use enigo::{Direction, Enigo, Key, Keyboard};

/// Android 版 `ValueParser` の `value` 文字列を Enigo 経由で送出する。
///
/// 対応形式:
/// - 名前付きキー: ENTER / TAB / SPACE / ESC / BACKSPACE / DELETE / INSERT /
///   UP / DOWN / LEFT / RIGHT / HOME / END / PAGEUP / PAGEDOWN / F1-F12 /
///   NUMPAD0-9 / VOLUMEUP / MEDIANEXT / CAPSLOCK など (Android 版と同名)
/// - 修飾キー付き: "CTRL+C", "CMD+Z", "ALT+F4", "CTRL+SHIFT+T" など
/// - 単一文字: "a", "1", "?"
/// - それ以外: そのままテキストとして送出 (かな文字・複数文字も可)
pub fn send_command(enigo: &mut Enigo, value: &str, shifted: bool) {
    if let Err(e) = try_send_command(enigo, value, shifted) {
        log::warn!("failed to send {value:?}: {e}");
    }
}

fn try_send_command(enigo: &mut Enigo, value: &str, shifted: bool) -> Result<(), String> {
    if shifted {
        enigo
            .key(Key::Shift, Direction::Press)
            .map_err(|e| format!("shift press: {e}"))?;
    }

    let segments: Vec<&str> = value.split('+').map(str::trim).collect();
    let mut modifiers: Vec<Key> = Vec::new();
    let mut main_key: Option<Key> = None;
    let mut literal: Option<String> = None;

    for seg in &segments {
        let up = seg.to_ascii_uppercase();
        match up.as_str() {
            "CTRL" | "CONTROL" => modifiers.push(Key::Control),
            "SHIFT" => modifiers.push(Key::Shift),
            "ALT" | "OPTION" => modifiers.push(Key::Alt),
            "META" | "CMD" | "COMMAND" | "SUPER" | "WIN" => modifiers.push(Key::Meta),
            _ => {
                if let Some(k) = named_key(&up) {
                    main_key = Some(k);
                } else {
                    literal = Some((*seg).to_string());
                }
            }
        }
    }

    let result = (|| -> Result<(), String> {
        for m in &modifiers {
            enigo.key(*m, Direction::Press).map_err(|e| e.to_string())?;
        }
        if let Some(k) = main_key {
            enigo.key(k, Direction::Click).map_err(|e| e.to_string())?;
        } else if let Some(s) = literal {
            enigo.text(&s).map_err(|e| e.to_string())?;
        }
        for m in modifiers.iter().rev() {
            enigo.key(*m, Direction::Release).map_err(|e| e.to_string())?;
        }
        Ok(())
    })();

    if shifted {
        let _ = enigo.key(Key::Shift, Direction::Release);
    }
    result
}

/// OS の日本語 IME をトグルするキーを送出する (`KANA` コマンド)。
///
/// - Windows: かなキー (VK_KANA)
/// - Linux/X11: 半角/全角 (XK_Zenkaku_Hankaku)
/// - macOS: JIS かなキー (kVK_JIS_Kana = 0x68)
pub fn send_ime_kana_toggle(enigo: &mut Enigo) {
    let key = ime_toggle_key();
    if let Err(e) = enigo.key(key, Direction::Click) {
        log::warn!("failed to toggle IME with {key:?}: {e}");
    }
}

fn ime_toggle_key() -> Key {
    #[cfg(target_os = "windows")]
    {
        Key::Kana
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        Key::Other(0xFF2A) // XK_Zenkaku_Hankaku
    }
    #[cfg(target_os = "macos")]
    {
        Key::Other(104) // kVK_JIS_Kana
    }
}

fn named_key(name: &str) -> Option<Key> {
    // 名前の正規化 (Android 版 ValueParser と同じ: _ / - / 空白を除去)
    let n: String = name
        .chars()
        .filter(|c| !matches!(c, '_' | '-' | ' '))
        .collect();
    if let Some(k) = common_named_key(&n) {
        return Some(k);
    }
    if let Some(k) = numpad_key(&n) {
        return Some(k);
    }
    function_key(&n)
}

