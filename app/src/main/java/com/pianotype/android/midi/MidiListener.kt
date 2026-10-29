package com.pianotype.android.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import java.io.Closeable

sealed class MidiEvent {
    /** channel は 1..16 */
    data class NoteOn(val note: Int, val velocity: Int = 64, val channel: Int = 1) : MidiEvent()
    data class NoteOff(val note: Int, val velocity: Int = 0, val channel: Int = 1) : MidiEvent()
}

/**
 * 指定デバイス（null なら先頭）の MIDI 入力を購読する。
 * channelFilter を指定すると、その MIDI チャンネル (1..16) のみ通す。
 * null なら全チャンネルを通す。
 */
class MidiListener(
    context: Context,
    private val deviceSelector: String?,
    private val channelFilter: Int?,        // 1..16, null = any
    private val callback: (MidiEvent) -> Unit,
) : Closeable {

    private val mm = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null
    private var receiver: MidiReceiver? = null

    fun start() {
        val devices = mm.devices
        val chosen = pick(devices) ?: return
        mm.openDevice(chosen, { dev ->
            if (dev == null) return@openDevice
            device = dev
            val p = dev.openOutputPort(0) ?: return@openDevice
            port = p
            val wantChannel = channelFilter                 // 1..16 (受信側も 1 始まりに正規化)
            val r = object : MidiReceiver() {
                override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    var i = offset
                    val end = offset + count
                    while (i + 2 < end) {
                        val status = data[i].toInt() and 0xFF
                        val type = status and 0xF0
                        val channel = (status and 0x0F) + 1   // 1..16
                        val note = data[i + 1].toInt() and 0x7F
                        val vel = data[i + 2].toInt() and 0x7F
                        if (wantChannel == null || channel == wantChannel) {
                            if (type == 0x90 && vel > 0) {
                                callback(MidiEvent.NoteOn(note, vel, channel))
                            } else if (type == 0x80 || (type == 0x90 && vel == 0)) {
                                callback(MidiEvent.NoteOff(note, vel, channel))
                            }
                        }
                        i += 3
                    }
                }
            }
            receiver = r
            p.connect(r)
        }, null)
    }

    private fun pick(devices: Array<MidiDeviceInfo>): MidiDeviceInfo? {
        if (devices.isEmpty()) return null
        val sel = deviceSelector ?: return devices.first()
        val asIndex = sel.toIntOrNull()
        if (asIndex != null && asIndex in devices.indices) return devices[asIndex]
        return devices.firstOrNull { info ->
            info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) == sel
        } ?: devices.first()
    }

    override fun close() {
        runCatching { port?.disconnect(receiver) }
        runCatching { port?.close() }
        runCatching { device?.close() }
        port = null
        receiver = null
        device = null
    }
}