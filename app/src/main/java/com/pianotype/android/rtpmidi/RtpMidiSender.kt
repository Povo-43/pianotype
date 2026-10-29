package com.pianotype.android.rtpmidi

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.random.Random

/** UI 表示用のセッション情報 */
data class RtpSessionInfo(
    val name: String,
    val address: String,
    val connected: Boolean,
    val incoming: Boolean,      // 相手から INV されて成立したセッション
)

/** RTP MIDI 送信機能全体の状態 */
data class RtpMidiStatus(
    val running: Boolean = false,
    val localPort: Int = 0,
    val target: String? = null,             // 能動接続の接続先 (host:port)
    val sessions: List<RtpSessionInfo> = emptyList(),
    val notesSent: Int = 0,
    val lastError: String? = null,
)

/** 設定画面から購読する状態バス */
object RtpMidiBus {
    private val mutable = MutableStateFlow(RtpMidiStatus())
    val status: StateFlow<RtpMidiStatus> = mutable
    fun publish(status: RtpMidiStatus) { mutable.value = status }
}

/**
 * RTP-MIDI (AppleMIDI) で MIDI ノートを送信する。
 *
 * - 設定された host:port へ能動的に接続する (INV → OK)
 * - 自身の control ポート (port) / data ポート (port+1) でも待ち受け、
 *   相手からの INV を受け入れる
 * - [sendNoteOn] / [sendNoteOff] で RTP パケットを data ポートへ送る
 *
 * パケット形式は Apple 実装・rtpmidid・Windows rtpMIDI と互換:
 *
 *   INV/OK/NO/BY : 0xFFFF, cmd, protocol=2, initiatorToken, ssrc, name\0
 *   CK           : 0xFFFF, 'CK', ssrc, count, pad(3), ts1..ts3 (u64, 100us 単位)
 *   RTP MIDI     : 0x80, 0x61 (PT=97), seq, timestamp (100us 単位), ssrc, len, MIDI bytes
 */
