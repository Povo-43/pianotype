package com.pianotype.android.keyboard

import android.view.KeyEvent

sealed class OutputAction {
    data class Text(val text: String) : OutputAction()
    data class Key(
        val keyCode: Int,
        val shift: Boolean = false,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
    ) : OutputAction()
}

/**
 * PC版 pianotype の value 文字列を Android の入力アクションへ変換する。
 * ここは純粋関数。IME サービスから呼び出される。
 */
object ValueParser {

    fun parse(value: String, shiftArmed: Boolean): OutputAction? {
        val upper = value.uppercase()
        if (upper == "TOGGLE" || upper == "SHIFT") return null

        // MOD+key
        val plus = value.indexOf('+')
        if (plus > 0) {
            val mod = value.substring(0, plus).trim()
            val rest = value.substring(plus + 1).trim()
            val modCtrl = mod.equals("CTRL", true) || mod.equals("CONTROL", true)
            val modAlt = mod.equals("ALT", true)
            val modShift = mod.equals("SHIFT", true)
            val modMeta = mod.equals("META", true) || mod.equals("SUPER", true) ||
                    mod.equals("WIN", true) || mod.equals("CMD", true)
            if (modCtrl || modAlt || modShift || modMeta) {
                val kc = keyCode(rest.uppercase()) ?: charKeyCode(rest) ?: return null
                return OutputAction.Key(
                    kc,
                    shift = shiftArmed || modShift,
                    ctrl = modCtrl,
                    alt = modAlt,
                )
            }
        }

        val kc = keyCode(upper)
        if (kc != null) return OutputAction.Key(kc, shift = shiftArmed)

        if (value.length == 1) {
            val text = if (shiftArmed) value.uppercase() else value
            return OutputAction.Text(text)
        }

        // かな・記号などの非 ASCII 文字列はそのまま入力する (きゃ, ー など)。
        if (value.any { it.code > 0x7F }) return OutputAction.Text(value)

        return null
    }

    fun isValid(value: String): Boolean {
        val upper = value.uppercase()
        if (upper == "TOGGLE" || upper == "SHIFT") return true
        val plus = value.indexOf('+')
        if (plus > 0) {
            val mod = value.substring(0, plus).trim()
            val rest = value.substring(plus + 1).trim()
            val modOk = mod.equals("CTRL", true) || mod.equals("CONTROL", true) ||
                    mod.equals("ALT", true) || mod.equals("SHIFT", true) ||
                    mod.equals("META", true) || mod.equals("SUPER", true) ||
                    mod.equals("WIN", true) || mod.equals("CMD", true)
            if (modOk && (keyCode(rest.uppercase()) != null || rest.length == 1)) return true
        }
        if (keyCode(upper) != null) return true
        if (value.length == 1) return true
        // かな・記号などの非 ASCII 文字列
        return value.any { it.code > 0x7F }
    }

    private fun charKeyCode(s: String): Int? {
        if (s.length != 1) return null
        val c = s[0].uppercaseChar()
        return when (c) {
            in 'A'..'Z' -> KeyEvent.KEYCODE_A + (c - 'A')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
            else -> null
        }
    }

