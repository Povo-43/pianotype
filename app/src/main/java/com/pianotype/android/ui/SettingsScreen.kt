package com.pianotype.android.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pianotype.android.config.Config
import com.pianotype.android.config.ConfigRepository
import com.pianotype.android.config.Mapping
import com.pianotype.android.config.TomlConfig
import com.pianotype.android.keyboard.ValueParser
import com.pianotype.android.midi.MidiDeviceScanner
import com.pianotype.android.midi.MidiEvent
import com.pianotype.android.midi.MidiListener

@Composable
fun SettingsContent(
    context: Context,
    themeColor: Color,
    bgColor: Color,
    onThemeColorChange: (Color) -> Unit,
    onBgColorChange: (Color) -> Unit,
) {
    var config by remember { mutableStateOf(ConfigRepository.current) }
    var deviceList by remember { mutableStateOf(MidiDeviceScanner.list(context)) }
    var showTomlDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    var showThemePicker by remember { mutableStateOf(false) }
    var showBgPicker by remember { mutableStateOf(false) }

    var monitorOn by remember { mutableStateOf(false) }
    var heldNotes by remember { mutableStateOf<List<Int>>(emptyList()) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var monitorListener by remember { mutableStateOf<MidiListener?>(null) }

    // 現在のモードで使うマッピング (英数 / かな)
    val activeMappings: List<Mapping> = config.activeMapping

    fun setActiveMappings(list: List<Mapping>) {
        config = if (config.kanaMode) config.copy(kanaMapping = list) else config.copy(mapping = list)
        ConfigRepository.update(context, config)
    }

    fun setKanaMode(kana: Boolean) {
        if (config.kanaMode == kana) return
        config = config.copy(kanaMode = kana)
        ConfigRepository.update(context, config)
        statusMsg = if (kana) "かなモードに切り替えました" else "英数モードに切り替えました"
    }

    DisposableEffect(Unit) {
        val l: (Config) -> Unit = { config = it }
        ConfigRepository.addListener(l)
        onDispose { ConfigRepository.removeListener(l) }
    }

    LaunchedEffect(monitorOn, config.midiDevice, config.midiChannel) {
        if (monitorOn) {
            val l = MidiListener(context, config.midiDevice, config.midiChannel) { ev ->
                when (ev) {
                    is MidiEvent.NoteOn -> {
                        if (!heldNotes.contains(ev.note)) {
                            heldNotes = (heldNotes + ev.note).sorted()
                        }
                        log = (log + "ON  ${ev.note}  (chord: ${(heldNotes + ev.note).distinct().sorted().joinToString(",")})")
                            .takeLast(40)
                    }
                    is MidiEvent.NoteOff -> {
                        heldNotes = heldNotes - ev.note
                        log = (log + "OFF ${ev.note}").takeLast(40)
                    }
                }
            }.also { it.start() }
            monitorListener = l
        } else {
            monitorListener?.close()
            monitorListener = null
            heldNotes = emptyList()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            monitorListener?.close()
            monitorListener = null
        }
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------------- 見た目 ----------------
        Text("見た目", style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(themeColor)
                    .border(1.dp, Color.Gray, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("テーマカラー")
                Text(
                    String.format("#%08X", themeColor.toArgb()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            TextButton(onClick = { showThemePicker = true }) { Text("変更") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(bgColor)
                    .border(1.dp, Color.Gray, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("背景色")
                Text(
                    String.format("#%08X", bgColor.toArgb()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            TextButton(onClick = { showBgPicker = true }) { Text("変更") }
        }

        Divider()

        // ---------------- 入力モード ----------------
        Text("入力モード", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !config.kanaMode,
                onClick = { setKanaMode(false) },
                label = { Text("英数 (a b c)") }
            )
            FilterChip(
                selected = config.kanaMode,
                onClick = { setKanaMode(true) },
                label = { Text("かな (あ い う)") }
            )
        }
        Text(
            if (config.kanaMode) "かなモード: 和音が日本語のかな文字を入力します。"
            else "英数モード: 和音が英数字・記号キーを入力します。",
            style = MaterialTheme.typography.bodySmall
        )

        // ---------------- MIDI モニター ----------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("MIDI モニター", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = monitorOn, onCheckedChange = { monitorOn = it })
                        Spacer(Modifier.width(4.dp))
                        Text(if (monitorOn) "ON" else "OFF")
                    }
                }

                Text(
                    "押されている鍵盤: " +
                        if (heldNotes.isEmpty()) "(なし)"
                        else heldNotes.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (log.isNotEmpty()) {
                    Text(
                        text = log.reversed().joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp, max = 220.dp)
                            .background(Color.Black.copy(alpha = 0.06f))
                            .padding(8.dp),
                    )
                } else if (monitorOn) {
                    Text("鍵盤を弾いてください…", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { log = emptyList() }) { Text("ログをクリア") }
            }
        }

        Divider()

        // ---------------- MIDI デバイス ----------------
        Text("MIDI 入力デバイス", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            var expanded by remember { mutableStateOf(false) }
            val label = config.midiDevice ?: "(最初に見つかったデバイス)"
            OutlinedButton(onClick = {
                deviceList = MidiDeviceScanner.list(context)
                expanded = true
            }) { Text(label) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("(auto / 先頭)") },
                    onClick = {
                        config = config.copy(midiDevice = null)
                        ConfigRepository.update(context, config)
                        expanded = false
                    }
                )
                deviceList.forEachIndexed { idx, item ->
                    DropdownMenuItem(
                        text = { Text("$idx: ${item.name}") },
                        onClick = {
                            config = config.copy(midiDevice = item.name)
                            ConfigRepository.update(context, config)
                            expanded = false
                        }
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { deviceList = MidiDeviceScanner.list(context) }) {
                Text("更新")
            }
        }

        // ---------------- MIDI チャンネル ----------------
        Text("MIDI チャンネル", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            var chExpanded by remember { mutableStateOf(false) }
            val chLabel = config.midiChannel?.let { "CH $it" } ?: "(全チャンネル)"
            OutlinedButton(onClick = { chExpanded = true }) { Text(chLabel) }
            DropdownMenu(expanded = chExpanded, onDismissRequest = { chExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("(全チャンネル)") },
                    onClick = {
                        config = config.copy(midiChannel = null)
                        ConfigRepository.update(context, config)
                        chExpanded = false
                    }
                )
                (1..16).forEach { ch ->
                    DropdownMenuItem(
                        text = { Text("CH $ch") },
                        onClick = {
                            config = config.copy(midiChannel = ch)
                            ConfigRepository.update(context, config)
                            chExpanded = false
                        }
                    )
                }
            }
        }

        Divider()

        // ---------------- mappings ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "マッピング (${if (config.kanaMode) "かな" else "英数"}: ${activeMappings.size})",
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("追加")
            }
        }

        if (activeMappings.isEmpty()) {
            Text("(未登録)", style = MaterialTheme.typography.bodyMedium)
        } else {
            activeMappings.forEachIndexed { idx, m ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("keys: ${m.keys.joinToString(", ")}")
                            Text("value: ${m.value}")
                        }
                        IconButton(onClick = { editingIndex = idx }) {
                            Icon(Icons.Default.Edit, contentDescription = "edit")
                        }
                        IconButton(onClick = {
                            val newList = activeMappings.toMutableList().also { it.removeAt(idx) }
                            setActiveMappings(newList)
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "delete")
                        }
                    }
                }
            }
        }

        Divider()

        Button(
            onClick = { showTomlDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("TOML を貼り付けて読み込む / 表示") }

        OutlinedButton(
            onClick = { showResetConfirm = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("おすすめ設定に戻す") }

        statusMsg?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showThemePicker) {
        ColorPickerDialog(
            title = "テーマカラー",
            initial = themeColor,
            onDismiss = { showThemePicker = false },
            onConfirm = {
                onThemeColorChange(it)
                showThemePicker = false
            }
        )
    }

    if (showBgPicker) {
        ColorPickerDialog(
            title = "背景色",
            initial = bgColor,
            onDismiss = { showBgPicker = false },
            onConfirm = {
                onBgColorChange(it)
                showBgPicker = false
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("おすすめ設定に戻す") },
            text = { Text("英数 (${config.mapping.size}件)・かな (${config.kanaMapping.size}件) のマッピングはすべて既定に戻されます。よろしいですか？") },
            confirmButton = {
                TextButton(onClick = {
                    ConfigRepository.resetToDefault(context)
                    config = ConfigRepository.current
                    statusMsg = "おすすめ設定に戻しました"
                    showResetConfirm = false
                }) { Text("戻す") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("キャンセル") }
            }
        )
    }

    if (showTomlDialog) {
        TomlEditorDialog(
            initial = TomlConfig.serialize(config),
            onDismiss = { showTomlDialog = false },
            onApply = { text ->
                try {
                    ConfigRepository.loadFromToml(context, text)
                    config = ConfigRepository.current
                    statusMsg = "TOML を読み込みました (${config.mapping.size} mappings)"
                    showTomlDialog = false
                } catch (e: Exception) {
                    statusMsg = "エラー: ${e.message}"
                }
            },
        )
    }

    if (showAddDialog || editingIndex != null) {
        MappingEditorDialog(
            initial = editingIndex?.let { activeMappings[it] } ?: Mapping(emptyList(), ""),
            context = context,
            config = config,
            onDismiss = { showAddDialog = false; editingIndex = null },
            onSave = { newMapping ->
                val list = activeMappings.toMutableList()
                val idx = editingIndex
                if (idx == null) list.add(newMapping) else list[idx] = newMapping
                setActiveMappings(list)
                showAddDialog = false
                editingIndex = null
            },
        )
    }
}

