package com.pianotype.android.config

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

object TomlConfig {

    fun parse(text: String): Config {
        val result = Toml.parse(text)
        if (result.hasErrors()) {
            val msgs = result.errors().joinToString("\n") { it.toString() }
            throw IllegalArgumentException("Invalid TOML:\n$msgs")
        }
        val device = result.getString("midi_device")
        val channel = (result.getLong("midi_channel"))?.toInt()?.takeIf { it in 1..16 }
        val handSplit = (result.getLong("hand_split") ?: 60L).toInt()
        val bothHands = result.getBoolean("require_both_hands") ?: true
        val kanaMode = result.getBoolean("kana_mode") ?: false

        return Config(
            midiDevice = device,
            midiChannel = channel,
            handSplit = handSplit,
            requireBothHands = bothHands,
            mapping = parseMappings(result.getArray("mapping")),
            kanaMapping = parseMappings(result.getArray("kana_mapping")),
            kanaMode = kanaMode,
        )
    }

    private fun parseMappings(arr: TomlArray?): List<Mapping> {
        if (arr == null) return emptyList()
        val mappings = mutableListOf<Mapping>()
        for (i in 0 until arr.size()) {
            val t: TomlTable = arr.getTable(i) ?: continue
            val keysArr = t.getArray("keys") ?: continue
            val keys = (0 until keysArr.size()).mapNotNull { j ->
                (keysArr.get(j) as? Long)?.toInt()
            }.sorted()
            val value = t.getString("value") ?: continue
            mappings.add(Mapping(keys, value))
        }
        return mappings
    }

    fun serialize(config: Config): String = buildString {
        appendLine("# pianotype Android configuration")
        config.midiDevice?.let { appendLine("midi_device = \"${escape(it)}\"") }
        config.midiChannel?.let { appendLine("midi_channel = $it") }
        appendLine("hand_split = ${config.handSplit}")
        appendLine("require_both_hands = ${config.requireBothHands}")
        appendLine("kana_mode = ${config.kanaMode}")
        appendLine()
        appendMappings("mapping", config.mapping)
        appendMappings("kana_mapping", config.kanaMapping)
    }

    private fun StringBuilder.appendMappings(name: String, mappings: List<Mapping>) {
        if (mappings.isEmpty()) return
        for (m in mappings) {
            appendLine("[[$name]]")
            appendLine("keys = [${m.keys.joinToString(", ")}]")
            appendLine("value = \"${escape(m.value)}\"")
            appendLine()
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
