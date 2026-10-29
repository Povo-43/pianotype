# PianoType PC

MIDI キーボードの和音を PC 上のキー入力に変換するツールです。
`pianotype_mobile` (Android 版 IME) と **同じ `pianotype.toml`** を読み書きします。

![platforms](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-blue)

## 仕組み

MIDI 入力 (USB / Bluetooth / 仮想デバイス) を監視し、設定された和音が成立した瞬間に
対応するキー操作を「現在フォーカスされているアプリ」へ送出します。
GUI (egui) でデバイス選択・マッピング編集・設定ファイルの読み書きができます。

## ビルド

```bash
cargo build --release
```

### Linux の依存パッケージ

```bash
# Debian / Ubuntu
sudo apt install pkg-config libasound2-dev libxdo-dev
```

macOS / Windows は追加のシステム依存なしでビルドできます。

## 使い方

1. 起動すると初回のみ既定の `pianotype.toml` を設定ディレクトリへ書き出します
   (既定は 88 鍵の左端 A1 = note 33 で TOGGLE、34 で SHIFT、35 で KANA)。
2. MIDI セクションでデバイスを選び **Connect**。
3. MIDI キーボードで TOGGLE の和音を弾くと ON / OFF が切り替わります。
4. ON の状態で他の和音を弾くと、その `value` が入力されます。

設定ファイルの場所:

| OS | パス |
|---|---|
| Linux | `~/.config/pianotype/pianotype.toml` |
| macOS | `~/Library/Application Support/dev.Povo-43.pianotype/pianotype.toml` |
| Windows | `%APPDATA%\Povo-43\pianotype\config\pianotype.toml` |

GUI から Load / Save / Import / Export も可能です。

## Android 版との互換性

- `midi_device`, `midi_channel`, `hand_split`, `require_both_hands`, `[[mapping]]` を
  そのまま読み書きできます
- **かなモード**: PC 版では `KANA` コマンドは OS の IME 切替キーを送出します
  (Windows: かなキー / Linux: 半角・全角 / macOS: JIS かなキー)。
  日本語入力自体は OS の IME に任せます。
  `kana_mode` と `[[kana_mapping]]` はモバイル版との設定ファイル互換のため
  読み書きされますが、PC 版の変換には使用しません
  (Android へエクスポートした設定ファイルでそのまま活きます)
- `value` で使えるコマンド名は Android 版とほぼ同じです:
  `ENTER` `TAB` `SPACE` `ESC` `BACKSPACE` `DELETE` `INSERT` `UP/DOWN/LEFT/RIGHT`
  `HOME` `END` `PAGEUP` `PAGEDOWN` `F1`〜`F12` `NUMPAD0`〜`9` `VOLUMEUP/DOWN/MUTE`
  `MEDIANEXT/PREV/STOP/PLAYPAUSE` `CAPSLOCK` など、
  修飾キー付きは `CTRL+C` `CMD+Z` `ALT+F4` `CTRL+SHIFT+T` 形式
- 単一文字 (`a`, `1`, `?`) や非 ASCII 文字列 (`きゃ`, `ー` など) もそのまま送出できます

## RTP MIDI (AppleMIDI) 送受信

PC をネットワーク上の MIDI 機器と接続できます (設定不要の UDP 実装、既定ポート 5004/5005)。

- **Sender**: PC に接続した MIDI キーボードのノートを、INVITE で接続した相手
  (Android 版 Pianotype、rtpMIDI ドライバ、rtpmidid、iOS の Network MIDI など) へ転送
- **Receiver**: リモートからの INVITE を受け入れ、受信したノートで PC のキー入力を
  行う (Android から PC へのリモートタイピングが可能)

### 設定 (GUI または `pianotype.toml`)

```toml
rtp_midi_enabled = true
rtp_midi_host = "192.168.1.10"   # 接続先 (省略時は待ち受けのみで受信可)
rtp_midi_port = 5004             # control ポート (data は +1)
rtp_midi_channel = 1             # 送信チャンネル上書き (省略時は元のまま)
rtp_midi_name = "Pianotype PC"   # セッション名
```

- `rtp_midi_host` を指定すると `host[:control_port]` へ INVITE を送り続け、
  OK が返るとセッションが確立します (接続先の data ポートは control + 1)。
- 省略した場合は待ち受けのみで、相手からの INVITE を受け入れます
  (macOS の Audio MIDI Setup や Android アプリから接続してください)。
- GUI の RTP MIDI セクションで接続状態・送受信パケット数を確認できます。

### 接続例

| 相手 | 手順 |
|---|---|
| macOS | Audio MIDI Setup → MIDI Studio → ネットワーク → セッション作成 → PC の IP を追加 |
| Windows (rtpMIDI) | rtpMIDI の設定で Dir=IN に PC の IP:5004 を追加 |
| Linux (rtpmidid) | `rtpmidid` が待ち受けている 5004 へ host 指定、または逆に PC 側を待ち受けに |
| Android | MIDI over BLE/RTP 対応アプリから PC の IP:5004 へ接続 |

## リリース (GitHub Actions)

`v*` タグを push すると、Windows / Linux 向けバイナリが自動ビルドされ、
GitHub Release にアセットとして登録されます:

```bash
git tag v0.2.0
git push origin v0.2.0
```

| アセット | 内容 |
|---|---|
| `pianotype-pc-<tag>-windows-x64.zip` | Windows (x64, MSVC) の `pianotype.exe` |
| `pianotype-pc-<tag>-linux-x64.tar.gz` | Linux (x64, Ubuntu 22.04 ビルド) の `pianotype` |

Linux バイナリの実行には `libasound.so.2` (ALSA) と `libxdo.so.3` が必要です
(`sudo apt install libasound2 libxdo3`)。

また `master` への push / PR 時には両 OS で `cargo test` + ビルドの
チェック (`.github/workflows/build.yml`) が走ります。

## 開発

```bash
cargo test      # ユニットテスト (エンジン / 設定 / キーマップ / RTP MIDI 往復)
cargo run       # GUI 起動
```

RTP MIDI のテストには実際の UDP 往復 (`service_loops_back_note_over_udp`) が含まれ、
INVITE → data INVITE → RTP MIDI NoteOn/NoteOff 受信までを検証します。

## ライセンス

MIT
