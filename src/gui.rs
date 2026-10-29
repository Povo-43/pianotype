use crate::config::{Config, Mapping};
use crate::default_config::default_config;
use crate::engine::{Action, MappingEngine};
use crate::keymap::{send_command, send_ime_kana_toggle};
use crate::midi::{MidiEvent, MidiListener};
use crate::rtpmidi::{RtpMidiService, RtpStatus};
use crossbeam_channel::Receiver;
use eframe::egui;
use enigo::Enigo;

pub struct PianotypeApp {
    config: Config,
    engine: MappingEngine,
    midi: MidiListener,
    rx: Receiver<MidiEvent>,
    tx: crossbeam_channel::Sender<MidiEvent>,
    enigo: Enigo,
    status: String,
    devices: Vec<String>,
    selected_device: String,
    config_path: std::path::PathBuf,
    /// RTP MIDI サービス (None = 停止中)
    rtp: Option<RtpMidiService>,
    /// RTP MIDI 受信イベント用チャンネル
    rtp_rx: Option<Receiver<MidiEvent>>,
    /// UI 編集用バッファ (適用時に config へ反映)
    rtp_host_buf: String,
    rtp_name_buf: String,
    rtp_channel_buf: i32,
    last_rtp_status: Option<RtpStatus>,
}

impl PianotypeApp {
    pub fn new(_cc: &eframe::CreationContext<'_>) -> Self {
        let config_path = Config::default_path();
        // 初回起動時は内蔵の既定設定 (Android 版と同じ配列) を書き出す
        let config = if config_path.exists() {
            Config::load_or_default(&config_path)
        } else {
            let def = default_config();
            if let Err(e) = def.save(&config_path) {
                log::warn!("failed to write default config: {e}");
            }
            def
        };

        let (tx, rx) = crossbeam_channel::unbounded();
        let (_rtp_tx, rtp_rx) = crossbeam_channel::unbounded();
        let devices = MidiListener::list_devices();
        let selected_device = config
            .midi_device
            .clone()
            .or_else(|| devices.first().cloned())
            .unwrap_or_default();

        let app = Self {
            config,
            engine: MappingEngine::new(),
            midi: MidiListener::new(),
            rx,
            tx,
            enigo: match Enigo::new(&enigo::Settings::default()) {
                Ok(e) => e,
                Err(e) => {
                    log::error!("Enigo init failed: {e}");
                    panic!("Enigo init failed: {e}");
                }
            },
            status: "Ready".into(),
            devices,
            selected_device,
            config_path,
            rtp: None,
            rtp_rx: Some(rtp_rx),
            rtp_host_buf: String::new(),
            rtp_name_buf: "Pianotype PC".into(),
            rtp_channel_buf: 0,
            last_rtp_status: None,
        };
        app
    }

    fn refresh_devices(&mut self) {
        self.devices = MidiListener::list_devices();
        if !self.devices.iter().any(|d| d == &self.selected_device) {
            self.selected_device = self.devices.first().cloned().unwrap_or_default();
        }
    }

    fn connect(&mut self) {
        let device = if self.selected_device.is_empty() {
            None
        } else {
            Some(self.selected_device.as_str())
        };
        self.config.midi_device = device.map(str::to_string);
        match self
            .midi
            .start(device, self.config.midi_channel, self.tx.clone())
        {
            Ok(()) => {
                self.status = format!("Connected: {:?}", self.midi.device_name);
                let _ = self.config.save(&self.config_path);
            }
            Err(e) => self.status = format!("MIDI error: {e}"),
        }
    }

    fn disconnect(&mut self) {
        self.midi.stop();
        self.status = "Disconnected".into();
    }

    /// RTP MIDI サービスを設定どおり (再) 起動する。
    fn restart_rtp(&mut self) {
        // 既存サービスを停止 (Drop で BY 送出 + スレッド停止)
        self.rtp = None;
        self.last_rtp_status = None;

        if !self.config.rtp_midi_enabled {
            self.status = "RTP MIDI disabled".into();
            return;
        }
        let (rtp_tx, rtp_rx) = crossbeam_channel::unbounded();
        match RtpMidiService::start(
            self.config.rtp_midi_host.as_deref(),
            self.config.rtp_midi_port,
            &self.config.rtp_midi_name,
            self.config.rtp_midi_channel,
            rtp_tx,
        ) {
            Ok(svc) => {
                self.status = match &self.config.rtp_midi_host {
                    Some(h) => format!("RTP MIDI started (connecting to {h})"),
                    None => format!(
                        "RTP MIDI listening on {} (+{})",
                        self.config.rtp_midi_port,
                        self.config.rtp_midi_port + 1
                    ),
                };
                self.rtp = Some(svc);
                self.rtp_rx = Some(rtp_rx);
            }
            Err(e) => {
                self.status = format!("RTP MIDI error: {e}");
                log::error!("{e}");
            }
        }
    }

