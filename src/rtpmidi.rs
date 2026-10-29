//! RTP MIDI (AppleMIDI / RFC 6295) の送受信。
//!
//! - **Sender**: ローカルの MIDI 入力 (NoteOn/NoteOff) を
//!   INVITE で接続したピアへ RTP MIDI として転送する。
//! - **Receiver**: リモート (Android 版 Pianotype や rtpMIDI 対応 DAW 等) からの
//!   INVITE を受け入れ、受信したノートをローカルのエンジンへ流す。
//!
//! AppleMIDI セッションプロトコル (リバースエンジニアリング仕様に基づく):
//!
//! ```text
//! Invitation (IN/OK/NO/BY):
//!   | FF FF | cmd(2) | version(4)=2 BE | initiator(4) | ssrc(4) | name(NUL 末尾) |
//! Synchronization (CK):
//!   | FF FF | "CK" | ssrc(4) | count(1) | pad(3) | timestamps(8*3) |  (100us 単位)
//! ```
//!
//! * セッション管理は control ポート (`rtp_midi_port`, 既定 5004)、
//!   MIDI データは data ポート (+1)。
//! * 接続確立は 2 段階: control で INVITE/OK → data で INVITE/OK → Connected。
//! * クロック同期 (CK) は同期開始側が CK0 → 相手 CK1 → CK0送信側 CK2。
//!
//! 外部クレートに依存せず `std::net::UdpSocket` + スレッドで実装している。

use anyhow::{anyhow, Result};
use crossbeam_channel::Sender;
use std::collections::HashMap;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU16, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crate::midi::MidiEvent;

const RTP_MIDI_PT: u8 = 97; // dynamic payload type for MIDI

/// セッション管理パケットのマジックシグネチャ
const APPLE_SIGNATURE: [u8; 2] = [0xFF, 0xFF];
/// プロトコルバージョン 2 (network byte order)
const APPLE_VERSION: [u8; 4] = [0x00, 0x00, 0x00, 0x02];

/// コマンド ("IN", "OK", "NO", "BY", "CK")
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AppleCmd {
    Invite,
    Accept,
    Reject,
    End,
    ClockSync,
}

impl AppleCmd {
    fn bytes(self) -> [u8; 2] {
        match self {
            AppleCmd::Invite => *b"IN",
            AppleCmd::Accept => *b"OK",
            AppleCmd::Reject => *b"NO",
            AppleCmd::End => *b"BY",
            AppleCmd::ClockSync => *b"CK",
        }
    }

    fn from_bytes(b: &[u8]) -> Option<Self> {
        match b {
            b"IN" => Some(AppleCmd::Invite),
            b"OK" => Some(AppleCmd::Accept),
            b"NO" => Some(AppleCmd::Reject),
            b"BY" => Some(AppleCmd::End),
            b"CK" => Some(AppleCmd::ClockSync),
            _ => None,
        }
    }
}

/// AppleMIDI セッションパケットの解析結果
#[derive(Debug, Clone, PartialEq)]
pub enum AppleMidiPacket {
    /// IN / OK / NO / BY
    Invitation {
        cmd: AppleCmd,
        initiator: u32,
        ssrc: u32,
        name: String,
    },
    /// CK (タイムスタンプは 100µs 単位の u64)
    ClockSync {
        ssrc: u32,
        count: u8,
        /// 常に 3 スロット (未使用は 0)
        timestamps: [u64; 3],
    },
}

/// セッション管理パケット (IN/OK/NO/BY) を構築する。
pub fn build_applemidi_packet(cmd: AppleCmd, initiator: u32, ssrc: u32, name: &str) -> Vec<u8> {
    debug_assert_ne!(cmd, AppleCmd::ClockSync);
    let mut out = Vec::with_capacity(16 + name.len() + 1);
    out.extend_from_slice(&APPLE_SIGNATURE);
    out.extend_from_slice(&cmd.bytes());
    out.extend_from_slice(&APPLE_VERSION);
    out.extend_from_slice(&initiator.to_be_bytes());
    out.extend_from_slice(&ssrc.to_be_bytes());
    out.extend_from_slice(name.as_bytes());
    out.push(0); // NUL 終端
    out
}

/// CK パケットを構築する。`timestamps` は 3 スロット。
pub fn build_ck_packet(ssrc: u32, count: u8, timestamps: [u64; 3]) -> Vec<u8> {
    let mut out = Vec::with_capacity(36);
    out.extend_from_slice(&APPLE_SIGNATURE);
    out.extend_from_slice(AppleCmd::ClockSync.bytes().as_slice());
    out.extend_from_slice(&ssrc.to_be_bytes());
    out.push(count.min(2));
    out.extend_from_slice(&[0, 0, 0]); // padding
    for t in timestamps {
        out.extend_from_slice(&t.to_be_bytes());
    }
    out
}

