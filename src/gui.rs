use crate::config::{Config, Mapping};
use crate::engine::{Action, MappingEngine};
use crate::keymap::send_command;
use crate::midi::{MidiEvent, MidiListener};
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
}

impl PianotypeApp {
    pub fn new(_cc: &eframe::CreationContext<'_>) -> Self {
        let config_path = Config::default_path();
        let config = Config::load_or_default(&config_path);
        let (tx, rx) = crossbeam_channel::unbounded();
        let devices = MidiListener::list_devices();
        let selected_device = config
            .midi_device
            .clone()
            .or_else(|| devices.first().cloned())
            .unwrap_or_default();

        Self {
            config,
            engine: MappingEngine::new(),
            midi: MidiListener::new(),
            rx,
            tx,
            enigo: Enigo::new(),
            status: "Ready".into(),
            devices,
            selected_device,
            config_path,
        }
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

    fn process_midi(&mut self, ctx: &egui::Context) {
        let mut dirty = false;
        while let Ok(ev) = self.rx.try_recv() {
            dirty = true;
            match ev {
                MidiEvent::NoteOn(note) => {
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
                        Action::SendKey(value, shifted) => {
                            send_command(&mut self.enigo, &value, shifted);
                            self.status =
                                format!("Sent: {value}{}", if shifted { " (shift)" } else { "" });
                        }
                        Action::Ignored => {}
                    }
                }
                MidiEvent::NoteOff(note) => {
                    self.engine.on_note_off(note);
                }
            }
        }
        if dirty {
            ctx.request_repaint();
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
                ui.separator();
                ui.label(format!(
                    "MIDI: {}",
                    if self.midi.is_running() {
                        "connected"
                    } else {
                        "off"
                    }
                ));
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
