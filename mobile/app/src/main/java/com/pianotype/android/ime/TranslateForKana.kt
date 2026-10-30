
package com.pianotype.android.ime

class KanaConverter {
    private val table = mapOf(
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",

        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す",
        "se" to "せ", "so" to "そ", "sha" to "しゃ", "shu" to "しゅ",
        "sho" to "しょ", "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ",
        "tu" to "つ", "te" to "て", "to" to "と",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "fu" to "ふ", "hu" to "ふ",
        "he" to "へ", "ho" to "ほ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wo" to "を",

        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "za" to "ざ", "ji" to "じ", "zi" to "じ", "zu" to "ず",
        "ze" to "ぜ", "zo" to "ぞ",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",

        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ"
    )

    private var pending = ""

    /**
     * ローマ字を入力し、確定できたひらがなを返す。
     * 未確定のローマ字は内部に保持する。
     */
    fun input(char: Char): String {
        val c = char.lowercaseChar()

        if (c !in 'a'..'z') {
            val result = flush()
            return result + c
        }

        pending += c
        val output = StringBuilder()

        while (pending.isNotEmpty()) {
            // まだ長いローマ字になる可能性がある場合は保留
            if (table.keys.any { it.startsWith(pending) }) break

            // 先頭から最長一致を探す
            val match = table.keys
                .filter { pending.startsWith(it) }
                .maxByOrNull { it.length }

            if (match != null) {
                output.append(table.getValue(match))
                pending = pending.drop(match.length)
            } else if (
                pending.length >= 2 &&
                pending[0] == pending[1] &&
                pending[0] !in "aiueon"
            ) {
                // kk -> っk
                output.append("っ")
                pending = pending.drop(1)
            } else if (pending.length >= 2 && pending[0] == 'n' && pending[1] == 'n') {
                output.append("ん")
                pending = pending.drop(1)
            } else {
                // 変換できない先頭文字をそのまま出す
                output.append(pending[0])
                pending = pending.drop(1)
            }
        }

        return output.toString()
    }

    /** 保留中の文字を確定する */
    fun flush(): String {
        val result = when (pending) {
            "n", "nn" -> "ん"
            else -> pending
        }
        pending = ""
        return result
    }

    fun reset() {
        pending = ""
    }
}