fn common_named_key(n: &str) -> Option<Key> {
    Some(match n {
        "ENTER" | "RETURN" => Key::Return,
        "TAB" => Key::Tab,
        "SPACE" => Key::Space,
        "ESC" | "ESCAPE" => Key::Escape,
        "BACKSPACE" | "BACK" | "BKSP" => Key::Backspace,
        "DELETE" | "DEL" => Key::Delete,
        "UP" | "UPARROW" => Key::UpArrow,
        "DOWN" | "DOWNARROW" => Key::DownArrow,
        "LEFT" | "LEFTARROW" => Key::LeftArrow,
        "RIGHT" | "RIGHTARROW" => Key::RightArrow,
        "HOME" => Key::Home,
        "END" => Key::End,
        "PAGEUP" | "PGUP" => Key::PageUp,
        "PAGEDOWN" | "PGDN" => Key::PageDown,
        "CAPSLOCK" => Key::CapsLock,
        #[cfg(any(target_os = "windows", all(unix, not(target_os = "macos"))))]
        "NUMLOCK" => Key::Numlock,
        #[cfg(all(unix, not(target_os = "macos")))]
        "SCROLLLOCK" => Key::ScrollLock,
        // PRINTSCREEN はプラットフォーム差が大きい: Linux/X11 は Print keysym
        #[cfg(all(unix, not(target_os = "macos")))]
        "PRINTSCREEN" | "PRTSC" | "SNAPSHOT" | "PRINT" | "SYSRQ" => Key::Print,
        #[cfg(any(target_os = "windows", all(unix, not(target_os = "macos"))))]
        "PAUSE" | "BREAK" => Key::Pause,
        #[cfg(any(target_os = "windows", all(unix, not(target_os = "macos"))))]
        "HELP" => Key::Help,
        #[cfg(any(target_os = "windows", all(unix, not(target_os = "macos"))))]
        "CLEAR" => Key::Clear,
        "VOLUMEMUTE" | "MUTE" => Key::VolumeMute,
        "VOLUMEDOWN" => Key::VolumeDown,
        "VOLUMEUP" => Key::VolumeUp,
        "MEDIANEXT" | "MEDIANEXTTRACK" => Key::MediaNextTrack,
        "MEDIAPREV" | "MEDIAPREVTRACK" => Key::MediaPrevTrack,
        "MEDIASTOP" => Key::MediaStop,
        "MEDIAPLAYPAUSE" | "PLAYPAUSE" => Key::MediaPlayPause,
        "INSERT" | "INS" => Key::Insert,
        "NUMPADENTER" | "KPENTER" => Key::Return,
        _ => return None,
    })
}

/// "KP0"→"NUMPAD0"、"ADD"→"NUMPADADD" のように別名を正規化する
fn normalize_numpad_name(name: &str) -> &str {
    match name {
        "KP0" => "NUMPAD0",
        "KP1" => "NUMPAD1",
        "KP2" => "NUMPAD2",
        "KP3" => "NUMPAD3",
        "KP4" => "NUMPAD4",
        "KP5" => "NUMPAD5",
        "KP6" => "NUMPAD6",
        "KP7" => "NUMPAD7",
        "KP8" => "NUMPAD8",
        "KP9" => "NUMPAD9",
        "ADD" => "NUMPADADD",
        "SUBTRACT" | "NUMPADMINUS" | "KPSUBTRACT" => "NUMPADSUBTRACT",
        "MULTIPLY" | "KPMULTIPLY" => "NUMPADMULTIPLY",
        "DIVIDE" | "KPDIVIDE" => "NUMPADDIVIDE",
        "DECIMAL" | "KPDECIMAL" => "NUMPADDECIMAL",
        "KPEQUAL" => "NUMPADEQUAL",
        other => other,
    }
}

