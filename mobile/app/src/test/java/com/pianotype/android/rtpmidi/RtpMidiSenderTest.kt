package com.pianotype.android.rtpmidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

// ループバック UDP でも CI 高負荷時はパケットが落ちたり、受信スレッドの
// スケジュール遅延で soTimeout を超えることがあるため数回リトライする
private const val RECEIVE_ATTEMPTS = 3

/** SocketTimeoutException を数回までリトライして receive する */
private fun DatagramSocket.receiveWithRetry(p: DatagramPacket) {
    var last: SocketTimeoutException? = null
    repeat(RECEIVE_ATTEMPTS) {
        try {
            receive(p)
            return
        } catch (e: SocketTimeoutException) {
            last = e
        }
    }
    throw last!!
}

/**
 * RtpMidiSender のプロトコル実装を、実 UDP ソケットを使う簡易 AppleMIDI ピア
 * (rtpmidid / macOS のふり) と対話させて検証する結合テスト。
 */
class RtpMidiSenderTest {

    // ------------------------------------------------------------------
    // 簡易 AppleMIDI ピア
    // ------------------------------------------------------------------

    /** INV/OK/CK を喋るテスト用ピア。controlPort / dataPort で待ち受ける。 */
    private class FakePeer(val controlPort: Int, val dataPort: Int, val ssrc: Int = 0x11223344) {
        val control = DatagramSocket(InetSocketAddress("127.0.0.1", controlPort)).apply { soTimeout = 5000 }
        val data = DatagramSocket(InetSocketAddress("127.0.0.1", dataPort)).apply { soTimeout = 5000 }

        fun close() {
            control.close()
            data.close()
        }

        /** 期待するコマンド ("IN" など) を受信して解析する */
        fun receive(socket: DatagramSocket, cmd: String): Packet {
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            socket.receiveWithRetry(p)
            val len = p.length
            assertEquals(0xFFFF, ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF))
            assertEquals(cmd, String(buf, 2, 2, Charsets.US_ASCII))
            return Packet(buf, len, InetSocketAddress(p.address, p.port))
        }

        class Packet(val data: ByteArray, val len: Int, val from: InetSocketAddress) {
            fun u32(off: Int): Int =
                ((data[off].toInt() and 0xFF) shl 24) or ((data[off + 1].toInt() and 0xFF) shl 16) or
                    ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
            fun u64(off: Int): Long {
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (data[off + i].toLong() and 0xFF)
                return v
            }
            fun name(off: Int): String {
                var p = off
                while (p < len && data[p] != 0.toByte()) p++
                return String(data, off, p - off, Charsets.UTF_8)
            }
        }

