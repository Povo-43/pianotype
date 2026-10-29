use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const DEFAULT_HAND_SPLIT: u8 = 60;
pub const DEFAULT_RTP_PORT: u16 = 5004;

fn default_hand_split() -> u8 {
    DEFAULT_HAND_SPLIT
}
fn default_both_hands() -> bool {
    true
}
fn default_rtp_port() -> u16 {
    DEFAULT_RTP_PORT
}
fn default_rtp_name() -> String {
    "Pianotype PC".to_string()
}

/// PianoType の設定。`pianotype_mobile` と同一スキーマを維持する。
///
/// PC 版では `KANA` コマンドは OS の IME 切替キー (半角/全角・かなキー) を送出する。
/// `kana_mode` / `[[kana_mapping]]` はモバイル版との設定ファイル互換のため
/// 読み書きするが、PC 版自身は変換に使用しない (Android へエクスポートした
/// ときに活きる)。`rtp_midi_*` は RTP MIDI (AppleMIDI) 送受信の設定。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub midi_device: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub midi_channel: Option<u8>,
    #[serde(default = "default_hand_split")]
    pub hand_split: u8,
    #[serde(default = "default_both_hands")]
    pub require_both_hands: bool,
    /// 現在かなモードかどうか (PC 版では保存のみ)
    #[serde(default)]
    pub kana_mode: bool,
    /// RTP MIDI 送受信を有効化 (AppleMIDI)
    #[serde(default)]
    pub rtp_midi_enabled: bool,
    /// 接続先ホスト (null なら待ち受けのみ)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rtp_midi_host: Option<String>,
    /// control ポート (data は +1)
    #[serde(default = "default_rtp_port")]
    pub rtp_midi_port: u16,
    /// 送信チャンネル上書き (1..16, null なら元のまま)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rtp_midi_channel: Option<u8>,
    /// セッション名
    #[serde(default = "default_rtp_name")]
    pub rtp_midi_name: String,
    /// 英数モードのマッピング
    #[serde(default, rename = "mapping")]
    pub mappings: Vec<Mapping>,
    /// かなモードのマッピング (PC 版では読み書きのみ)
    #[serde(default, rename = "kana_mapping", skip_serializing_if = "Vec::is_empty")]
    pub kana_mappings: Vec<Mapping>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Mapping {
    pub keys: Vec<u8>,
    pub value: String,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            midi_device: None,
            midi_channel: None,
            hand_split: DEFAULT_HAND_SPLIT,
            require_both_hands: true,
            kana_mode: false,
            rtp_midi_enabled: false,
            rtp_midi_host: None,
            rtp_midi_port: DEFAULT_RTP_PORT,
            rtp_midi_channel: None,
            rtp_midi_name: default_rtp_name(),
            mappings: Vec::new(),
            kana_mappings: Vec::new(),
        }
    }
}

impl Config {
    pub fn load(path: &Path) -> Result<Self> {
        let text = std::fs::read_to_string(path)
            .with_context(|| format!("failed to read config: {}", path.display()))?;
        let cfg: Config = toml::from_str(&text)
            .with_context(|| format!("failed to parse TOML: {}", path.display()))?;
        Ok(cfg)
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        let text = toml::to_string_pretty(self)?;
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).ok();
        }
        std::fs::write(path, text)
            .with_context(|| format!("failed to write config: {}", path.display()))?;
        Ok(())
    }

    pub fn default_path() -> PathBuf {
        if let Some(dirs) = directories::ProjectDirs::from("dev", "Povo-43", "pianotype") {
            dirs.config_dir().join("pianotype.toml")
        } else {
            PathBuf::from("pianotype.toml")
        }
    }

    pub fn load_or_default(path: &Path) -> Self {
        Self::load(path).unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_keeps_mobile_keys() {
        let cfg = Config {
            kana_mode: true,
            rtp_midi_enabled: true,
            rtp_midi_host: Some("192.168.1.10".into()),
            rtp_midi_port: 5004,
            rtp_midi_channel: Some(5),
            mappings: vec![Mapping {
                keys: vec![60, 64, 67],
                value: "TOGGLE".into(),
            }],
            ..Default::default()
        };
        let text = toml::to_string_pretty(&cfg).unwrap();
        // モバイル版と同じキー名で書き出される
        assert!(text.contains("kana_mode = true"));
        assert!(text.contains("rtp_midi_enabled = true"));
        assert!(text.contains("rtp_midi_host = \"192.168.1.10\""));
        assert!(text.contains("rtp_midi_channel = 5"));
        assert!(text.contains("[[mapping]]"));

        // 再読込で復元できる
        let parsed: Config = toml::from_str(&text).unwrap();
        assert_eq!(parsed.rtp_midi_host.as_deref(), Some("192.168.1.10"));
        assert_eq!(parsed.rtp_midi_channel, Some(5));
        assert!(parsed.kana_mode);
    }

    #[test]
    fn parses_mobile_exported_toml() {
        // Android 版が書き出す形式 (kana_mode / rtp_midi_* / [[kana_mapping]])
        let text = r#"
hand_split = 60
require_both_hands = false
kana_mode = true
rtp_midi_enabled = false
rtp_midi_port = 5004
rtp_midi_name = "Pianotype Android"

[[mapping]]
keys = [33]
value = "TOGGLE"

[[kana_mapping]]
keys = [48, 52, 55, 60]
value = "あ"
"#;
        let cfg: Config = toml::from_str(text).unwrap();
        assert_eq!(cfg.mappings.len(), 1);
        assert_eq!(cfg.kana_mappings.len(), 1);
        assert_eq!(cfg.kana_mappings[0].value, "あ");
        assert!(cfg.kana_mode);
        // 再保存しても元のキーが保持される
        let out = toml::to_string_pretty(&cfg).unwrap();
        assert!(out.contains("[[kana_mapping]]"));
        assert!(out.contains("rtp_midi_name = \"Pianotype Android\""));
    }
}