fn function_key(n: &str) -> Option<Key> {
    if n.len() >= 2 && (n.starts_with('F') || n.starts_with('f')) {
        let num: u32 = n[1..].parse().ok()?;
        if !(1..=35).contains(&num) {
            return None;
        }
        // F1..F24 は全プラットフォーム共通、F25..F35 は Linux/X11 のみ
        return Some(match num {
            1 => Key::F1,
            2 => Key::F2,
            3 => Key::F3,
            4 => Key::F4,
            5 => Key::F5,
            6 => Key::F6,
            7 => Key::F7,
            8 => Key::F8,
            9 => Key::F9,
            10 => Key::F10,
            11 => Key::F11,
            12 => Key::F12,
            13 => Key::F13,
            14 => Key::F14,
            15 => Key::F15,
            16 => Key::F16,
            17 => Key::F17,
            18 => Key::F18,
            19 => Key::F19,
            20 => Key::F20,
            21 => Key::F21,
            22 => Key::F22,
            23 => Key::F23,
            24 => Key::F24,
            #[cfg(all(unix, not(target_os = "macos")))]
            25 => Key::F25,
            #[cfg(all(unix, not(target_os = "macos")))]
            26 => Key::F26,
            #[cfg(all(unix, not(target_os = "macos")))]
            27 => Key::F27,
            #[cfg(all(unix, not(target_os = "macos")))]
            28 => Key::F28,
            #[cfg(all(unix, not(target_os = "macos")))]
            29 => Key::F29,
            #[cfg(all(unix, not(target_os = "macos")))]
            30 => Key::F30,
            #[cfg(all(unix, not(target_os = "macos")))]
            31 => Key::F31,
            #[cfg(all(unix, not(target_os = "macos")))]
            32 => Key::F32,
            #[cfg(all(unix, not(target_os = "macos")))]
            33 => Key::F33,
            #[cfg(all(unix, not(target_os = "macos")))]
            34 => Key::F34,
            #[cfg(all(unix, not(target_os = "macos")))]
            35 => Key::F35,
            // Windows / macOS では F24 まで
            _ => return None,
        });
    }
    None
}

