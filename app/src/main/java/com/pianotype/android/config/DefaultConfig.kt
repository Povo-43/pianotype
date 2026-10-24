package com.pianotype.android.config

object DefaultConfig {

    /**
     * 初回起動時や「おすすめ設定に戻す」で使う既定設定。
     * PC 版 pianotype の TOML と互換。
     */
    val TOML: String = """
        # ============================================================
        # pianotype.toml  (Android 版 / PC 版 共通)
        # MIDI note: C4 = 60
        # ============================================================

        hand_split = 60
        require_both_hands = false

        # ---------------- 制御コマンド ----------------

        # タイピング ON/OFF。88鍵の左下端 A1 を想定。
        [[mapping]]
        keys = [33]
        value = "TOGGLE"

        # 次の1アクションだけ Shift を適用（大文字・記号用）
        [[mapping]]
        keys = [34]
        value = "SHIFT"

        # ---------------- 英小文字 a〜l (左手: C major) ----------------
        [[mapping]]
        keys = [48, 52, 55, 60]
        value = "a"
        [[mapping]]
        keys = [48, 52, 55, 61]
        value = "b"
        [[mapping]]
        keys = [48, 52, 55, 62]
        value = "c"
        [[mapping]]
        keys = [48, 52, 55, 63]
        value = "d"
        [[mapping]]
        keys = [48, 52, 55, 64]
        value = "e"
        [[mapping]]
        keys = [48, 52, 55, 65]
        value = "f"
        [[mapping]]
        keys = [48, 52, 55, 66]
        value = "g"
        [[mapping]]
        keys = [48, 52, 55, 67]
        value = "h"
        [[mapping]]
        keys = [48, 52, 55, 68]
        value = "i"
        [[mapping]]
        keys = [48, 52, 55, 69]
        value = "j"
        [[mapping]]
        keys = [48, 52, 55, 70]
        value = "k"
        [[mapping]]
        keys = [48, 52, 55, 71]
        value = "l"

        # ---------------- 英小文字 m〜x (左手: D minor) ----------------
        [[mapping]]
        keys = [50, 53, 57, 60]
        value = "m"
        [[mapping]]
        keys = [50, 53, 57, 61]
        value = "n"
        [[mapping]]
        keys = [50, 53, 57, 62]
        value = "o"
        [[mapping]]
        keys = [50, 53, 57, 63]
        value = "p"
        [[mapping]]
        keys = [50, 53, 57, 64]
        value = "q"
        [[mapping]]
        keys = [50, 53, 57, 65]
        value = "r"
        [[mapping]]
        keys = [50, 53, 57, 66]
        value = "s"
        [[mapping]]
        keys = [50, 53, 57, 67]
        value = "t"
        [[mapping]]
        keys = [50, 53, 57, 68]
        value = "u"
        [[mapping]]
        keys = [50, 53, 57, 69]
        value = "v"
        [[mapping]]
        keys = [50, 53, 57, 70]
        value = "w"
        [[mapping]]
        keys = [50, 53, 57, 71]
        value = "x"

        # ---------------- y, z + 編集キー (左手: E minor) ----------------
        [[mapping]]
        keys = [52, 55, 59, 60]
        value = "y"
        [[mapping]]
        keys = [52, 55, 59, 61]
        value = "z"
        [[mapping]]
        keys = [52, 55, 59, 62]
        value = "SPACE"
        [[mapping]]
        keys = [52, 55, 59, 63]
        value = "ENTER"
        [[mapping]]
        keys = [52, 55, 59, 64]
        value = "BACKSPACE"
        [[mapping]]
        keys = [52, 55, 59, 65]
        value = "TAB"
        [[mapping]]
        keys = [52, 55, 59, 66]
        value = "ESC"
        [[mapping]]
        keys = [52, 55, 59, 67]
        value = "."
        [[mapping]]
        keys = [52, 55, 59, 68]
        value = ","
        [[mapping]]
        keys = [52, 55, 59, 69]
        value = "-"
        [[mapping]]
        keys = [52, 55, 59, 70]
        value = "/"
        [[mapping]]
        keys = [52, 55, 59, 71]
        value = "?"

        # ---------------- 数字 0〜9 (左手: F major) ----------------
        [[mapping]]
        keys = [41, 45, 48, 60]
        value = "0"
        [[mapping]]
        keys = [41, 45, 48, 61]
        value = "1"
        [[mapping]]
        keys = [41, 45, 48, 62]
        value = "2"
        [[mapping]]
        keys = [41, 45, 48, 63]
        value = "3"
        [[mapping]]
        keys = [41, 45, 48, 64]
        value = "4"
        [[mapping]]
        keys = [41, 45, 48, 65]
        value = "5"
        [[mapping]]
        keys = [41, 45, 48, 66]
        value = "6"
        [[mapping]]
        keys = [41, 45, 48, 67]
        value = "7"
        [[mapping]]
        keys = [41, 45, 48, 68]
        value = "8"
        [[mapping]]
        keys = [41, 45, 48, 69]
        value = "9"

        # ---------------- 矢印キー (左手: G major) ----------------
        [[mapping]]
        keys = [43, 47, 50, 60]
        value = "LEFT"
        [[mapping]]
        keys = [43, 47, 50, 62]
        value = "RIGHT"
        [[mapping]]
        keys = [43, 47, 50, 64]
        value = "UP"
        [[mapping]]
        keys = [43, 47, 50, 65]
        value = "DOWN"
        [[mapping]]
        keys = [43, 47, 50, 67]
        value = "HOME"
        [[mapping]]
        keys = [43, 47, 50, 69]
        value = "END"
        [[mapping]]
        keys = [43, 47, 50, 71]
        value = "PAGEUP"
        [[mapping]]
        keys = [43, 47, 50, 72]
        value = "PAGEDOWN"

        # ---------------- F1〜F12 (左手: A minor) ----------------
        [[mapping]]
        keys = [45, 48, 52, 60]
        value = "F1"
        [[mapping]]
        keys = [45, 48, 52, 61]
        value = "F2"
        [[mapping]]
        keys = [45, 48, 52, 62]
        value = "F3"
        [[mapping]]
        keys = [45, 48, 52, 63]
        value = "F4"
        [[mapping]]
        keys = [45, 48, 52, 64]
        value = "F5"
        [[mapping]]
        keys = [45, 48, 52, 65]
        value = "F6"
        [[mapping]]
        keys = [45, 48, 52, 66]
        value = "F7"
        [[mapping]]
        keys = [45, 48, 52, 67]
        value = "F8"
        [[mapping]]
        keys = [45, 48, 52, 68]
        value = "F9"
        [[mapping]]
        keys = [45, 48, 52, 69]
        value = "F10"
        [[mapping]]
        keys = [45, 48, 52, 70]
        value = "F11"
        [[mapping]]
        keys = [45, 48, 52, 71]
        value = "F12"
    """.trimIndent() + "\n"