/// 受信した AppleMIDI パケットを解析する。
pub fn parse_applemidi(buf: &[u8]) -> Option<AppleMidiPacket> {
    if buf.len() < 8 {
        return None;
    }
    if buf[0..2] != APPLE_SIGNATURE {
        return None;
    }
    let cmd = AppleCmd::from_bytes(&buf[2..4])?;
    match cmd {
        AppleCmd::ClockSync => {
            if buf.len() < 12 {
                return None;
            }
            let ssrc = u32::from_be_bytes(buf[4..8].try_into().ok()?);
            let count = buf[8];
            if count > 2 {
                return None;
            }
            let mut timestamps = [0u64; 3];
            for (i, t) in timestamps.iter_mut().enumerate() {
                let o = 12 + i * 8;
                if buf.len() >= o + 8 {
                    *t = u64::from_be_bytes(buf[o..o + 8].try_into().ok()?);
                } else {
                    break;
                }
            }
            Some(AppleMidiPacket::ClockSync {
                ssrc,
                count,
                timestamps,
            })
        }
        cmd => {
            if buf.len() < 16 {
                return None;
            }
            if buf[4..8] != APPLE_VERSION {
                return None;
            }
            let initiator = u32::from_be_bytes(buf[8..12].try_into().ok()?);
            let ssrc = u32::from_be_bytes(buf[12..16].try_into().ok()?);
            let name_bytes = &buf[16..];
            let name_end = name_bytes
                .iter()
                .position(|&b| b == 0)
                .unwrap_or(name_bytes.len());
            let name = String::from_utf8_lossy(&name_bytes[..name_end]).to_string();
            Some(AppleMidiPacket::Invitation {
                cmd,
                initiator,
                ssrc,
                name,
            })
        }
    }
}

/// CK パケットへの応答を構築する。
///
/// * `count == 0` → `count = 1` で返信: `[相手 ts0, 自分の now, 0]`
/// * `count == 1` → `count = 2` で返信: `[相手 ts0, 相手 ts1, 自分の now]`
/// * `count == 2` → 同期完了、応答なし
pub fn build_ck_response(
    my_ssrc: u32,
    packet: &AppleMidiPacket,
    now: u64,
) -> Option<Vec<u8>> {
    let AppleMidiPacket::ClockSync {
        count, timestamps, ..
    } = packet
    else {
        return None;
    };
    let (count, stamps): (u8, [u64; 3]) = match (count, *timestamps) {
        (0, [ts0, _, _]) => (1, [ts0, now, 0]),
        (1, [ts0, ts1, _]) => (2, [ts0, ts1, now]),
        _ => return None,
    };
    Some(build_ck_packet(my_ssrc, count, stamps))
}

/// RTP MIDI パケットを構築する (ジャーナルなし、Z=0)。
pub fn build_rtp_midi(ssrc: u32, seq: u16, timestamp: u32, midi: &[u8]) -> Vec<u8> {
    let mut pkt = Vec::with_capacity(13 + midi.len());
    pkt.push(0x80); // V=2, P=0, X=0, CC=0
    pkt.push(0x80 | RTP_MIDI_PT); // M=1 (MIDI section 非空), PT=97
    pkt.extend_from_slice(&seq.to_be_bytes());
    pkt.extend_from_slice(&timestamp.to_be_bytes());
    pkt.extend_from_slice(&ssrc.to_be_bytes());
    debug_assert!(midi.len() < 15, "long MIDI sections need extended length");
    pkt.push(midi.len().min(15) as u8); // MIDI header: J=0 Z=0 P=0, len<15
    pkt.extend_from_slice(midi);
    pkt
}

/// RTP パケットから MIDI コマンドセクションを抽出する。
pub fn parse_rtp_midi(buf: &[u8]) -> Option<&[u8]> {
    if buf.len() < 13 {
        return None;
    }
    if buf[0] & 0xC0 != 0x80 {
        return None; // V != 2
    }
    if buf[1] & 0x7F != RTP_MIDI_PT {
        return None;
    }
    let cc = (buf[0] & 0x0F) as usize;
    let mut header_len = 12 + cc * 4;
    if buf[0] & 0x10 != 0 {
        // 拡張ヘッダー (X=1): 4 バイトヘッダー + 可変長をスキップ
        if buf.len() < header_len + 4 {
            return None;
        }
        let ext_len = u16::from_be_bytes([buf[header_len + 2], buf[header_len + 3]]) as usize;
        header_len += 4 + ext_len;
    }
    if buf.len() <= header_len {
        return None;
    }
    Some(&buf[header_len..])
}