        /** INV を受け、OK を返す ( Apple の responder 挙動 ) */
        fun acceptInvitation(socket: DatagramSocket, replyFrom: DatagramSocket): Packet {
            val inv = receive(socket, "IN")
            assertEquals(2, inv.u32(4))                    // protocol
            val token = inv.u32(8)
            assertTrue(inv.u32(12) != 0)                   // 相手 SSRC
            val name = inv.name(16)
            assertTrue(name.isNotEmpty())
            // OK: protocol, token, 自分の ssrc, name
            val ok = java.io.ByteArrayOutputStream().let { bos ->
                java.io.DataOutputStream(bos).apply {
                    writeShort(0xFFFF); writeBytes("OK")
                    writeInt(2); writeInt(token); writeInt(ssrc)
                    writeBytes("FakeMIDI"); writeByte(0)
                }.flush()
                bos.toByteArray()
            }
            val from = inv.from
            replyFrom.send(DatagramPacket(ok, ok.size, from.address, from.port))
            return inv
        }
    }

    // ------------------------------------------------------------------
    // 1) 能動接続 (initiator): INV → OK → INV(data) → OK → RTP MIDI 送信
    // ------------------------------------------------------------------

    @Test
    fun initiatorHandshakeAndSendNotes() {
        val peer = FakePeer(controlPort = 26004, dataPort = 26005)
        val sender = RtpMidiSender("TestDevice") { RtpMidiBus.publish(it) }
        try {
            sender.start(bindPort = 25004, host = "127.0.0.1", remotePort = 26004)

            // control ポートへの INV → OK
            peer.acceptInvitation(peer.control, peer.control)
            // data ポートへの INV → OK
            peer.acceptInvitation(peer.data, peer.data)
            Thread.sleep(300)

            // Note On → RTP MIDI パケットが data ポートへ届く
            sender.sendNoteOn(1, 60, 100)
            val on = recvRtp(peer.data)
            assertEquals(0x90, on.statusByte)
            assertEquals(60, on.note)
            assertEquals(100, on.velocity)

            // Note Off
            sender.sendNoteOff(1, 60, 0)
            val off = recvRtp(peer.data)
            assertEquals(0x80, off.statusByte)
            assertEquals(60, off.note)

            // チャンネルを変えて送信 (CH 10 = 0x99)
            sender.sendNoteOn(10, 62, 99)
            val ch10 = recvRtp(peer.data)
            assertEquals(0x90 or 9, ch10.statusByte)
            assertEquals(62, ch10.note)

            // シーケンス番号が増加していること
            assertTrue(on.seq < ch10.seq)
        } finally {
            sender.close()
            peer.close()
        }
    }

    // ------------------------------------------------------------------
    // 2) 受入れた接続 (acceptor): 相手からの INV → OK → 転送
    // ------------------------------------------------------------------

    @Test
    fun acceptorAcceptsInvitationAndSends() {
        val sender = RtpMidiSender("TestDevice") { RtpMidiBus.publish(it) }
        val peer = FakePeer(controlPort = 28004, dataPort = 28005, ssrc = 0x55667788)
        try {
            // host なし = 待ち受けのみ (bind 25014/25015)
            sender.start(bindPort = 25014, host = null, remotePort = 5004)

            // 相手から INV (control) → OK
            val inv1 = java.io.ByteArrayOutputStream().let { bos ->
                java.io.DataOutputStream(bos).apply {
                    writeShort(0xFFFF); writeBytes("IN")
                    writeInt(2); writeInt(0xAABBCCDD.toInt()); writeInt(peer.ssrc)
                    writeBytes("FakeMIDI"); writeByte(0)
                }.flush()
                bos.toByteArray()
            }
            val senderCtl = InetSocketAddress("127.0.0.1", 25014)
            peer.control.send(DatagramPacket(inv1, inv1.size, senderCtl.address, senderCtl.port))
            peer.receive(peer.control, "OK")

            // 相手から INV (data) → OK
            val senderData = InetSocketAddress("127.0.0.1", 25015)
            peer.data.send(DatagramPacket(inv1, inv1.size, senderData.address, senderData.port))
            peer.receive(peer.data, "OK")

            // 転送相手は「相手の data ポート」なので、peer.data 宛に届く
            sender.sendNoteOn(3, 70, 80)
            val pkt = recvRtp(peer.data)
            assertEquals(0x90 or 2, pkt.statusByte)
            assertEquals(70, pkt.note)
            assertEquals(80, pkt.velocity)
        } finally {
            sender.close()
            peer.close()
        }
    }

    // ------------------------------------------------------------------
    // 3) CK (時計同期) への応答
    // ------------------------------------------------------------------

    @Test
    fun respondsToClockSync() {
        val peer = FakePeer(controlPort = 30004, dataPort = 30005)
        val sender = RtpMidiSender("TestDevice") { RtpMidiBus.publish(it) }
        try {
            sender.start(bindPort = 29004, host = "127.0.0.1", remotePort = 30004)
            val invCtl = peer.acceptInvitation(peer.control, peer.control)
            val senderSSRC = invCtl.u32(12)   // INV の送信者 SSRC
            peer.acceptInvitation(peer.data, peer.data)

            // CK0 を送る → CK1 (count=1, ck1 はエコー, ck2>0, ck3=0) が返る
            val t1 = 12345678L
            val ck0 = java.io.ByteArrayOutputStream().let { bos ->
                java.io.DataOutputStream(bos).apply {
                    writeShort(0xFFFF); writeBytes("CK")
                    writeInt(peer.ssrc)
                    writeByte(0); writeByte(0); writeShort(0)
                    writeLong(t1); writeLong(0); writeLong(0)
                }.flush()
                bos.toByteArray()
            }
            val to = InetSocketAddress("127.0.0.1", 29005)   // sender の data ポート
            peer.data.send(DatagramPacket(ck0, ck0.size, to.address, to.port))

            val rep = peer.receive(peer.data, "CK")
            assertEquals(senderSSRC, rep.u32(4))   // 応答者の SSRC = sender
            assertEquals(1, rep.data[8].toInt() and 0xFF)
            assertEquals(t1, rep.u64(12))
            assertTrue(rep.u64(20) > 0)   // ck2 = 自分の時計
            assertEquals(0L, rep.u64(28))
        } finally {
            sender.close()
            peer.close()
        }
    }

    // ------------------------------------------------------------------
    // ヘルパ
    // ------------------------------------------------------------------

    private class RtpMidi(
        val statusByte: Int,
        val note: Int,
        val velocity: Int,
        val seq: Int,
    )

    /** RTP MIDI パケットを 1 つ受信して解析する */
    private fun recvRtp(socket: DatagramSocket): RtpMidi {
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        socket.receiveWithRetry(p)
        val len = p.length
        assertEquals(128, buf[0].toInt() and 0xFF)
        assertEquals(0x61, buf[1].toInt() and 0xFF)
        val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        val cmdLen = buf[12].toInt() and 0xFF
        assertEquals(3, cmdLen)
        assertTrue(len >= 16)
        return RtpMidi(
            statusByte = buf[13].toInt() and 0xFF,
            note = buf[14].toInt() and 0x7F,
            velocity = buf[15].toInt() and 0x7F,
            seq = seq,
        )
    }
}
