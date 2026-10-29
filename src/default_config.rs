use crate::config::{Config, Mapping};

/// 既定の `pianotype.toml`。Android 版 `DefaultConfig.TOML` と同じ内容。
pub const DEFAULT_TOML: &str = r#"# ============================================================
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

# かなモードの ON/OFF（PC 版では OS の IME 切替キーを送出）
[[mapping]]
keys = [35]
value = "KANA"

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
"#;

/// かなモード用の既定マッピング。
///
/// Android 版 `DefaultConfig.kanaMappings` と同じ配列。
/// PC 版では使用しないが、Android と同じ TOML を書き出すことで
/// 設定ファイルをそのまま受け渡しできる。
///
/// 左手で 3 音のコード（行）を押し、右手で 1 オクターブ内の音（列）を選ぶ。
/// 右手の音は 60(C4)〜71(B4) の 12 音を使う。
pub fn default_kana_mappings() -> Vec<Mapping> {
    fn row(left: &[u8], values: &[&str], out: &mut Vec<Mapping>) {
        for (i, v) in values.iter().enumerate() {
            if v.is_empty() {
                continue;
            }
            let mut keys = left.to_vec();
            keys.push(60 + i as u8);
            keys.sort_unstable();
            out.push(Mapping {
                keys,
                value: (*v).to_string(),
            });
        }
    }

    let mut out = Vec::new();
    // 制御コマンド (英数モードと同じ位置に置く)
    out.push(Mapping {
        keys: vec![33],
        value: "TOGGLE".into(),
    });
    out.push(Mapping {
        keys: vec![34],
        value: "SHIFT".into(),
    });
    out.push(Mapping {
        keys: vec![35],
        value: "KANA".into(),
    });

    // あ行 + 小書き母音 + 長音
    row(
        &[48, 52, 55],
        &["あ", "い", "う", "え", "お", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ", "ー", "・"],
        &mut out,
    );
    // か行 + 濁音
    row(
        &[50, 53, 57],
        &["か", "き", "く", "け", "こ", "が", "ぎ", "ぐ", "げ", "ご", "ゔ"],
        &mut out,
    );
    // さ行 + 濁点
    row(
        &[52, 55, 59],
        &["さ", "し", "す", "せ", "そ", "ざ", "じ", "ず", "ぜ", "ぞ", "っ"],
        &mut out,
    );
    // た行 + 濁点
    row(
        &[41, 45, 48],
        &["た", "ち", "つ", "て", "と", "だ", "ぢ", "づ", "で", "ど", "゛", "゜"],
        &mut out,
    );
    // な行 + 小書きや行 + 繰り返し記号
    row(
        &[43, 47, 50],
        &["な", "に", "ぬ", "ね", "の", "ゃ", "ゅ", "ょ", "ゝ", "ゞ", "ヽ", "ヾ"],
        &mut out,
    );
    // は行 + 濁点 + 半濁音(2)
    row(
        &[45, 48, 52],
        &["は", "ひ", "ふ", "へ", "ほ", "ば", "び", "ぶ", "べ", "ぼ", "ぱ", "ぴ"],
        &mut out,
    );
    // 半濁点の残り + ま行 + や行
    row(
        &[47, 50, 53],
        &["ぷ", "ぺ", "ぽ", "ま", "み", "む", "め", "も", "や", "ゆ", "よ", "「"],
        &mut out,
    );
    // ら行 + わ行 + 句読点
    row(
        &[49, 52, 55],
        &["ら", "り", "る", "れ", "ろ", "わ", "を", "ん", "、", "。", "？", "！"],
        &mut out,
    );
    // 編集キー
    row(
        &[44, 47, 51],
        &[
            "SPACE", "ENTER", "BACKSPACE", "TAB", "ESC", "LEFT", "RIGHT", "UP", "DOWN", "HOME",
            "END", "DELETE",
        ],
        &mut out,
    );
    // 数字
    row(
        &[46, 49, 53],
        &["0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "PAGEUP", "PAGEDOWN"],
        &mut out,
    );
    // ファンクションキー
    row(
        &[44, 48, 51],
        &[
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
        ],
        &mut out,
    );
    out
}

/// 内蔵の既定設定を生成する。
pub fn default_config() -> Config {
    let mut cfg: Config =
        toml::from_str(DEFAULT_TOML).expect("built-in default TOML must be valid");
    cfg.kana_mappings = default_kana_mappings();
    cfg
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_toml_is_valid_and_has_toggle() {
        let cfg: Config = toml::from_str(DEFAULT_TOML).expect("valid toml");
        assert!(cfg
            .mappings
            .iter()
            .any(|m| m.value.eq_ignore_ascii_case("TOGGLE")));
        assert!(cfg
            .mappings
            .iter()
            .any(|m| m.value.eq_ignore_ascii_case("KANA")));
        assert_eq!(cfg.hand_split, 60);
    }

    #[test]
    fn kana_mappings_have_no_conflict_with_romaji() {
        let cfg = default_config();
        assert!(cfg.kana_mappings.len() > 100);
        // 同一コードが二重登録されていないこと
        let mut seen = std::collections::HashSet::new();
        for m in &cfg.kana_mappings {
            assert!(seen.insert(m.keys.clone()), "duplicated chord: {:?}", m.keys);
        }
    }
}