/// MIDI コマンドセクションから (status, note, velocity) のリストを抽出する。
///
/// NoteOn / NoteOff 以外のコマンドは読み飛ばす。running status と
/// コマンド間の delta time (VLQ) も処理する。
pub fn parse_midi_commands(section: &[u8]) -> Vec<(u8, u8, u8)> {
    if section.is_empty() {
        return Vec::new();
    }
    // ヘッダーバイト: B J Z P + len(4bit)
    let has_first_delta = section[0] & 0x20 != 0; // Z flag
    let len_low = (section[0] & 0x0F) as usize;
    let mut idx = 1usize;
    let section_len = if len_low < 15 {
        len_low
    } else {
        // 拡張長: B=1 → 続くオクテットの下位 7bit を加算
        if section.len() < 2 {
            return Vec::new();
        }
        let more = (section[1] & 0x7F) as usize;
        idx = 2;
        15 + more
    };
    let end = (idx + section_len).min(section.len());

    let mut out = Vec::new();
    let mut status: u8 = 0;
    let mut first = true;
    // 送信者が delta time を付けるか (None = 未確定、最初の候補で決定する)
    let mut delta_mode: Option<bool> = None;
    let mut i = idx;
    while i < end {
        // delta time (VLQ) をスキップ。最初のコマンドは Z=0 なら省略、
        // それ以外は常にある (RFC 6295)。ただし単純な送信実装は running
        // status 時に delta を省略することがあるため、読み飛ばした候補の
        // 次が status バイトでなければ delta ではなかったとして巻き戻し、
        // 以降は delta なしモードで処理する。
        let want_delta = match delta_mode {
            Some(has) => has,
            // 最初のコマンドは Z=0 なら delta を省略 (RFC 6295)
            None => !first || has_first_delta,
        };
        if want_delta {
            let save = i;
            while i < end {
                let b = section[i];
                i += 1;
                if b & 0x80 == 0 {
                    break;
                }
            }
            if i >= end {
                break;
            }
            if section[i] & 0x80 == 0 {
                // delta ではなかった (running status のデータ)
                delta_mode = Some(false);
                i = save;
            } else if delta_mode.is_none() {
                delta_mode = Some(true);
            }
        }
        first = false;
        if i >= end {
            break;
        }
        let b = section[i];
        if b & 0x80 != 0 {
            status = b;
            i += 1;
        }
        match status & 0xF0 {
            0x90 | 0x80 => {
                if i + 2 > end {
                    break;
                }
                let (note, vel) = (section[i], section[i + 1]);
                i += 2;
                let vel = if status & 0xF0 == 0x90 { vel } else { 0 };
                out.push((status, note, vel));
            }
            0xA0..=0xE0 => {
                // 2 データバイトのコマンド (CC, pitch bend など) はスキップ
                if status == 0 || i + 2 > end {
                    break;
                }
                i += 2;
            }
            _ => {
                // status 未確定 / system メッセージ: 読み飛ばす
                if status == 0 {
                    i += 1;
                }
            }
        }
    }
    out
}

#[derive(Debug, Clone)]
struct Peer {
    name: String,
    data_addr: SocketAddr,
    /// data チャネルの INVITE/OK でアドレスが確定済みか
    data_confirmed: bool,
    connected_at: Instant,
}

#[derive(Debug, Default)]
struct Shared {
    my_ssrc: AtomicU32,
    /// control チャネルの OK を受信済み (data INVITE へ進める)
    control_accepted: AtomicBool,
    peers: Mutex<HashMap<u32, Peer>>,
    sent: AtomicU64,
    received: AtomicU64,
    inviting: AtomicBool,
    last_error: Mutex<Option<String>>,
    stop: AtomicBool,
}

/// UI 表示用の状態スナップショット
#[derive(Debug, Clone, Default)]
pub struct RtpStatus {
    pub peers: Vec<String>,
    pub sent: u64,
    pub received: u64,
    pub inviting: bool,
    pub last_error: Option<String>,
}

impl RtpStatus {
    pub fn connected(&self) -> bool {
        !self.peers.is_empty()
    }
}

pub struct RtpMidiService {
    shared: Arc<Shared>,
    data_sock: Arc<UdpSocket>,
    my_ssrc: u32,
    my_name: String,
    channel_override: Option<u8>,
    seq: AtomicU16,
    start: Instant,
    handles: Mutex<Vec<JoinHandle<()>>>,
}