    fn process_midi(&mut self, ctx: &egui::Context) {
        let mut dirty = false;
        while let Ok(ev) = self.rx.try_recv() {
            dirty = true;
            // ローカル MIDI 入力を RTP で転送 (sender)
            if let Some(rtp) = &self.rtp {
                match ev {
                    MidiEvent::NoteOn {
                        channel,
                        note,
                        velocity,
                    } => rtp.send_note_on(channel, note, velocity),
                    MidiEvent::NoteOff { channel, note } => rtp.send_note_off(channel, note),
                }
            }
            self.handle_midi_event(ev);
        }

        // リモートから受信したノート (receiver)
        let remote_events: Vec<MidiEvent> = self
            .rtp_rx
            .as_ref()
            .map(|rx| {
                let mut v = Vec::new();
                while let Ok(ev) = rx.try_recv() {
                    v.push(ev);
                }
                v
            })
            .unwrap_or_default();
        for ev in remote_events {
            dirty = true;
            self.handle_midi_event(ev);
        }

        if let Some(rtp) = &self.rtp {
            self.last_rtp_status = Some(rtp.status());
        }
        if dirty {
            ctx.request_repaint();
        }
    }

    fn handle_midi_event(&mut self, ev: MidiEvent) {
        let note = ev.note();
        match ev {
            MidiEvent::NoteOn { .. } => {
                let action = self.engine.on_note_on(note, &self.config);
                match action {
                    Action::Toggle(on) => {
                        self.status = if on {
                            "Enabled".into()
                        } else {
                            "Disabled".into()
                        };
                    }
                    Action::ShiftArmed => {
                        self.status = "Shift armed".into();
                    }
                    Action::KanaIme => {
                        send_ime_kana_toggle(&mut self.enigo);
                        self.status = "Sent: IME kana toggle".into();
                    }
                    Action::SendKey(value, shifted) => {
                        send_command(&mut self.enigo, &value, shifted);
                        self.status =
                            format!("Sent: {value}{}", if shifted { " (shift)" } else { "" });
                    }
                    Action::Ignored => {}
                }
            }
            MidiEvent::NoteOff { .. } => {
                self.engine.on_note_off(note);
            }
        }
    }
}