    /**
     * かなモード用の既定マッピング。
     *
     * 左手で 3 音のコード（行）を押し、右手で 1 オクターブ内の音（列）を選ぶ。
     * 右手の音は 60(C4)〜71(B4) の 12 音を使う。
     *
     *   あ行 [48,52,55] / か行 [50,53,57] / さ行 [52,55,59] / た行 [41,45,48]
     *   な行 [43,47,50] / は行 [45,48,52] / ぱ・ま・や行 [47,50,53] / ら・わ行 [49,52,55]
     *   編集 [44,47,51] / 数字 [46,49,53] / ファンクション [44,48,51]
     */
    val kanaMappings: List<Mapping> = buildList {
        // 制御コマンド (英数モードと同じ位置に置く)
        add(Mapping(listOf(33), "TOGGLE"))
        add(Mapping(listOf(34), "SHIFT"))

        fun row(left: List<Int>, values: List<String>) {
            values.forEachIndexed { i, v ->
                if (v.isEmpty()) return@forEachIndexed
                add(Mapping((left + (60 + i)).sorted(), v))
            }
        }

        // あ行 + 小書き母音 + 長音
        row(listOf(48, 52, 55), listOf("あ","い","う","え","お","ぁ","ぃ","ぅ","ぇ","ぉ","ー","・"))
        // か行 + 濁点
        row(listOf(50, 53, 57), listOf("か","き","く","け","こ","が","ぎ","ぐ","げ","ご","ゔ"))
        // さ行 + 濁点
        row(listOf(52, 55, 59), listOf("さ","し","す","せ","そ","ざ","じ","ず","ぜ","ぞ","っ"))
        // た行 + 濁点
        row(listOf(41, 45, 48), listOf("た","ち","つ","て","と","だ","ぢ","づ","で","ど","゛","゜"))
        // な行 + 小書きや行 + 繰り返し記号
        row(listOf(43, 47, 50), listOf("な","に","ぬ","ね","の","ゃ","ゅ","ょ","ゝ","ゞ","ヽ","ヾ"))
        // は行 + 濁点 + 半濁点(2)
        row(listOf(45, 48, 52), listOf("は","ひ","ふ","へ","ほ","ば","び","ぶ","べ","ぼ","ぱ","ぴ"))
        // 半濁点の残り + ま行 + や行
        row(listOf(47, 50, 53), listOf("ぷ","ぺ","ぽ","ま","み","む","め","も","や","ゆ","よ","「"))
        // ら行 + わ行 + 句読点
        row(listOf(49, 52, 55), listOf("ら","り","る","れ","ろ","わ","を","ん","、","。","？","！"))
        // 編集キー
        row(listOf(44, 47, 51), listOf("SPACE","ENTER","BACKSPACE","TAB","ESC","LEFT","RIGHT","UP","DOWN","HOME","END","DELETE"))
        // 数字
        row(listOf(46, 49, 53), listOf("0","1","2","3","4","5","6","7","8","9","PAGEUP","PAGEDOWN"))
        // ファンクションキー
        row(listOf(44, 48, 51), listOf("F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12"))
    }

    fun parse(): Config = TomlConfig.parse(TOML).copy(kanaMapping = kanaMappings)
}