    private fun keyCode(name: String): Int? {
        val n = name.replace("_", "").replace("-", "").replace(" ", "")
        return when (n) {
            "SPACE" -> KeyEvent.KEYCODE_SPACE
            "ENTER", "RETURN" -> KeyEvent.KEYCODE_ENTER
            "BACKSPACE", "BKSP" -> KeyEvent.KEYCODE_DEL
            "TAB" -> KeyEvent.KEYCODE_TAB
            "ESC", "ESCAPE" -> KeyEvent.KEYCODE_ESCAPE
            "LEFT", "LEFTARROW" -> KeyEvent.KEYCODE_DPAD_LEFT
            "RIGHT", "RIGHTARROW" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "UP", "UPARROW" -> KeyEvent.KEYCODE_DPAD_UP
            "DOWN", "DOWNARROW" -> KeyEvent.KEYCODE_DPAD_DOWN
            "HOME" -> KeyEvent.KEYCODE_MOVE_HOME
            "END" -> KeyEvent.KEYCODE_MOVE_END
            "DELETE", "DEL" -> KeyEvent.KEYCODE_FORWARD_DEL
            "INSERT", "INS" -> KeyEvent.KEYCODE_INSERT
            "PAGEUP", "PGUP" -> KeyEvent.KEYCODE_PAGE_UP
            "PAGEDOWN", "PGDN" -> KeyEvent.KEYCODE_PAGE_DOWN
            "CAPSLOCK" -> KeyEvent.KEYCODE_CAPS_LOCK
            "NUMLOCK" -> KeyEvent.KEYCODE_NUM_LOCK
            "SCROLLLOCK" -> KeyEvent.KEYCODE_SCROLL_LOCK
            "PRINTSCREEN", "PRTSC", "SNAPSHOT", "PRINT", "SYSRQ" -> KeyEvent.KEYCODE_SYSRQ
            "PAUSE", "BREAK" -> KeyEvent.KEYCODE_BREAK
            "HELP" -> KeyEvent.KEYCODE_HELP
            "CLEAR" -> KeyEvent.KEYCODE_CLEAR
            "VOLUMEMUTE", "MUTE" -> KeyEvent.KEYCODE_VOLUME_MUTE
            "VOLUMEDOWN" -> KeyEvent.KEYCODE_VOLUME_DOWN
            "VOLUMEUP" -> KeyEvent.KEYCODE_VOLUME_UP
            "MEDIANEXT", "MEDIANEXTTRACK" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "MEDIAPREV", "MEDIAPREVTRACK" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "MEDIASTOP" -> KeyEvent.KEYCODE_MEDIA_STOP
            "MEDIAPLAYPAUSE", "PLAYPAUSE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "NUMPAD0", "KP0" -> KeyEvent.KEYCODE_NUMPAD_0
            "NUMPAD1", "KP1" -> KeyEvent.KEYCODE_NUMPAD_1
            "NUMPAD2", "KP2" -> KeyEvent.KEYCODE_NUMPAD_2
            "NUMPAD3", "KP3" -> KeyEvent.KEYCODE_NUMPAD_3
            "NUMPAD4", "KP4" -> KeyEvent.KEYCODE_NUMPAD_4
            "NUMPAD5", "KP5" -> KeyEvent.KEYCODE_NUMPAD_5
            "NUMPAD6", "KP6" -> KeyEvent.KEYCODE_NUMPAD_6
            "NUMPAD7", "KP7" -> KeyEvent.KEYCODE_NUMPAD_7
            "NUMPAD8", "KP8" -> KeyEvent.KEYCODE_NUMPAD_8
            "NUMPAD9", "KP9" -> KeyEvent.KEYCODE_NUMPAD_9
            "NUMPADADD", "ADD", "KPADD" -> KeyEvent.KEYCODE_NUMPAD_ADD
            "NUMPADSUBTRACT", "SUBTRACT", "KPSUBTRACT", "NUMPADMINUS" -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT
            "NUMPADMULTIPLY", "MULTIPLY", "KPMULTIPLY" -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
            "NUMPADDIVIDE", "DIVIDE", "KPDIVIDE" -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
            "NUMPADDECIMAL", "DECIMAL", "KPDECIMAL" -> KeyEvent.KEYCODE_NUMPAD_DOT
            "NUMPADENTER", "KPENTER" -> KeyEvent.KEYCODE_NUMPAD_ENTER
            "NUMPADEQUAL", "KPEQUAL" -> KeyEvent.KEYCODE_NUMPAD_EQUALS
            else -> {
                if (n.length >= 2 && n[0] == 'F') {
                    val num = n.substring(1).toIntOrNull() ?: return null
                    if (num in 1..12) KeyEvent.KEYCODE_F1 + (num - 1) else null
                } else null
            }
        }
    }
}