impl eframe::App for PianotypeApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.process_midi(ctx);

        egui::TopBottomPanel::top("top").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.heading("PianoType PC");
                ui.separator();
                let enabled = self.engine.is_enabled();
                let color = if enabled {
                    egui::Color32::from_rgb(60, 200, 100)
                } else {
                    egui::Color32::GRAY
                };
                ui.colored_label(color, if enabled { "ENABLED" } else { "DISABLED" });
                if self.engine.is_shift_armed() {
                    ui.colored_label(
                        egui::Color32::from_rgb(255, 200, 80),
                        "SHIFT",
                    );
                }
                ui.separator();
                let held = self.engine.held_notes();
                if !held.is_empty() {
                    ui.label(format!(
                        "notes: {}",
                        held.iter()
                            .map(|n| n.to_string())
                            .collect::<Vec<_>>()
                            .join(",")
                    ));
                    ui.separator();
                }
                ui.label(format!(
                    "MIDI: {}",
                    if self.midi.is_running() {
                        "connected"
                    } else {
                        "off"
                    }
                ));
                ui.separator();
                let rtp_on = self.rtp.is_some();
                let rtp_peers = self
                    .last_rtp_status
                    .as_ref()
                    .map(|s| s.peers.len())
                    .unwrap_or(0);
                ui.colored_label(
                    if rtp_on {
                        egui::Color32::from_rgb(90, 170, 255)
                    } else {
                        egui::Color32::GRAY
                    },
                    format!(
                        "RTP MIDI: {}{}",
                        if rtp_on { "on" } else { "off" },
                        if rtp_on {
                            format!(" ({rtp_peers} peers)")
                        } else {
                            String::new()
                        }
                    ),
                );
            });
        });

        egui::TopBottomPanel::bottom("bottom").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.label("Status:");
                ui.label(&self.status);
            });
        });

        egui::CentralPanel::default().show(ctx, |ui| {
            egui::ScrollArea::vertical().show(ui, |ui| {
                ui.group(|ui| {
                    ui.heading("MIDI");
                    ui.horizontal(|ui| {
                        if ui.button("Refresh").clicked() {
                            self.refresh_devices();
                        }
                        egui::ComboBox::from_label("Device")
                            .selected_text(if self.selected_device.is_empty() {
                                "(none)".to_string()
                            } else {
                                self.selected_device.clone()
                            })
                            .show_ui(ui, |ui| {
                                for d in &self.devices {
                                    ui.selectable_value(&mut self.selected_device, d.clone(), d);
                                }
                            });
                    });
                    ui.horizontal(|ui| {
                        if ui.button("Connect").clicked() {
                            self.connect();
                        }
                        if ui.button("Disconnect").clicked() {
                            self.disconnect();
                        }
                    });
                    let mut ch = self.config.midi_channel.unwrap_or(0) as i32;
                    if ui
                        .add(egui::Slider::new(&mut ch, 0..=16).text("Channel (0=all)"))
                        .changed()
                    {
                        self.config.midi_channel = if ch == 0 { None } else { Some(ch as u8) };
                    }
                });

                ui.add_space(8.0);

                ui.group(|ui| {
                    ui.heading("RTP MIDI (AppleMIDI)");
                    ui.label("Send local notes to / receive notes from the network.");
                    ui.horizontal(|ui| {
                        ui.checkbox(&mut self.config.rtp_midi_enabled, "Enabled");
                        ui.label("Host:");
                        let host_resp = ui.add(
                            egui::TextEdit::singleline(&mut self.rtp_host_buf)
                                .hint_text("(listen only)")
                                .desired_width(150.0),
                        );
                        host_resp.on_hover_text(
                            "Connect target, e.g. 192.168.1.10 or 192.168.1.10:5004.\n\
                             Empty = listen only (accept incoming INVITEs).",
                        );
                    });
                    ui.horizontal(|ui| {
                        ui.label("Name:");
                        ui.add(
                            egui::TextEdit::singleline(&mut self.rtp_name_buf)
                                .desired_width(120.0),
                        );
                        ui.label("Channel:");
                        ui.add(egui::DragValue::new(&mut self.rtp_channel_buf).clamp_range(0..=16));
                        ui.label("(0 = keep original)");
                    });
                    let mut port = self.config.rtp_midi_port as i32;
                    ui.add(egui::Slider::new(&mut port, 1024..=65530).text("Port (data +1)"));
                    self.config.rtp_midi_port = port as u16;

                    ui.horizontal(|ui| {
                        if ui.button("Apply / Restart").clicked() {
                            let host = self.rtp_host_buf.trim().to_string();
                            self.config.rtp_midi_host =
                                if host.is_empty() { None } else { Some(host) };
                            let name = self.rtp_name_buf.trim().to_string();
                            if !name.is_empty() {
                                self.config.rtp_midi_name = name;
                            }
                            self.config.rtp_midi_channel =
                                if self.rtp_channel_buf == 0 {
                                    None
                                } else {
                                    Some(self.rtp_channel_buf as u8)
                                };
                            match self.config.save(&self.config_path) {
                                Ok(()) => self.status = "Saved".into(),
                                Err(e) => self.status = format!("Save error: {e}"),
                            }
                            self.restart_rtp();
                        }
                    });

                    if let Some(st) = &self.last_rtp_status {
                        ui.separator();
                        if st.connected() {
                            ui.colored_label(egui::Color32::from_rgb(90, 200, 120), "connected");
                            for p in &st.peers {
                                ui.label(format!("  • {p}"));
                            }
                        } else if st.inviting {
                            ui.colored_label(
                                egui::Color32::from_rgb(255, 200, 80),
                                "inviting… (waiting for OK)",
                            );
                        } else {
                            ui.label("no peers (listening)");
                        }
                        ui.label(format!("sent {} / received {}", st.sent, st.received));
                        if let Some(err) = &st.last_error {
                            ui.colored_label(egui::Color32::from_rgb(255, 100, 100), err);
                        }
                    }
                });

                ui.add_space(8.0);

                ui.group(|ui| {
                    ui.heading("Behavior");
                    ui.add(
                        egui::Slider::new(&mut self.config.hand_split, 0..=127).text("Hand split"),
                    );
                    ui.checkbox(&mut self.config.require_both_hands, "Require both hands");
                });

                ui.add_space(8.0);

                ui.group(|ui| {
                    ui.heading("Mappings");
                    ui.label("keys: MIDI note numbers, comma separated (e.g. 60,64,67)");
                    let mut remove_idx: Option<usize> = None;
                    for (i, m) in self.config.mappings.iter_mut().enumerate() {
                        ui.horizontal(|ui| {
                            let mut keys_str = m
                                .keys
                                .iter()
                                .map(|k| k.to_string())
                                .collect::<Vec<_>>()
                                .join(",");
                            if ui
                                .add(egui::TextEdit::singleline(&mut keys_str).desired_width(120.0))
                                .changed()
                            {
                                m.keys = keys_str
                                    .split(',')
                                    .filter_map(|s| s.trim().parse::<u8>().ok())
                                    .collect();
                            }
                            ui.label("→");
                            ui.add(egui::TextEdit::singleline(&mut m.value).desired_width(140.0));
                            if ui.button("✖").clicked() {
                                remove_idx = Some(i);
                            }
                        });
                    }
                    if let Some(i) = remove_idx {
                        self.config.mappings.remove(i);
                    }
                    if ui.button("＋ Add mapping").clicked() {
                        self.config.mappings.push(Mapping {
                            keys: vec![60],
                            value: "ENTER".into(),
                        });
                    }
                });

                ui.add_space(8.0);

                ui.group(|ui| {
                    ui.heading("Config file");
                    ui.label(format!("Path: {}", self.config_path.display()));
                    ui.horizontal(|ui| {
                        if ui.button("Load").clicked() {
                            self.config = Config::load_or_default(&self.config_path);
                            self.rtp_host_buf = self.config.rtp_midi_host.clone().unwrap_or_default();
                            self.rtp_name_buf = self.config.rtp_midi_name.clone();
                            self.rtp_channel_buf = self.config.rtp_midi_channel.unwrap_or(0) as i32;
                            self.status = "Loaded".into();
                        }
                        if ui.button("Save").clicked() {
                            match self.config.save(&self.config_path) {
                                Ok(()) => self.status = "Saved".into(),
                                Err(e) => self.status = format!("Save error: {e}"),
                            }
                        }
                        if ui.button("Import…").clicked() {
                            if let Some(path) = rfd::FileDialog::new()
                                .add_filter("TOML", &["toml"])
                                .pick_file()
                            {
                                match Config::load(&path) {
                                    Ok(c) => {
                                        self.config = c;
                                        self.rtp_host_buf =
                                            self.config.rtp_midi_host.clone().unwrap_or_default();
                                        self.rtp_name_buf = self.config.rtp_midi_name.clone();
                                        self.rtp_channel_buf =
                                            self.config.rtp_midi_channel.unwrap_or(0) as i32;
                                        self.status = "Imported".into();
                                    }
                                    Err(e) => self.status = format!("Import error: {e}"),
                                }
                            }
                        }
                        if ui.button("Export…").clicked() {
                            if let Some(path) = rfd::FileDialog::new()
                                .add_filter("TOML", &["toml"])
                                .set_file_name("pianotype.toml")
                                .save_file()
                            {
                                match self.config.save(&path) {
                                    Ok(()) => self.status = "Exported".into(),
                                    Err(e) => self.status = format!("Export error: {e}"),
                                }
                            }
                        }
                    });
                    ui.small(
                        "kana_mode / [[kana_mapping]] / rtp_midi_* are kept for \
                         pianotype_mobile compatibility.",
                    );
                });

                ui.add_space(8.0);
                if ui.button("Reset engine state").clicked() {
                    self.engine.reset();
                    self.status = "Engine reset".into();
                }
            });
        });
    }
}

impl Drop for PianotypeApp {
    fn drop(&mut self) {
        // RTP サービスを確実に停止する
        self.rtp = None;
    }
}
