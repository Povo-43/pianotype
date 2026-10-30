# Pianotype Android

MIDI キーボードの和音を Android の任意のアプリへのキー入力に変換する IME (入力メソッド) です。
PC 版 pianotype の `pianotype.toml` と完全互換です。

## 仕組み

Android で root 無しに任意のキー (F1-F12、矢印、CTRL+C など) を他アプリへ注入するには
`InputMethodService` (IME) を使うのが唯一の公式な方法です。本アプリは IME として動作し、
MIDI 入力を監視して、設定された和音が成立した瞬間に対応するキーを「今フォーカスされている
アプリ」に送出します。

## ビルド

### ローカル (Android Studio)
1. このフォルダを Android Studio で開く
2. 初回は Gradle Wrapper が自動生成されます
3. ▶ Run で `app` をデバイスへインストール

### ローカル (CLI)
```bash
gradle wrapper --gradle-version 8.7   # 初回のみ
./gradlew assembleDebug
```
APK は `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

### GitHub Actions
`main` ブランチへ push すると自動で debug APK がビルドされ、Artifacts からダウンロードできます。

## 使い方

1. アプリを起動 → 設定画面で MIDI デバイス、入力モード、RTP MIDI 送信、マッピングを設定
   - 見た目・チャンネル・MIDI モニター・TOML 編集などは「詳細設定」ページにまとまっています
2. 「IME設定」ボタン → システム設定で Pianotype を有効化
3. 任意のアプリでテキストフィールドをタップ → キーボード切替で Pianotype を選ぶ
4. MIDI キーボードで TOGGLE に割り当てた和音を弾くと ON/OFF が切り替わる
5. ON の状態で他の和音を弾くと、その value が入力される

## PC 版との互換性

- `pianotype.toml` の `midi_device`, `hand_split`, `require_both_hands`, `[[mapping]]` をそのまま読み書きできます
- 設定画面の「TOML を貼り付けて読み込む」で PC 版の設定をそのままインポートできます
- `value` で使えるコマンド名は PC 版とほぼ同じです

## 入力モード (かな / 英数)

設定画面の「入力モード」で切り替えられます。現在のモードは IME の状態表示に反映されます (例: `Pianotype: ON / かな`)。

- **英数モード**: 既定の `[[mapping]]` (`a`, `1`, `SPACE` など) を使用
- **かなモード**: `[[kana_mapping]]` を使用。左手 3 音のコードで「行」を、右手の 1 音で列を選び
  日本語のかな文字を直接入力します (あ・か・さ…、濁音・半濁音・小書き・記号も内蔵の既定配列)
- **鍵盤から切替**: `value = "KANA"` を割り当てた和音 (既定では B1 = ノート 35) を弾くとモードが切り替わります
- **ローマ字からの入力**: かなモード中にローマ字 (`ka`, `shi` など) を出す和音を弾くと
  ローマ字をかなに変換して入力します。未確定の文字は SPACE / ENTER で確定
  - `kana_mode = true / false` で現在のモードを記録します
  - PC 版 pianotype はこの 2 項目 + `[[kana_mapping]]` を読み飛ばすため、設定ファイルの受け渡しには影響しません
- `value` にかな文字 (1 文字だけでなく `きゃ` のような複数文字) を指定すればそのまま入力されます

### かな漢字変換 (日本語 IME と同じ方式)

かなモードでは内蔵の **OpenWnn** かな漢字変換エンジン
(AOSP `platform/packages/inputmethods/OpenWnn`, © OMRON SOFTWARE Co., Ltd., Apache License 2.0) を使って、
日本語 IME と同じような変換ができます。
ソースは `app/src/main/java/jp/co/omronsoft/openwnn/` と `app/src/main/cpp/` 以下に同梱しています (ライセンスは `app/src/main/cpp/NOTICE.OpenWnn`)。

1. ローマ字の和音を弾くと、その場で平仮名に変換されて composing (未確定文字) として表示されます
   (例: `k` `y` `o` `u` → `きょう`)
2. `SPACE` と同じ和音 (既定の kana_mapping では `SPACE`) を弾くと連文節変換します
   (例: `きょうはいいてんき` → `今日はいい天気` / `今日 は いい 天気` に文節分割)
3. 変換後の `SPACE` は対象文節の次の候補に切り替えます。候補一周で左隣の文節へ移ります
4. `LEFT` / `RIGHT` (`OutputAction.Key` の矢印キーを出す和音を `[[kana_mapping]]` に設定) で
   対象文節を移動できます
5. `BACKSPACE` で変換をやめて (元の平仮名に戻ってから末尾 1 文字ずつ消えます)、
   `ENTER` でその場の文字列を確定します。

辞書がまだロードされていない間や変換に失敗したときは、従来どおり平仮名のまま SPACE で確定します。

## RTP MIDI 送信 (MIDI センダー)

設定画面の「RTP MIDI 送信」を有効にすると、接続した MIDI キーボードの Note On/Off を
RTP-MIDI (AppleMIDI) プロトコルでネットワークへリアルタイム転送します。
スマホが「MIDI キーボード ↔ ネットワーク」のワイヤレスブリッジになります。

- macOS の「オーディオ MIDI 設定 → ネットワーク」、Linux の `rtpmidid`、Windows の `rtpMIDI` ドライバと相互接続できます
- **接続先 IP + ポート (既定 5004)** を指定するとスマホから能動的にセッションを張ります (INV → OK ハンドシェイク、CK 時計同期に対応)
- スマホ側でも同じポートで待ち受けるため、Mac 側からスマホへ接続することもできます
- 転送はフォアグラウンドサービス (常駐・通知表示) が行います。IME を使っていなくても鍵盤を弾けば転送されます
- 「送信チャンネル」で転送時の MIDI チャンネルを強制できます (未指定なら受信チャンネルのまま)
- TOML キー: `rtp_midi_enabled`, `rtp_midi_host`, `rtp_midi_port`, `rtp_midi_channel`, `rtp_midi_name`
  (PC 版 pianotype はこれらのキーを無視するため、設定ファイルの受け渡しには影響しません)

## 制限事項

- IME の仕様上、テキスト入力可能なフィールドにのみ注入できます
- 一部のシステム UI やパスワード入力欄など、IME が制限されている画面では動作しません
- Bluetooth MIDI は Android 6.0 以降、USB MIDI は OTG 対応端末で利用可能です