impl RtpMidiService {
    /// RTP MIDI サービスを開始する。
    ///
    /// * `host` — 接続先 (`host[:control_port]`)。None なら待ち受けのみ。
    ///   接続先の data ポートは control ポート + 1 とみなす (Apple の規約)。
    /// * `control_port` — 待ち受け control ポート (data は +1)。
    /// * `tx` — リモートから受信したノートイベントの転送先。
    pub fn start(
        host: Option<&str>,
        control_port: u16,
        name: &str,
        channel_override: Option<u8>,
        tx: Sender<MidiEvent>,
    ) -> Result<Self> {
        let data_port = control_port.saturating_add(1);
        let control_sock = Arc::new(
            UdpSocket::bind(("0.0.0.0", control_port))
                .map_err(|e| anyhow!("control port {control_port} bind failed: {e}"))?,
        );
        let data_sock = Arc::new(
            UdpSocket::bind(("0.0.0.0", data_port))
                .map_err(|e| anyhow!("data port {data_port} bind failed: {e}"))?,
        );

        let my_ssrc = random_u32();
        let shared = Arc::new(Shared {
            my_ssrc: AtomicU32::new(my_ssrc),
            ..Default::default()
        });
        let mut handles = Vec::new();

        // --- control ループ: INVITE/OK/BY (段階 1) ---
        {
            let sock = Arc::clone(&control_sock);
            let shared = Arc::clone(&shared);
            let my_ssrc = my_ssrc;
            handles.push(thread::Builder::new()
                .name("rtpmidi-control".into())
                .spawn(move || {
                    let mut buf = [0u8; 2048];
                    while !shared.stop.load(Ordering::Relaxed) {
                        sock.set_read_timeout(Some(Duration::from_millis(250))).ok();
                        let (n, src) = match sock.recv_from(&mut buf) {
                            Ok(x) => x,
                            Err(_) => continue,
                        };
                        let Some(packet) = parse_applemidi(&buf[..n]) else {
                            continue;
                        };
                        let reply =
                            handle_control_packet(packet, &shared, src, my_ssrc);
                        if let Some((reply, dest)) = reply {
                            let _ = sock.send_to(&reply, dest);
                        }
                    }
                })?);
        }

        // --- data ループ: INVITE/OK (段階 2) + RTP 受信 + CK 同期 ---
        {
            let sock = Arc::clone(&data_sock);
            let shared = Arc::clone(&shared);
            handles.push(thread::Builder::new()
                .name("rtpmidi-data".into())
                .spawn(move || {
                    let mut buf = [0u8; 4096];
                    while !shared.stop.load(Ordering::Relaxed) {
                        sock.set_read_timeout(Some(Duration::from_millis(250))).ok();
                        let (n, src) = match sock.recv_from(&mut buf) {
                            Ok(x) => x,
                            Err(_) => continue,
                        };
                        let data = &buf[..n];
                        let Some(packet) = parse_applemidi(data) else {
                            // RTP MIDI データ
                            let Some(section) = parse_rtp_midi(data) else {
                                continue;
                            };
                            shared.received.fetch_add(1, Ordering::Relaxed);
                            for (status, note, vel) in parse_midi_commands(section) {
                                let channel = (status & 0x0F) + 1;
                                let ev = if status & 0xF0 == 0x90 && vel > 0 {
                                    MidiEvent::NoteOn {
                                        channel,
                                        note,
                                        velocity: vel,
                                    }
                                } else {
                                    MidiEvent::NoteOff { channel, note }
                                };
                                let _ = tx.send(ev);
                            }
                            continue;
                        };
                        match packet {
                            AppleMidiPacket::Invitation {
                                cmd: AppleCmd::Invite,
                                initiator,
                                ssrc,
                                name,
                            } => {
                                // data チャネルの INVITE: 正確な data アドレスで更新
                                #[cfg(test)]
                                eprintln!("[B] got data INVITE from {src}");
                                log::info!("RTP MIDI data INVITE from {name:?} ({src})");
                                upsert_peer(&shared, ssrc, name, src, true);
                                let reply = build_applemidi_packet(
                                    AppleCmd::Accept,
                                    initiator,
                                    my_ssrc_of(&shared),
                                    "",
                                );
                                let _ = sock.send_to(&reply, src);
                            }
                            AppleMidiPacket::Invitation {
                                cmd: AppleCmd::Accept,
                                ssrc,
                                ..
                            } => {
                                // 自分が data INVITE を送った相手からの OK
                                #[cfg(test)]
                                eprintln!("[A] got data OK from {src}");
                                upsert_peer(&shared, ssrc, String::new(), src, true);
                                log::info!("RTP MIDI session established with {src}");
                                shared.inviting.store(false, Ordering::Relaxed);
                            }
                            AppleMidiPacket::Invitation {
                                cmd: AppleCmd::End,
                                ssrc,
                                ..
                            } => {
                                log::info!("RTP MIDI peer {src} ended session");
                                shared.peers.lock().unwrap().remove(&ssrc);
                            }
                            AppleMidiPacket::Invitation { .. } => {}
                            AppleMidiPacket::ClockSync { .. } => {
                                if let Some(reply) = build_ck_response(
                                    my_ssrc_of(&shared),
                                    &packet,
                                    now_10khz(),
                                ) {
                                    let _ = sock.send_to(&reply, src);
                                }
                            }
                        }
                    }
                })?);
        }

        // --- INVITE ループ: host への接続 (sender 用) ---
        if let Some(host) = host {
            let (host, port) = split_host_port(host, control_port);
            let control_ref = Arc::clone(&control_sock);
            let data_ref = Arc::clone(&data_sock);
            let shared = Arc::clone(&shared);
            let my_name = name.to_string();
            let dest_str = format!("{host}:{port}");
            handles.push(thread::Builder::new()
                .name("rtpmidi-invite".into())
                .spawn(move || {
                    let dest: SocketAddr = match dest_str.parse() {
                        Ok(d) => d,
                        Err(e) => {
                            *shared.last_error.lock().unwrap() =
                                Some(format!("invalid host: {dest_str} ({e})"));
                            return;
                        }
                    };
                    // 相手の data ポートは control + 1 (Apple の規約)
                    let data_dest =
                        SocketAddr::new(dest.ip(), dest.port().saturating_add(1));
                    while !shared.stop.load(Ordering::Relaxed) {
                        let established = shared
                            .peers
                            .lock()
                            .unwrap()
                            .values()
                            .any(|p| p.data_confirmed);
                        if established {
                            shared.inviting.store(false, Ordering::Relaxed);
                            thread::sleep(Duration::from_secs(2));
                            continue;
                        }
                        // control OK 受信済みなら data チャネルへ、未なら control へ
                        // (BY 受信時は control_accepted がリセットされる)
                        let control_ok = shared.control_accepted.load(Ordering::Relaxed);
                        shared.inviting.store(true, Ordering::Relaxed);
                        let initiator = random_u32();
                        let invite = build_applemidi_packet(
                            AppleCmd::Invite,
                            initiator,
                            my_ssrc_of(&shared),
                            &my_name,
                        );
                        if control_ok {
                            // 段階 2: data ポートへ INVITE (data ソケットから。
                            // OK を data ループで受け取るため)
                            #[cfg(test)]
                            eprintln!("[A] sending data INVITE to {data_dest}");
                            let _ = data_ref.send_to(&invite, data_dest);
                        } else {
                            // 段階 1: control ポートへ INVITE (control ソケットから)
                            #[cfg(test)]
                            eprintln!("[A] sending control INVITE to {dest}");
                            let _ = control_ref.send_to(&invite, dest);
                        }
                        thread::sleep(Duration::from_millis(1200));
                    }
                })?);
        }

        Ok(Self {
            shared,
            data_sock,
            my_ssrc,
            my_name: name.to_string(),
            channel_override,
            seq: AtomicU16::new(random_u32() as u16),
            start: Instant::now(),
            handles: Mutex::new(handles),
        })
    }