/// テンキー関連のキーをプラットフォーム別に解決する。
///
/// enigo のテンキー variant は Windows 専用のため、Linux では X11 keysym、
/// macOS では仮想キーコードを `Key::Other` で送る。
fn numpad_key(name: &str) -> Option<Key> {
    // KP9 のような別名を正規名に寄せる
    let name = normalize_numpad_name(name);
    #[cfg(target_os = "windows")]
    {
        Some(match name {
            "NUMPAD0" => Key::Numpad0,
            "NUMPAD1" => Key::Numpad1,
            "NUMPAD2" => Key::Numpad2,
            "NUMPAD3" => Key::Numpad3,
            "NUMPAD4" => Key::Numpad4,
            "NUMPAD5" => Key::Numpad5,
            "NUMPAD6" => Key::Numpad6,
            "NUMPAD7" => Key::Numpad7,
            "NUMPAD8" => Key::Numpad8,
            "NUMPAD9" => Key::Numpad9,
            "NUMPADADD" => Key::Add,
            "NUMPADSUBTRACT" => Key::Subtract,
            "NUMPADMULTIPLY" => Key::Multiply,
            "NUMPADDIVIDE" => Key::Divide,
            "NUMPADDECIMAL" => Key::Decimal,
            "NUMPADEQUAL" => return None,
            _ => return None,
        })
    }
    // Linux: X11 keysym (KP_0..KP_9, KP_Add, ...)
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        const KP: &[(&str, u32)] = &[
            ("NUMPAD0", 0xFFB0),
            ("NUMPAD1", 0xFFB1),
            ("NUMPAD2", 0xFFB2),
            ("NUMPAD3", 0xFFB3),
            ("NUMPAD4", 0xFFB4),
            ("NUMPAD5", 0xFFB5),
            ("NUMPAD6", 0xFFB6),
            ("NUMPAD7", 0xFFB7),
            ("NUMPAD8", 0xFFB8),
            ("NUMPAD9", 0xFFB9),
            ("NUMPADADD", 0xFFAB),      // KP_Add
            ("NUMPADSUBTRACT", 0xFFAD), // KP_Subtract
            ("NUMPADMULTIPLY", 0xFFAA), // KP_Multiply
            ("NUMPADDIVIDE", 0xFFAF),   // KP_Divide
            ("NUMPADDECIMAL", 0xFFAE),  // KP_Decimal
            ("NUMPADEQUAL", 0xFFBD),    // KP_Equal
        ];
        KP.iter()
            .find(|(n, _)| *n == name)
            .map(|(_, k)| Key::Other(*k))
    }
    // macOS: Carbon 仮想キーコード
    #[cfg(target_os = "macos")]
    {
        const KP: &[(&str, u32)] = &[
            ("NUMPAD0", 82),
            ("NUMPAD1", 83),
            ("NUMPAD2", 84),
            ("NUMPAD3", 85),
            ("NUMPAD4", 86),
            ("NUMPAD5", 87),
            ("NUMPAD6", 88),
            ("NUMPAD7", 89),
            ("NUMPAD8", 91),
            ("NUMPAD9", 92),
            ("NUMPADADD", 69),      // kVK_ANSI_KeypadPlus
            ("NUMPADSUBTRACT", 78), // kVK_ANSI_KeypadMinus
            ("NUMPADMULTIPLY", 67), // kVK_ANSI_KeypadMultiply
            ("NUMPADDIVIDE", 75),   // kVK_ANSI_KeypadDivide
            ("NUMPADDECIMAL", 65),  // kVK_ANSI_KeypadDecimal
            ("NUMPADEQUAL", 81),    // kVK_ANSI_KeypadEquals
        ];
        KP.iter()
            .find(|(n, _)| *n == name)
            .map(|(_, k)| Key::Other(*k))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn named_keys_resolve() {
        assert!(matches!(named_key("ENTER"), Some(Key::Return)));
        assert!(matches!(named_key("SPACE"), Some(Key::Space)));
        assert!(matches!(named_key("BACKSPACE"), Some(Key::Backspace)));
        assert!(matches!(named_key("BKSP"), Some(Key::Backspace)));
        assert!(matches!(named_key("LEFT"), Some(Key::LeftArrow)));
        assert!(matches!(named_key("LEFT_ARROW"), Some(Key::LeftArrow)));
        assert!(matches!(named_key("PGUP"), Some(Key::PageUp)));
        assert!(matches!(named_key("PAGE-UP"), Some(Key::PageUp)));
        assert!(matches!(named_key("F1"), Some(Key::F1)));
        assert!(matches!(named_key("f12"), Some(Key::F12)));
        // Numpad はプラットフォーム別に解決される
        // (Windows は専用 variant、Linux/macOS は Key::Other)
        #[cfg(target_os = "windows")]
        {
            assert!(matches!(named_key("NUMPAD5"), Some(Key::Numpad5)));
            assert!(matches!(named_key("KP9"), Some(Key::Numpad9)));
        }
        #[cfg(not(target_os = "windows"))]
        {
            assert!(matches!(named_key("NUMPAD5"), Some(Key::Other(_))));
            assert!(matches!(named_key("KP9"), Some(Key::Other(_))));
        }
        assert!(matches!(named_key("VOLUMEUP"), Some(Key::VolumeUp)));
        assert!(matches!(named_key("MUTE"), Some(Key::VolumeMute)));
        assert!(matches!(
            named_key("MEDIAPLAYPAUSE"),
            Some(Key::MediaPlayPause)
        ));
        assert!(matches!(named_key("INSERT"), Some(Key::Insert)));
        assert!(matches!(named_key("CAPSLOCK"), Some(Key::CapsLock)));
        // 未対応名
        assert!(named_key("HOGEPFUGA").is_none());
    }

    #[test]
    fn function_keys_range() {
        assert!(function_key("F1").is_some());
        assert!(function_key("F24").is_some());
        assert!(function_key("F36").is_none());
        assert!(function_key("F0").is_none());
        assert!(function_key("FX").is_none());
        // F25..F35 は Linux/X11 のみ
        #[cfg(all(unix, not(target_os = "macos")))]
        assert!(function_key("F35").is_some());
        #[cfg(not(all(unix, not(target_os = "macos"))))]
        assert!(function_key("F35").is_none());
    }
}
