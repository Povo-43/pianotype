use enigo::{Enigo, Key, KeyboardControllable};

/// Android 版 MappingEngine の `value` 文字列を Enigo 経由で送出する。
///
/// 対応形式:
/// - 名前付きキー: ENTER / TAB / SPACE / ESC / BACKSPACE / DELETE /
///   UP / DOWN / LEFT / RIGHT / HOME / END / PAGEUP / PAGEDOWN
/// - 修飾キー付き: "CTRL+C", "CMD+Z", "ALT+F4", "CTRL+SHIFT+T" など
/// - 単一文字: "a", "1", "?"
/// - それ以外: そのままキーシーケンスとして送出
pub fn send_command(enigo: &mut Enigo, value: &str, shifted: bool) {
    if shifted {
        enigo.key_down(Key::Shift);
    }

    let segments: Vec<&str> = value.split('+').map(str::trim).collect();
    let mut modifiers: Vec<Key> = Vec::new();
    let mut main_key: Option<Key> = None;
    let mut main_char: Option<char> = None;
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
                    let chars: Vec<char> = seg.chars().collect();
                    if chars.len() == 1 {
                        main_char = Some(chars[0]);
                    } else {
                        literal = Some((*seg).to_string());
                    }
                }
            }
        }
    }

    for m in &modifiers {
        enigo.key_down(*m);
    }
    if let Some(k) = main_key {
        enigo.key_click(k);
    } else if let Some(c) = main_char {
        enigo.key_sequence(&c.to_string());
    } else if let Some(s) = literal {
        enigo.key_sequence(&s);
    }
    for m in modifiers.iter().rev() {
        enigo.key_up(*m);
    }

    if shifted {
        enigo.key_up(Key::Shift);
    }
}

fn named_key(name: &str) -> Option<Key> {
    Some(match name {
        "ENTER" | "RETURN" => Key::Return,
        "TAB" => Key::Tab,
        "SPACE" => Key::Space,
        "ESC" | "ESCAPE" => Key::Escape,
        "BACKSPACE" | "BACK" => Key::Backspace,
        "DELETE" | "DEL" => Key::Delete,
        "UP" => Key::UpArrow,
        "DOWN" => Key::DownArrow,
        "LEFT" => Key::LeftArrow,
        "RIGHT" => Key::RightArrow,
        "HOME" => Key::Home,
        "END" => Key::End,
        "PAGEUP" => Key::PageUp,
        "PAGEDOWN" => Key::PageDown,
        _ => return None,
    })
}