@Composable
private fun TomlEditorDialog(
    initial: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TOML") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 400.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
            )
        },
        confirmButton = { TextButton(onClick = { onApply(text) }) { Text("読み込む") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun MappingEditorDialog(
    initial: Mapping,
    context: Context,
    config: Config,
    onDismiss: () -> Unit,
    onSave: (Mapping) -> Unit,
) {
    var keysText by remember { mutableStateOf(initial.keys.joinToString(" ")) }
    var value by remember { mutableStateOf(initial.value) }
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
        title = { Text(if (initial.keys.isEmpty()) "マッピング追加" else "マッピング編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("keys (MIDI note 番号、スペース区切り)", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = keysText,
                    onValueChange = { keysText = it },
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
                        keysText = capturedNotes.joinToString(" ")
                    }) { Text("この和音を採用") }
                }
                Text("value (かな / 文字 / キー名 / TOGGLE / SHIFT / CTRL+F3 など)", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (value.isNotEmpty() && !ValueParser.isValid(value)) {
                    Text("無効な値です", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotEmpty() && ValueParser.isValid(value),
                onClick = {
                    val keys = keysText.split(" ", ",")
                        .filter { it.isNotBlank() }
                        .mapNotNull { it.toIntOrNull() }
                        .sorted()
                    onSave(Mapping(keys, value))
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}