    /// 現在の状態を返す。
    pub fn status(&self) -> RtpStatus {
        RtpStatus {
            peers: self
                .shared
                .peers
                .lock()
                .unwrap()
                .values()
                .map(|p| {
                    if p.name.is_empty() {
                        format!("peer @ {}", p.data_addr)
                    } else {
                        format!("{} ({})", p.name, p.data_addr)
                    }
                })
                .collect(),
            sent: self.shared.sent.load(Ordering::Relaxed),
            received: self.shared.received.load(Ordering::Relaxed),
            inviting: self.shared.inviting.load(Ordering::Relaxed),
            last_error: self.shared.last_error.lock().unwrap().clone(),
        }
    }

    /// 接続済みピアへ NoteOn を送信する。`channel` は 1..16。
    pub fn send_note_on(&self, channel: u8, note: u8, velocity: u8) {
        let ch = self.effective_channel(channel);
        self.send_to_peers(&[0x90 | ch, note, velocity]);
    }

    /// 接続済みピアへ NoteOff を送信する。`channel` は 1..16。
    pub fn send_note_off(&self, channel: u8, note: u8) {
        let ch = self.effective_channel(channel);
        self.send_to_peers(&[0x80 | ch, note, 0x40]);
    }

    fn effective_channel(&self, channel: u8) -> u8 {
        self.channel_override
            .unwrap_or(channel)
            .saturating_sub(1)
            & 0x0F
    }

    fn send_to_peers(&self, midi: &[u8]) {
        let peers = self.shared.peers.lock().unwrap();
        if peers.is_empty() {
            return;
        }
        let seq = self.seq.fetch_add(1, Ordering::Relaxed);
        let ts = now_10khz_since(self.start) as u32;
        let pkt = build_rtp_midi(self.my_ssrc, seq, ts, midi);
        let mut n = 0u64;
        for peer in peers.values() {
            if self.data_sock.send_to(&pkt, peer.data_addr).is_ok() {
                n += 1;
            }
        }
        self.shared.sent.fetch_add(n, Ordering::Relaxed);
    }
}

