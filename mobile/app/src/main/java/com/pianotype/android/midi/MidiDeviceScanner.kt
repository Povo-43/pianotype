package com.pianotype.android.midi

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager

data class MidiDeviceItem(
    val id: Int,
    val name: String,
    val info: MidiDeviceInfo,
)

object MidiDeviceScanner {
    fun list(context: Context): List<MidiDeviceItem> {
        val mm = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
        return mm.devices.mapIndexed { idx, info ->
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
                ?: "MIDI Device #$idx"
            MidiDeviceItem(info.id, name, info)
        }
    }
}