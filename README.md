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

1. アプリを起動 → 設定画面で MIDI デバイス、hand_split、マッピングを設定
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
  - `kana_mode = true / false` で現在のモードを記録します
  - PC 版 pianotype はこの 2 項目 + `[[kana_mapping]]` を読み飛ばすため、設定ファイルの受け渡しには影響しません
- `value` にかな文字 (1 文字だけでなく `きゃ` のような複数文字) を指定すればそのまま入力されます

## 制限事項

- IME の仕様上、テキスト入力可能なフィールドにのみ注入できます
- 一部のシステム UI やパスワード入力欄など、IME が制限されている画面では動作しません
- Bluetooth MIDI は Android 6.0 以降、USB MIDI は OTG 対応端末で利用可能です