impl Drop for RtpMidiService {
    fn drop(&mut self) {
        // BY (セッション終了) を各ピアへ送る
        {
            let peers = self.shared.peers.lock().unwrap();
            let by = build_applemidi_packet(AppleCmd::End, 0, self.my_ssrc, &self.my_name);
            for peer in peers.values() {
                let _ = self.data_sock.send_to(&by, peer.data_addr);
            }
        }
        self.shared.stop.store(true, Ordering::Relaxed);
        if let Ok(mut handles) = self.handles.lock() {
            for h in handles.drain(..) {
                let _ = h.join();
            }
        }
    }
}

fn my_ssrc_of(shared: &Shared) -> u32 {
    shared.my_ssrc.load(Ordering::Relaxed)
}

fn upsert_peer(
    shared: &Shared,
    ssrc: u32,
    name: String,
    data_addr: SocketAddr,
    data_confirmed: bool,
) {
    let mut peers = shared.peers.lock().unwrap();
    match peers.get_mut(&ssrc) {
        Some(peer) => {
            peer.data_addr = data_addr;
            peer.data_confirmed = data_confirmed;
            peer.connected_at = Instant::now();
            if !name.is_empty() {
                peer.name = name;
            }
        }
        None => {
            peers.insert(
                ssrc,
                Peer {
                    name,
                    data_addr,
                    data_confirmed,
                    connected_at: Instant::now(),
                },
            );
        }
    }
}

/// control ポートのパケットを処理し、(応答パケット, 送信先) を返す。
fn handle_control_packet(
    packet: AppleMidiPacket,
    shared: &Shared,
    src: SocketAddr,
    my_ssrc: u32,
) -> Option<(Vec<u8>, SocketAddr)> {
    let AppleMidiPacket::Invitation {
        cmd,
        initiator,
        ssrc,
        name,
    } = packet
    else {
        return None;
    };
    match cmd {
        AppleCmd::Invite => {
            // 相手の data ポートは control + 1 と仮定 (data INVITE で確定する)
            let data_addr = SocketAddr::new(src.ip(), src.port().saturating_add(1));
            log::info!("RTP MIDI INVITE from {name:?} ({src})");
            upsert_peer(shared, ssrc, name, data_addr, false);
            let reply = build_applemidi_packet(AppleCmd::Accept, initiator, my_ssrc, "");
            Some((reply, src))
        }
        AppleCmd::Accept => {
            // control 段階 OK → invite ループが data ポートへ INVITE を送る
            log::info!("RTP MIDI control INVITE accepted by {src}");
            shared.control_accepted.store(true, Ordering::Relaxed);
            shared.inviting.store(false, Ordering::Relaxed);
            None
        }
        AppleCmd::Reject => {
            log::warn!("RTP MIDI INVITE rejected by {src}");
            shared.inviting.store(false, Ordering::Relaxed);
            *shared.last_error.lock().unwrap() = Some(format!("rejected by {src}"));
            None
        }
        AppleCmd::End => {
            log::info!("RTP MIDI peer {src} ended session");
            shared.peers.lock().unwrap().remove(&ssrc);
            // 次の接続試行は control 段階からやり直す
            shared.control_accepted.store(false, Ordering::Relaxed);
            None
        }
        AppleCmd::ClockSync => None,
    }
}

/// `host` または `host:port` を分解する。
fn split_host_port(host: &str, default_port: u16) -> (String, u16) {
    if let Some((h, p)) = host.rsplit_once(':') {
        if !h.is_empty() && !h.contains(':') {
            if let Ok(port) = p.parse::<u16>() {
                return (h.to_string(), port);
            }
        }
    }
    (host.to_string(), default_port)
}

/// 10kHz クロック (サービス起動基準)
fn now_10khz_since(start: Instant) -> u64 {
    start.elapsed().as_micros() as u64 / 100
}

/// CK 応答に使うグローバル 10kHz クロック (100µs 単位)
fn now_10khz() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_micros() as u64 / 100)
        .unwrap_or(0)
}

