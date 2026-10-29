use crate::config::{Config, Mapping};
use std::collections::HashSet;

/// エンジンが発火するアクション。Android 版 MappingEngine.kt と同じ意味論。
#[derive(Debug, Clone, PartialEq)]
pub enum Action {
    /// TOGGLE コードが成立し、有効/無効が切り替わった
    Toggle(bool),
    /// SHIFT コードが成立し、次コマンドが shift 状態で送られる
    ShiftArmed,
    /// KANA コードが成立。PC 版では OS の IME 切替キーを送出する
    KanaIme,
    /// 通常コマンド: (value, shifted)
    SendKey(String, bool),
    /// 何も起きない
    Ignored,
}

pub struct MappingEngine {
    held: HashSet<u8>,
    emitted: HashSet<Vec<u8>>,
    enabled: bool,
    shift_armed: bool,
}

impl MappingEngine {
    pub fn new() -> Self {
        Self {
            held: HashSet::new(),
            emitted: HashSet::new(),
            enabled: false,
            shift_armed: false,
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }
    pub fn is_shift_armed(&self) -> bool {
        self.shift_armed
    }
    pub fn held_notes(&self) -> Vec<u8> {
        let mut v: Vec<u8> = self.held.iter().copied().collect();
        v.sort_unstable();
        v
    }

    pub fn on_note_on(&mut self, note: u8, config: &Config) -> Action {
        self.held.insert(note);
        let mut current: Vec<u8> = self.held.iter().copied().collect();
        current.sort_unstable();

        if current.is_empty() || self.emitted.contains(&current) {
            return Action::Ignored;
        }

        let mapping = match find_mapping(config, &current) {
            Some(m) => m.clone(),
            None => return Action::Ignored,
        };
        self.emitted.insert(current);

        match mapping.value.to_ascii_uppercase().as_str() {
            "TOGGLE" => {
                self.enabled = !self.enabled;
                self.shift_armed = false;
                Action::Toggle(self.enabled)
            }
            "SHIFT" => {
                if self.enabled {
                    self.shift_armed = true;
                    Action::ShiftArmed
                } else {
                    Action::Ignored
                }
            }
            // Android 版と同じ: モード切替はエンジンの外 (PC 版では IME キー送出)
            "KANA" => {
                self.shift_armed = false;
                Action::KanaIme
            }
            _ => {
                if self.enabled {
                    let shifted = self.shift_armed;
                    self.shift_armed = false;
                    Action::SendKey(mapping.value.clone(), shifted)
                } else {
                    Action::Ignored
                }
            }
        }
    }

    pub fn on_note_off(&mut self, note: u8) {
        self.held.remove(&note);
        // Android 版に合わせ、1 音でも離したら発火済みフラグをクリアする
        // (和音を離し直せば再度発火できる)
        self.emitted.clear();
    }

    pub fn reset(&mut self) {
        self.held.clear();
        self.emitted.clear();
        self.shift_armed = false;
    }
}

fn find_mapping<'a>(config: &'a Config, held: &[u8]) -> Option<&'a Mapping> {
    let held_set: HashSet<u8> = held.iter().copied().collect();
    config
        .mappings
        .iter()
        .find(|m| m.keys.iter().copied().collect::<HashSet<_>>() == held_set)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Mapping;

    fn cfg() -> Config {
        Config {
            midi_device: None,
            midi_channel: None,
            hand_split: 60,
            require_both_hands: true,
            kana_mode: false,
            rtp_midi_enabled: false,
            rtp_midi_host: None,
            rtp_midi_port: 5004,
            rtp_midi_channel: None,
            rtp_midi_name: "Pianotype PC".into(),
            mappings: vec![
                Mapping {
                    keys: vec![60, 64, 67],
                    value: "TOGGLE".into(),
                },
                Mapping {
                    keys: vec![62, 65, 69],
                    value: "SHIFT".into(),
                },
                Mapping {
                    keys: vec![60, 63, 67],
                    value: "ENTER".into(),
                },
                Mapping {
                    keys: vec![35],
                    value: "KANA".into(),
                },
            ],
            kana_mappings: Vec::new(),
        }
    }

    #[test]
    fn toggle_then_command() {
        let c = cfg();
        let mut e = MappingEngine::new();
        assert_eq!(e.on_note_on(60, &c), Action::Ignored);
        assert_eq!(e.on_note_on(64, &c), Action::Ignored);
        assert_eq!(e.on_note_on(67, &c), Action::Toggle(true));
        e.on_note_off(60);
        e.on_note_off(64);
        e.on_note_off(67);

        assert_eq!(e.on_note_on(60, &c), Action::Ignored);
        assert_eq!(e.on_note_on(63, &c), Action::Ignored);
        assert_eq!(e.on_note_on(67, &c), Action::SendKey("ENTER".into(), false));
    }

    #[test]
    fn shift_arms_next_command() {
        let c = cfg();
        let mut e = MappingEngine::new();
        e.on_note_on(60, &c);
        e.on_note_on(64, &c);
        e.on_note_on(67, &c); // TOGGLE → 有効化
        e.reset();

        // 有効な状態で SHIFT コード
        assert_eq!(e.on_note_on(62, &c), Action::Ignored);
        assert_eq!(e.on_note_on(65, &c), Action::Ignored);
        assert_eq!(e.on_note_on(69, &c), Action::ShiftArmed);
        assert!(e.is_shift_armed());
    }

    #[test]
    fn kana_command_fires_when_enabled() {
        let c = cfg();
        let mut e = MappingEngine::new();
        e.on_note_on(60, &c);
        e.on_note_on(64, &c);
        e.on_note_on(67, &c); // TOGGLE → enabled
        e.reset();

        e.on_note_on(35, &c);
        assert_eq!(e.held_notes(), vec![35]);
        // KANA は有効時のみ
        e.on_note_off(35);
        assert_eq!(e.on_note_on(35, &c), Action::KanaIme);
    }

    #[test]
    fn repressing_a_note_retriggers_after_release() {
        // Android 版と同じ: ノートを離すと emitted がクリアされる
        let c = cfg();
        let mut e = MappingEngine::new();
        e.on_note_on(60, &c);
        e.on_note_on(64, &c);
        e.on_note_on(67, &c); // TOGGLE
        e.on_note_off(67);
        // 67 を離したのでもう一度押すと発火する
        assert_eq!(e.on_note_on(67, &c), Action::Toggle(false));
    }
}
