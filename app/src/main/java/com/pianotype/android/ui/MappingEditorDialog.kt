package com.pianotype.android.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pianotype.android.config.Config
import com.pianotype.android.config.Mapping
import com.pianotype.android.keyboard.ValueParser
import com.pianotype.android.midi.MidiEvent
import com.pianotype.android.midi.MidiListener

/**
 * マッピング追加 / 編集ダイアログ。
 * keys は MIDI の chord capture でも、手入力でも指定できる。
 * value にはかな（例: 「あ」「きゃ」）も指定できる。
 */
@Composable
fun MappingEditorDialog(
    initial: Mapping,
    context: Context,
    config: Config,
    onDismiss: () -> Unit,
    onSave: (Mapping) -> Unit,
) {
    var keysText by remember { mutableStateOf(initial.keys.joinToString(",")) }
    var valueText by remember { mutableStateOf(initial.value) }
    var capturing by remember { mutableStateOf(false) }
    var capturedNotes by remember { mutableStateOf<List<Int>>(emptyList()) }
    var listener by remember { mutableStateOf<MidiListener?>(null) }

    LaunchedEffect(capturing, config.midiDevice, config.midiChannel) {
        if (capturing) {
            capturedNotes = emptyList()
            val l = MidiListener(context, config.midiDevice, config.midiChannel) { ev ->
                when (ev) {
                    is MidiEvent.NoteOn -> {
                        capturedNotes = (capturedNotes + ev.note).distinct().sorted()
                    }
                    is MidiEvent.NoteOff -> {}
                }
            }.also { it.start() }
            listener = l
        } else {
            listener?.close()
            listener = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            listener?.close()
            listener = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.keys.isEmpty() && initial.value.isEmpty()) "マッピング追加" else "マッピング編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = keysText,
                    onValueChange = { keysText = it },
                    label = { Text("keys (例: 60,64,67)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { capturing = !capturing },
                        colors = if (capturing) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ) else ButtonDefaults.buttonColors()
                    ) {
                        Text(if (capturing) "停止" else "MIDI でキャプチャ")
                    }
                    if (capturing) {
                        Spacer(Modifier.width(8.dp))
                        Text("録音中...")
                    }
                }
                if (capturedNotes.isNotEmpty()) {
                    Text("取得中: ${capturedNotes.joinToString(", ")}")
                    TextButton(onClick = {
                        keysText = capturedNotes.joinToString(",")
                    }) { Text("この和音を採用") }
                }
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text("value (かな / 文字 / キー名 / TOGGLE / SHIFT / KANA / CTRL+F3)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (valueText.isNotEmpty() && !ValueParser.isValid(valueText)) {
                    Text("無効な値です", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valueText.isNotEmpty() && ValueParser.isValid(valueText),
                onClick = {
                    val keys = keysText.split(",", " ")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .sorted()
                    onSave(Mapping(keys, valueText))
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