/// 簡易乱数 (ssrc / initiator 用)
pub fn random_u32() -> u32 {
    use std::time::{SystemTime, UNIX_EPOCH};
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| (d.subsec_nanos() as u64) ^ d.as_secs())
        .unwrap_or(0);
    let pid = std::process::id() as u64;
    let mut x = nanos
        .wrapping_mul(0x9E3779B97F4A7C15)
        .wrapping_add(pid.wrapping_mul(0x2545F4914F6CDD1D));
    // splitmix64
    x = x.wrapping_add(0x9E3779B97F4A7C15);
    x = (x ^ (x >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
    x = (x ^ (x >> 27)).wrapping_mul(0x94D049BB133111EB);
    (x ^ (x >> 31)) as u32
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossbeam_channel::unbounded;

    #[test]
    fn invitation_roundtrip() {
        let pkt = build_applemidi_packet(AppleCmd::Invite, 0x1234_5678, 0xDEAD_BEEF, "Pianotype PC");
        assert_eq!(&pkt[0..2], &[0xFF, 0xFF]);
        assert_eq!(&pkt[2..4], b"IN");
        assert_eq!(&pkt[4..8], &[0x00, 0x00, 0x00, 0x02]);
        match parse_applemidi(&pkt).unwrap() {
            AppleMidiPacket::Invitation {
                cmd,
                initiator,
                ssrc,
                name,
            } => {
                assert_eq!(cmd, AppleCmd::Invite);
                assert_eq!(initiator, 0x1234_5678);
                assert_eq!(ssrc, 0xDEAD_BEEF);
                assert_eq!(name, "Pianotype PC");
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn accept_and_by_packets_parse() {
        for (cmd, sig) in [
            (AppleCmd::Accept, &b"OK"[..]),
            (AppleCmd::Reject, &b"NO"[..]),
            (AppleCmd::End, &b"BY"[..]),
        ] {
            let pkt = build_applemidi_packet(cmd, 1, 2, "x");
            assert_eq!(&pkt[2..4], sig);
            assert!(parse_applemidi(&pkt).is_some());
        }
    }

    #[test]
    fn ck_packet_and_response_counts() {
        // CK0 (自分から同期開始)
        let req0 = build_ck_packet(0xABCD, 0, [0, 0, 0]);
        assert_eq!(req0.len(), 36);
        match parse_applemidi(&req0).unwrap() {
            AppleMidiPacket::ClockSync { count, .. } => assert_eq!(count, 0),
            other => panic!("{other:?}"),
        }
        // CK0 → CK1 応答
        let resp = build_ck_response(0x1111, &parse_applemidi(&req0).unwrap(), 12345).unwrap();
        match parse_applemidi(&resp).unwrap() {
            AppleMidiPacket::ClockSync {
                ssrc,
                count,
                timestamps,
            } => {
                assert_eq!(ssrc, 0x1111);
                assert_eq!(count, 1);
                assert_eq!(timestamps, [0, 12345, 0]);
            }
            other => panic!("{other:?}"),
        }

        // CK1 → CK2 応答 (ts0, ts1 をエコー)
        let req1 = build_ck_packet(0xABCD, 1, [42, 55, 0]);
        let resp = build_ck_response(0x1111, &parse_applemidi(&req1).unwrap(), 999).unwrap();
        match parse_applemidi(&resp).unwrap() {
            AppleMidiPacket::ClockSync { count, timestamps, .. } => {
                assert_eq!(count, 2);
                assert_eq!(timestamps, [42, 55, 999]);
            }
            other => panic!("{other:?}"),
        }

        // CK2 → 応答なし
        let req2 = build_ck_packet(1, 2, [1, 2, 3]);
        assert!(build_ck_response(1, &parse_applemidi(&req2).unwrap(), 0).is_none());
    }

    #[test]
    fn rtp_midi_roundtrip() {
        let midi = [0x90, 0x3C, 0x40];
        let pkt = build_rtp_midi(0xCAFE, 42, 1000, &midi);
        assert_eq!(pkt[0], 0x80);
        assert_eq!(pkt[1], 0x80 | RTP_MIDI_PT);
        assert_eq!(u16::from_be_bytes([pkt[2], pkt[3]]), 42);
        let section = parse_rtp_midi(&pkt).unwrap();
        assert_eq!(&section[1..], &midi[..]);
        let cmds = parse_midi_commands(section);
        assert_eq!(cmds, vec![(0x90, 0x3C, 0x40)]);
    }

    #[test]
    fn parse_rtp_rejects_non_rtp() {
        assert!(parse_rtp_midi(&[0u8; 12]).is_none());
        assert!(parse_rtp_midi(&[0xFF, 0xFF]).is_none());
        assert!(parse_rtp_midi(&[]).is_none());
    }

    #[test]
    fn midi_command_extraction_variants() {
        // NoteOff (0x80)
        let cmds = parse_midi_commands(&[0x03, 0x80, 0x3C, 0x00]);
        assert_eq!(cmds, vec![(0x80, 0x3C, 0)]);
        // running status: 0x90 60 64, 62 70 (running)
        let cmds = parse_midi_commands(&[0x05, 0x90, 60, 64, 62, 70]);
        assert_eq!(cmds, vec![(0x90, 60, 64), (0x90, 62, 70)]);
        // NoteOn with velocity 0 == NoteOff
        let cmds = parse_midi_commands(&[0x03, 0x90, 60, 0]);
        assert_eq!(cmds, vec![(0x90, 60, 0)]);
        // CC は読み飛ばし
        let cmds = parse_midi_commands(&[0x03, 0xB0, 7, 100]);
        assert!(cmds.is_empty());
        // delta time 付き (Z=1): len は delta 込み。 [delta=0] 0x90 60 64
        let cmds = parse_midi_commands(&[0x24, 0x00, 0x90, 60, 64]);
        assert_eq!(cmds, vec![(0x90, 60, 64)]);
        // delta time 付き (2バイト VLQ): 0x81 0x00 (=128)
        let cmds = parse_midi_commands(&[0x25, 0x81, 0x00, 0x90, 60, 64]);
        assert_eq!(cmds, vec![(0x90, 60, 64)]);
        // Apple 風: 最初のコマンドは delta なし (Z=0)、以降は delta 0 + 完全 status
        let cmds = parse_midi_commands(&[0x07, 0x90, 60, 64, 0x00, 0x90, 62, 70]);
        assert_eq!(cmds, vec![(0x90, 60, 64), (0x90, 62, 70)]);
    }

    #[test]
    fn split_host_port_variants() {
        assert_eq!(
            split_host_port("192.168.1.5", 5004),
            ("192.168.1.5".into(), 5004)
        );
        assert_eq!(
            split_host_port("192.168.1.5:6004", 5004),
            ("192.168.1.5".into(), 6004)
        );
        assert_eq!(
            split_host_port("piano.local", 5004),
            ("piano.local".into(), 5004)
        );
    }

    #[test]
    fn service_send_without_peer_is_silent() {
        // ピアがいなければ送信は何もしない (エラーにならない)
        let (tx, _rx) = unbounded();
        let port = 30000 + (random_u32() % 20000) as u16; // 衝突しにくいポート
        if UdpSocket::bind(("0.0.0.0", port)).is_err() {
            return; // ポートが取れない環境ではスキップ
        }
        let svc = RtpMidiService::start(None, port, "test", None, tx).unwrap();
        svc.send_note_on(1, 60, 100);
        svc.send_note_off(1, 60);
        let st = svc.status();
        assert_eq!(st.sent, 0);
        assert!(!st.connected());
    }

    #[test]
    fn service_loops_back_note_over_udp() {
        // 実際に UDP を往復して NoteOn が受信チャンネルに届くことを確認する。
        // A (control a_port) が B (control b_port) を INVITE → B の受信を検証。
        let (tx_a, _rx_a) = unbounded();
        let (tx_b, rx_b) = unbounded();
        let a_port = 20000 + (random_u32() % 8000) as u16 * 4;
        let b_port = a_port + 4;
        if UdpSocket::bind(("0.0.0.0", a_port)).is_err()
            || UdpSocket::bind(("0.0.0.0", b_port)).is_err()
        {
            return; // 環境制約時はスキップ
        }
        let a = RtpMidiService::start(
            Some(&format!("127.0.0.1:{b_port}")),
            a_port,
            "A",
            Some(3),
            tx_a,
        )
        .unwrap();
        let b = RtpMidiService::start(None, b_port, "B", None, tx_b).unwrap();

        // 接続確立を待つ (INVITE ループ 1.2s 間隔 × 2 段階)
        // A 側の peer 登録は data OK 受信後なので、A の状態を見る
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline {
            let a_c = a.status().connected();
            let b_c = b.status().connected();
            if a_c && b_c {
                break;
            }
            if cfg!(debug_assertions) {
                eprintln!("waiting: a={a_c} b={b_c} a={:?} b={:?}", a.status(), b.status());
            }
            thread::sleep(Duration::from_millis(500));
        }
        assert!(
            a.status().connected() && b.status().connected(),
            "A and B should be connected (a: {:?}, b: {:?})",
            a.status(),
            b.status()
        );

        // A → B へノート送信 (A 側はチャンネル上書き 3)
        a.send_note_on(1, 60, 90);
        a.send_note_off(1, 60);
        let ev1 = rx_b
            .recv_timeout(Duration::from_secs(3))
            .expect("B should receive NoteOn");
        match ev1 {
            MidiEvent::NoteOn {
                channel,
                note,
                velocity,
            } => {
                assert_eq!(channel, 3); // 上書きチャンネル
                assert_eq!(note, 60);
                assert_eq!(velocity, 90);
            }
            other => panic!("{other:?}"),
        }
        let ev2 = rx_b
            .recv_timeout(Duration::from_secs(3))
            .expect("B should receive NoteOff");
        assert_eq!(ev2, MidiEvent::NoteOff { channel: 3, note: 60 });
        eprintln!("[T] dropping services");
        drop(b);
        drop(a);
        eprintln!("[T] dropped");
    }
}
