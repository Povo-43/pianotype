package com.pianotype.android.ime

import com.pianotype.android.config.Config
import com.pianotype.android.config.Mapping

/**
 * 保持しているノート集合と完全一致するマッピングを探して発火する。
 * 両手条件などの制約は無し。
 */
class MappingEngine(
    private val configProvider: () -> Config,
    private val onToggle: (Boolean) -> Unit,
    private val onShiftArmed: () -> Unit,
    private val onAction: (value: String, shifted: Boolean) -> Unit,
) {
    private val held = HashSet<Int>()
    private val emitted = HashSet<List<Int>>()
    private var enabled = false
    private var shiftArmed = false

    @Synchronized
    fun onNoteOn(note: Int) {
        held.add(note)
        val current = held.sorted()
        if (current.isEmpty() || emitted.contains(current)) return

        val mapping = findMapping(configProvider(), current) ?: return
        emitted.add(current)

        when (mapping.value.uppercase()) {
            "TOGGLE" -> {
                enabled = !enabled
                shiftArmed = false
                onToggle(enabled)
            }
            "SHIFT" -> if (enabled) {
                shiftArmed = true
                onShiftArmed()
            }
            else -> if (enabled) {
                val s = shiftArmed
                shiftArmed = false
                onAction(mapping.value, s)
            }
        }
    }

    @Synchronized
    fun onNoteOff(note: Int) {
        held.remove(note)
        emitted.clear()
    }

    @Synchronized
    fun reset() {
        held.clear()
        emitted.clear()
        shiftArmed = false
    }

    @Synchronized
    fun isEnabled(): Boolean = enabled

    private fun findMapping(config: Config, heldNotes: List<Int>): Mapping? {
        val heldSet = heldNotes.toSet()
        // かなモードでは kanaMapping、それ以外は mapping を使う。
        return config.activeMapping.firstOrNull { it.keys.toSet() == heldSet }
    }
}