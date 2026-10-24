package com.pianotype.android.config

data class Mapping(
    val keys: List<Int>,
    val value: String,
)

data class Config(
    val midiDevice: String? = null,
    val midiChannel: Int? = null,   // 1..16, null = 全チャンネル
    val handSplit: Int = 60,
    val requireBothHands: Boolean = true,
    val mapping: List<Mapping> = emptyList(),        // 英数モードのマッピング
    val kanaMapping: List<Mapping> = emptyList(),    // かなモードのマッピング
    val kanaMode: Boolean = false,                   // 現在かなモードかどうか
) {
    /** 現在のモードで実際に使うマッピング。 */
    val activeMapping: List<Mapping>
        get() = if (kanaMode && kanaMapping.isNotEmpty()) kanaMapping else mapping
}
