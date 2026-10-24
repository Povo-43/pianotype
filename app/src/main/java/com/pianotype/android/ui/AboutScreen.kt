package com.pianotype.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AboutContent() {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Pianotype", style = MaterialTheme.typography.headlineMedium)
        Text("バージョン 0.1.0", style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()

        Section("使い方") {
            Text(
                "MIDI キーボードの和音（コード）を、Android 上の任意のアプリへのキー入力に変換する入力メソッド (IME) です。" +
                "root 化は不要で、通常のキーボードと同じように切り替えて使えます。" +
                "PC 版 pianotype (Rust) の設定ファイル pianotype.toml と互換があります。"
            )
        }

        Section("基本的な使い方") {
            Bullet("1. このアプリで MIDI デバイスとマッピングを設定")
            Bullet("2. 入力モード (かな / 英数) を選ぶ")
            Bullet("3. 「IME設定」から Pianotype を有効化")
            Bullet("4. 任意のアプリのテキスト入力欄でキーボードを Pianotype に切り替え")
            Bullet("5. TOGGLE に割り当てた和音を弾いて ON にする")
            Bullet("6. ON の状態で他の和音を弾くと、対応する文字やキーが入力される")
        }

        Section("MIDI ノート番号の目安") {
            Mono("C4 = 60 (中央ハ)")
            Mono("C3 = 48")
            Mono("C5 = 72")
            Text("MIDI の慣習では C4=60 です。DAW によってはオクターブ表記が違う場合があります。", style = MaterialTheme.typography.bodySmall)
        }

        Section("value に書けるもの") {
            Mono("あ, きゃ, ー, ？      → かな (そのまま入力)")
            Mono("a, b, 1, ?            → 1文字")
            Mono("SPACE, ENTER, TAB     → 名前付きキー")
            Mono("F1 〜 F12, PAGEUP ...  → ファンクション/編集キー")
            Mono("LEFT, RIGHT, UP, DOWN → 矢印")
            Mono("NUMPAD0 〜 NUMPAD9    → テンキー")
            Mono("CTRL+C, ALT+TAB       → ショートカット")
            Mono("TOGGLE                → タイピング ON/OFF")
            Mono("SHIFT                 → 次の1アクションだけ Shift")
        }

        Section("入力モード (かな / 英数)") {
            Text("設定画面の「入力モード」で、かなと英数を切り替えられます。IME の表示に現在のモードが出ます。")
            Bullet("英数: 既定の [[mapping]] のキーを入力 (a, 1, SPACE …)")
            Bullet("かな: [[kana_mapping]] のかな文字を入力 (左手3音コードで行、右手音で列)")
            Mono("[[kana_mapping]]")
            Mono("keys = [50, 53, 57, 61]   # か行 + D#4")
            Mono("keys = [44, 47, 51, 60]   # 編集キー + C4")
            Text(
                "かなマッピングが未設定のときは内蔵の既定配列 (あ〜ん・濁音・半濁音・小書き・記号・数字) が使われます。" +
                "PC 版 pianotype は kana_mode と [[kana_mapping]] を読み飛ばすため、設定ファイルの受け渡しには影響しません。",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Section("TOML 設定ファイル") {
            Text("PC 版と同じ形式の TOML を「TOML を貼り付けて読み込む」からそのまま取り込めます。逆に、このアプリで作った設定を PC 版に持っていくこともできます。")
        }

        Section("制限事項") {
            Bullet("IME の仕様上、テキスト入力可能なフィールドにのみキーを送れます。")
            Bullet("一部のシステム UI やパスワード入力欄では動作しません。")
            Bullet("すべての MIDI デバイスが Android で認識されるわけではありません。")
        }

        HorizontalDivider()

        Text(
            "GitHub: Povo-43/pianoinput_mobile",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Text("・$text", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    )
}