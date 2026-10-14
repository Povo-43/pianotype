use anyhow::{anyhow, Result};
use crossbeam_channel::Sender;
use midir::{MidiInput, MidiInputConnection};

#[derive(Debug, Clone, Copy)]
pub enum MidiEvent {
    NoteOn(u8),
    NoteOff(u8),
}

pub struct MidiListener {
    conn: Option<MidiInputConnection<()>>,
    pub device_name: Option<String>,
}

impl MidiListener {
    pub fn new() -> Self {
        Self {
            conn: None,
            device_name: None,
        }
    }

    pub fn list_devices() -> Vec<String> {
        let input = match MidiInput::new("pianotype_pc_probe") {
            Ok(i) => i,
            Err(_) => return Vec::new(),
        };
        input
            .ports()
            .iter()
            .filter_map(|p| input.port_name(p).ok())
            .collect()
    }

    pub fn start(
        &mut self,
        device_name: Option<&str>,
        channel_filter: Option<u8>,
        tx: Sender<MidiEvent>,
    ) -> Result<()> {
        self.stop();
        let mut input = MidiInput::new("pianotype_pc")?;
        input.ignore(midir::Ignore::None);
        let ports = input.ports();
        if ports.is_empty() {
            return Err(anyhow!("no MIDI input ports available"));
        }
        let port = if let Some(name) = device_name {
            ports
                .iter()
                .find(|p| input.port_name(p).map(|n| n == name).unwrap_or(false))
                .ok_or_else(|| anyhow!("MIDI device not found: {name}"))?
        } else {
            &ports[0]
        };
        let name_clone = input
            .port_name(port)
            .unwrap_or_else(|_| "unknown".to_string());

        let conn = input.connect(
            port,
            "pianotype-input",
            move |_ts, message, _| {
                if message.len() < 3 {
                    return;
                }
                let status = message[0] & 0xF0;
                let channel = (message[0] & 0x0F) + 1;
                let note = message[1];
                let velocity = message[2];

                if let Some(filter) = channel_filter {
                    if channel != filter {
                        return;
                    }
                }
                match status {
                    0x90 if velocity > 0 => {
                        let _ = tx.send(MidiEvent::NoteOn(note));
                    }
                    0x80 | 0x90 => {
                        let _ = tx.send(MidiEvent::NoteOff(note));
                    }
                    _ => {}
                }
            },
            (),
        )?;

        self.conn = Some(conn);
        self.device_name = Some(name_clone);
        Ok(())
    }

    pub fn stop(&mut self) {
        self.conn = None;
        self.device_name = None;
    }

    pub fn is_running(&self) -> bool {
        self.conn.is_some()
    }
}
