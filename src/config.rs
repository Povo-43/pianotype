use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const DEFAULT_HAND_SPLIT: u8 = 60;

fn default_hand_split() -> u8 {
    DEFAULT_HAND_SPLIT
}
fn default_both_hands() -> bool {
    true
}

/// PianoType の設定。`pianotype_mobile` と同一スキーマを維持する。
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
    #[serde(default, rename = "mapping")]
    pub mappings: Vec<Mapping>,
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
            mappings: Vec::new(),
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