class RtpMidiSender(
    localName: String = "Pianotype Android",
    private val onStatus: (RtpMidiStatus) -> Unit = {},
) : Closeable {

    companion object {
        private const val TAG = "RtpMidi"
        private const val RECV_TIMEOUT_MS = 1000
        private const val RETRY_INVITE_MS = 2500L
        private const val SESSION_TIMEOUT_MS = 90_000L
        private const val PROTOCOL = 2
    }

    @Volatile private var localName: String = localName.trim().ifEmpty { "Pianotype Android" }

    private val lock = Any()        // peers / initiatorToken の保護
    private val rtpLock = Any()     // RTP ヘッダ生成 (seq) の保護
    private val hkLock = Any()      // housekeeping の多重実行防止

    private val localSSRC: Int = Random.nextInt()
    private var seq: Int = Random.nextInt() and 0xFFFF
    private val tsBase: Long = Random.nextLong(0, 1L shl 32)
    private val startedNano: Long = System.nanoTime()

    @Volatile private var running = false
    @Volatile private var controlSocket: DatagramSocket? = null
    @Volatile private var midiSocket: DatagramSocket? = null
    private var controlThread: Thread? = null
    private var midiThread: Thread? = null

    @Volatile var localPort: Int = 0
        private set

    // 能動接続 (initiator) の状態
    @Volatile private var targetHost: String? = null
    @Volatile private var targetPort: Int = 5004
    @Volatile private var initiatorToken: Int = 0
    @Volatile private var lastInviteMs = 0L

    // セッション一覧 (能動接続 + 受入れた接続)
    private val peers = LinkedHashMap<Int, Peer>()

    @Volatile var notesSent: Int = 0
        private set
    @Volatile private var lastError: String? = null
    private var lastHkMs = 0L

    private class Peer(
        val ssrc: Int,
        @Volatile var name: String,
        val incoming: Boolean,
        @Volatile var token: Int,       // BY 送付時に使う initiator token
    ) {
        @Volatile var controlAddr: InetSocketAddress? = null
        @Volatile var midiAddr: InetSocketAddress? = null
        @Volatile var controlConnected = false
        @Volatile var midiConnected = false
        @Volatile var lastSeenMs = System.currentTimeMillis()
        val connected: Boolean get() = controlConnected && midiConnected && midiAddr != null
    }

    /** 待ち受けを開始し、host が指定されていれば能動接続を試みる。 */
    @Synchronized
    fun start(bindPort: Int, host: String?, remotePort: Int) {
        if (running) return
        targetHost = host?.trim()?.takeIf { it.isNotEmpty() }
        targetPort = remotePort.coerceIn(1, 65534)
        running = true
        lastError = null

        controlSocket = openSocket(bindPort)
        midiSocket = openSocket(bindPort + 1)
        localPort = controlSocket?.localPort ?: 0

        controlThread = thread(name = "RtpMidiControl") { loop(controlSocket!!, midi = false) }
        midiThread = thread(name = "RtpMidiData") { loop(midiSocket!!, midi = true) }

        lastInviteMs = 0L   // 最初の housekeeping で INV を送る
        Log.i(TAG, "started: local=$localPort target=${targetHost ?: "(listen only)"}:${targetPort}")
        notifyStatus()
    }

    /** 設定変更を反映。接続先が変わった場合は既存セッションを切って再接続する。 */
    fun reconfigure(host: String?, remotePort: Int, name: String?) {
        val newHost = host?.trim()?.takeIf { it.isNotEmpty() }
        val newPort = remotePort.coerceIn(1, 65534)
        if (!name.isNullOrBlank()) localName = name.trim()

        if (newHost != targetHost || newPort != targetPort) {
            val olds = synchronized(lock) { peers.values.toList() }
            for (p in olds) sendBye(p)
            synchronized(lock) { peers.clear() }
            targetHost = newHost
            targetPort = newPort
            lastInviteMs = 0L   // 次の housekeeping で INV
            Log.i(TAG, "target changed: ${newHost ?: "(listen only)"}:$newPort")
        }
        notifyStatus()
    }

    override fun close() {
        if (!running) return
        running = false
        val snapshot = synchronized(lock) { peers.values.toList() }
        for (p in snapshot) sendBye(p)
        synchronized(lock) { peers.clear() }
        runCatching { controlSocket?.close() }
        runCatching { midiSocket?.close() }
        controlSocket = null
        midiSocket = null
        runCatching { controlThread?.join(500) }
        runCatching { midiThread?.join(500) }
        controlThread = null
        midiThread = null
        Log.i(TAG, "closed")
        notifyStatus()
    }

    // ------------------------------------------------------------------
    // MIDI 送信
    // ------------------------------------------------------------------

    /** channel は 1..16 */
    fun sendNoteOn(channel: Int, note: Int, velocity: Int) =
        sendNote(0x90, channel, note, velocity)

    /** channel は 1..16 */
    fun sendNoteOff(channel: Int, note: Int, velocity: Int) =
        sendNote(0x80, channel, note, velocity)

    private fun sendNote(type: Int, channel: Int, note: Int, velocity: Int) {
        if (!running) return
        val ch = (channel - 1).coerceIn(0, 15)
        val packet = ByteArray(16)
        synchronized(rtpLock) {
            seq = (seq + 1) and 0xFFFF
            val ts = rtpTimestamp()
            packet[0] = 0x80.toByte()                     // V=2, P/X/CC なし
            packet[1] = 0x61                              // PT=97 (RTP-MIDI)
            packet[2] = (seq ushr 8).toByte()
            packet[3] = seq.toByte()
            packet[4] = (ts ushr 24).toByte()
            packet[5] = (ts ushr 16).toByte()
            packet[6] = (ts ushr 8).toByte()
            packet[7] = ts.toByte()
            packet[8] = (localSSRC ushr 24).toByte()
            packet[9] = (localSSRC ushr 16).toByte()
            packet[10] = (localSSRC ushr 8).toByte()
            packet[11] = localSSRC.toByte()
            packet[12] = 3.toByte()                       // MIDI command section (3 バイト, ジャーナルなし)
            packet[13] = (type or ch).toByte()
            packet[14] = (note and 0x7F).toByte()
            packet[15] = (velocity and 0x7F).toByte()
        }
        val targets = synchronized(lock) {
            peers.values.filter { it.connected }.mapNotNull { it.midiAddr }
        }
        if (targets.isEmpty()) return
        val sock = midiSocket ?: return
        for (addr in targets) {
            runCatching { synchronized(sock) { sock.send(DatagramPacket(packet, packet.size, addr)) } }
                .onFailure { Log.w(TAG, "midi send failed -> $addr: ${it.message}") }
        }
        notesSent++
    }

    // ------------------------------------------------------------------
    // 受信ループ
    // ------------------------------------------------------------------

    private fun loop(socket: DatagramSocket, midi: Boolean) {
        val buf = ByteArray(4096)
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket.receive(pkt)
            } catch (e: SocketTimeoutException) {
                housekeeping()
                continue
            } catch (e: Exception) {
                if (running) Log.w(TAG, "receive error: ${e.message}")
                break
            }
            val from = pkt.socketAddress as? InetSocketAddress ?: continue
            runCatching { handlePacket(buf, pkt.length, from, midi) }
                .onFailure { Log.w(TAG, "packet error: ${it.message}") }
        }
    }

    private fun handlePacket(data: ByteArray, len: Int, from: InetSocketAddress, midi: Boolean) {
        if (len < 4) return
        val sig = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (sig != 0xFFFF) {
            // RTP パケット (相手からの MIDI など) は転送対象外。生存更新のみ。
            touchPeer(from)
            return
        }
        when (String(data, 2, 2, Charsets.US_ASCII)) {
            "IN" -> onInvitation(data, len, from, midi)
            "OK" -> onOk(data, len, from, midi)
            "NO" -> onRejected(data, len, from, midi)
            "BY" -> onBye(data, len, from, midi)
            "CK" -> onClockSync(data, len, from, midi)
            else -> { /* "RS" (receiver feedback) などは無視 */ }
        }
    }

    /** 相手からの接続要求 → OK を返して受け入れる */
    private fun onInvitation(data: ByteArray, len: Int, from: InetSocketAddress, midi: Boolean) {
        val bb = ByteBuffer.wrap(data, 4, len - 4)
        if (bb.remaining() < 12) return
        val protocol = bb.int
        val token = bb.int
        val ssrc = bb.int
        val name = readName(data, bb)
        if (protocol != PROTOCOL) {
            Log.w(TAG, "unsupported protocol $protocol from $from")
            return
        }

        val ok = applePacket("OK") {
            it.writeInt(PROTOCOL)
            it.writeInt(token)
            it.writeInt(localSSRC)
            it.writeBytes(localName)
            it.writeByte(0)
        }
        sendFrom(midi, ok, from)

        val now = System.currentTimeMillis()
        synchronized(lock) {
            val p = peers.getOrPut(ssrc) {
                Peer(ssrc, name.ifEmpty { from.hostString ?: "?" }, incoming = true, token = token)
            }
            if (name.isNotEmpty()) p.name = name
            p.token = token
            p.lastSeenMs = now
            if (midi) {
                p.midiConnected = true
                p.midiAddr = from
            } else {
                p.controlConnected = true
                p.controlAddr = from
            }
        }
        Log.i(TAG, "invitation from \"${name}\" $from (midi=$midi) ssrc=%08X".format(ssrc))
        notifyStatus()
    }

    /** 自分の INV への応答 */
    private fun onOk(data: ByteArray, len: Int, from: InetSocketAddress, midi: Boolean) {
        val bb = ByteBuffer.wrap(data, 4, len - 4)
        if (bb.remaining() < 12) return
        val protocol = bb.int
        val token = bb.int
        if (protocol != PROTOCOL) return
        if (token != initiatorToken) return   // 自分の INV に対する応答でない
        val ssrc = bb.int
        val name = readName(data, bb)
        val now = System.currentTimeMillis()

        var needMidiInvite = false
        synchronized(lock) {
            val p = peers.getOrPut(ssrc) {
                Peer(ssrc, name.ifEmpty { from.hostString ?: "?" }, incoming = false, token = initiatorToken)
            }
            if (name.isNotEmpty() && p.name.isEmpty()) p.name = name
            p.lastSeenMs = now
            if (midi) {
                p.midiConnected = true
                p.midiAddr = from
            } else {
                p.controlConnected = true
                p.controlAddr = from
                if (!p.midiConnected) needMidiInvite = true   // control OK → data ポートへも INV
            }
        }
        if (needMidiInvite) {
            sendInvitation(InetSocketAddress(from.address, targetPort + 1))
        }
        Log.i(TAG, "ok from $from (midi=$midi)")
        notifyStatus()
    }

    private fun onRejected(data: ByteArray, len: Int, from: InetSocketAddress, midi: Boolean) {
        val bb = ByteBuffer.wrap(data, 4, len - 4)
        if (bb.remaining() < 12) return
        val protocol = bb.int
        val token = bb.int
        val ssrc = bb.int
        if (protocol != PROTOCOL || token != initiatorToken) return
        lastError = "接続を拒否されました ($from)"
        synchronized(lock) { peers.remove(ssrc) }
        Log.w(TAG, "invitation rejected by $from")
        notifyStatus()
    }

    private fun onBye(data: ByteArray, len: Int, from: InetSocketAddress, midi: Boolean) {
        val bb = ByteBuffer.wrap(data, 4, len - 4)
        if (bb.remaining() < 12) return
        bb.int   // protocol
        bb.int   // token
        val ssrc = bb.int
        val removed = synchronized(lock) { peers.remove(ssrc) }
        if (removed != null) {
            Log.i(TAG, "bye from \"${removed.name}\" ($from)")
            notifyStatus()
        }
    }

    /** CK (時計同期) に応答する。macOS / rtpmidid は同期ラウンドを送ってくる。 */
    private fun onClockSync(data: ByteArray, len: Int, from: InetSocketAddress, midi: Boolean) {
        if (len < 36) return
        val bb = ByteBuffer.wrap(data, 4, len - 4)
        val ssrc = bb.int
        val count = bb.get().toInt() and 0xFF
        bb.get()      // padding
        bb.short      // padding
        val ck1 = bb.long
        when (count) {
            0 -> sendCk(1, ck1, now100us(), 0L, from, midi)
            1 -> {
                val ck2 = bb.long
                sendCk(2, ck1, ck2, now100us(), from, midi)
            }
            // count=2 は返信不要
        }
        synchronized(lock) { peers[ssrc]?.lastSeenMs = System.currentTimeMillis() }
    }

    private fun touchPeer(from: InetSocketAddress) {
        synchronized(lock) {
            peers.values.firstOrNull { it.controlAddr == from || it.midiAddr == from }
                ?.let { it.lastSeenMs = System.currentTimeMillis() }
        }
    }

    // ------------------------------------------------------------------
    // 制御パケット送信
    // ------------------------------------------------------------------

    private fun sendInvitation(to: InetSocketAddress) {
        val inv = applePacket("IN") {
            it.writeInt(PROTOCOL)
            it.writeInt(initiatorToken)
            it.writeInt(localSSRC)
            it.writeBytes(localName)
            it.writeByte(0)
        }
        // control ポート宛は control socket から、data ポート宛は midi socket から送る
        sendFrom(to.port != targetPort, inv, to)
    }

    private fun sendCk(count: Int, t1: Long, t2: Long, t3: Long, to: InetSocketAddress, midi: Boolean) {
        val pkt = applePacket("CK") {
            it.writeInt(localSSRC)
            it.writeByte(count)
            it.writeByte(0)     // padding
            it.writeShort(0)    // padding
            it.writeLong(t1)
            it.writeLong(t2)
            it.writeLong(t3)
        }
        sendFrom(midi, pkt, to)
    }

    private fun sendBye(p: Peer) {
        val bye = applePacket("BY") {
            it.writeInt(PROTOCOL)
            it.writeInt(p.token)
            it.writeInt(localSSRC)
        }
        p.controlAddr?.let { sendFrom(false, bye, it) }
        p.midiAddr?.let { sendFrom(true, bye, it) }
    }

    private fun sendFrom(midi: Boolean, payload: ByteArray, to: InetSocketAddress) {
        val sock = (if (midi) midiSocket else controlSocket) ?: return
        runCatching { synchronized(sock) { sock.send(DatagramPacket(payload, payload.size, to)) } }
            .onFailure { Log.w(TAG, "send failed -> $to: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // ハウスキーピング (受信タイムアウト時に両スレッドから ~1Hz で呼ばれる)
    // ------------------------------------------------------------------

    private fun housekeeping() {
        val now = System.currentTimeMillis()
        synchronized(hkLock) {
            if (now - lastHkMs < 900) return
            lastHkMs = now
        }

        // 能動接続の (再) INV
        val host = targetHost
        if (host != null) {
            val established = synchronized(lock) {
                peers.values.any { !it.incoming && it.connected }
            }
            if (!established && now - lastInviteMs >= RETRY_INVITE_MS) {
                lastInviteMs = now
                if (!inviteControl(host)) {
                    lastError = "ホストを解決できません: $host"
                }
            }
        }

        // タイムアウトしたセッションを破棄
        val stale = synchronized(lock) {
            peers.values.filter { now - it.lastSeenMs > SESSION_TIMEOUT_MS }
        }
        if (stale.isNotEmpty()) {
            for (p in stale) {
                sendBye(p)
                synchronized(lock) { peers.remove(p.ssrc) }
            }
            Log.i(TAG, "reaped ${stale.size} stale session(s)")
        }

        notifyStatus()
    }

    /** control ポートへ INV。ホスト名解決に失敗したら false。 */
    private fun inviteControl(host: String): Boolean {
        initiatorToken = Random.nextInt()
        // 前ラウンドの未確立セッションを破棄
        synchronized(lock) { peers.entries.removeAll { !it.value.incoming && !it.value.connected } }
        val ctl: InetSocketAddress = try {
            InetSocketAddress(InetAddress.getByName(host), targetPort)
        } catch (e: Exception) {
            return false
        }
        lastError = null
        sendInvitation(ctl)
        return true
    }

    // ------------------------------------------------------------------
    // 状態通知
    // ------------------------------------------------------------------

    private fun notifyStatus() {
        val sessions = synchronized(lock) {
            peers.values.map {
                RtpSessionInfo(
                    name = it.name,
                    address = "${it.midiAddr ?: it.controlAddr ?: "?"}",
                    connected = it.connected,
                    incoming = it.incoming,
                )
            }
        }
        onStatus(
            RtpMidiStatus(
                running = running,
                localPort = localPort,
                target = targetHost?.let { "$it:$targetPort" },
                sessions = sessions,
                notesSent = notesSent,
                lastError = lastError,
            )
        )
    }

    // ------------------------------------------------------------------
    // ユーティリティ
    // ------------------------------------------------------------------

    private fun openSocket(port: Int): DatagramSocket =
        try {
            DatagramSocket(null).apply {
                bind(InetSocketAddress(port))
                soTimeout = RECV_TIMEOUT_MS
            }
        } catch (e: Exception) {
            Log.w(TAG, "port $port busy, using ephemeral: ${e.message}")
            DatagramSocket(0).apply { soTimeout = RECV_TIMEOUT_MS }
        }

    /** モノトニック時計を 100us 単位で返す (CK 用) */
    private fun now100us(): Long = (System.nanoTime() - startedNano) / 100_000L

    /** RTP タイムスタンプ (100us 単位, ランダムオフセット付き) */
    private fun rtpTimestamp(): Int = (tsBase + now100us()).toInt()

    /** AppleMIDI 制御パケットを組み立てる */
    private inline fun applePacket(command: String, block: (DataOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { dos ->
            dos.writeShort(0xFFFF)
            dos.writeBytes(command)
            block(dos)
        }
        return bos.toByteArray()
    }

    /** ByteBuffer の現在位置から NUL 終端文字列を読む */
    private fun readName(data: ByteArray, bb: ByteBuffer): String {
        val start = bb.position()
        var p = start
        val limit = bb.limit()
        while (p < limit && data[p] != 0.toByte()) p++
        val name = String(data, start, p - start, Charsets.UTF_8)
        if (p < limit) p++   // NUL を読み飛ばす
        bb.position(p)
        return name
    }
}
