package com.pianotype.android.config

data class Mapping(
    val keys: List<Int>,
    val value: String,
)

data class Config(
    val midiDevice: String? = null,
    val midiChannel: Int? = null,
    val handSplit: Int = 60,
    val requireBothHands: Boolean = true,
    val mapping: List<Mapping> = emptyList(),        // 英数モードのマッピング
    val kanaMapping: List<Mapping> = emptyList(),    // かなモードのマッピング
    val kanaMode: Boolean = false,                   // 現在かなモードかどうか
    val rtpMidiEnabled: Boolean = false,             // RTP MIDI 送信 (AppleMIDI)
    val rtpMidiHost: String? = null,                 // 接続先ホスト (null なら待ち受けのみ)
    val rtpMidiPort: Int = 5004,                     // control ポート (data は +1)
    val rtpMidiChannel: Int? = null,                 // 送信チャンネル上書き (1..16, null なら元のまま)
    val rtpMidiName: String = "Pianotype Android",   // セッション名
) {
    /** 現在のモードで実際に使うマッピング。 */
    val activeMapping: List<Mapping>
        get() = if (kanaMode && kanaMapping.isNotEmpty()) kanaMapping else mapping
}
