package com.pianotype.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianotype.android.config.Config
import com.pianotype.android.config.ConfigRepository
import com.pianotype.android.config.Mapping
import com.pianotype.android.config.TomlConfig
import com.pianotype.android.midi.MidiDeviceScanner
import com.pianotype.android.midi.MidiEvent
import com.pianotype.android.midi.MidiListener
import com.pianotype.android.rtpmidi.RtpMidiBus
import com.pianotype.android.rtpmidi.RtpMidiService

// ============================================================================
// 共通パーツ
// ============================================================================

/** セクション見出し (アイコン + タイトル) */
@Composable
private fun SectionHeader(icon: ImageVector, title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/** アイコン付き設定行 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

private val Chevron = Icons.AutoMirrored.Filled.KeyboardArrowRight

// ============================================================================
// メイン設定ページ
// ============================================================================

@Composable
fun SettingsContent(
    context: Context,
    themeColor: Color,
    bgColor: Color,
    onThemeColorChange: (Color) -> Unit,
    onBgColorChange: (Color) -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    var config by remember { mutableStateOf(ConfigRepository.current) }
    var deviceList by remember { mutableStateOf(MidiDeviceScanner.list(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

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

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------------- 入力モード ----------------
        SectionHeader(Icons.Filled.Language, "入力モード")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !config.kanaMode,
                        onClick = { setKanaMode(false) },
                        label = { Text("英数") },
                        leadingIcon = {
                            Icon(Icons.Filled.Language, contentDescription = null, Modifier.size(18.dp))
                        }
                    )
                    FilterChip(
                        selected = config.kanaMode,
                        onClick = { setKanaMode(true) },
                        label = { Text("かな") },
                        leadingIcon = {
                            Icon(Icons.Filled.Translate, contentDescription = null, Modifier.size(18.dp))
                        }
                    )
                }
                Text(
                    if (config.kanaMode) "和音が日本語のかな文字を入力します。"
                    else "和音が英数字・記号キーを入力します。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // ---------------- MIDI キーボード ----------------
        SectionHeader(Icons.Filled.Piano, "MIDI キーボード")
        Card(Modifier.fillMaxWidth()) {
            Column {
                var deviceExpanded by remember { mutableStateOf(false) }
                Box {
                    SettingsRow(
                        icon = Icons.Filled.Usb,
                        title = "MIDI デバイス",
                        subtitle = config.midiDevice ?: "自動 (最初に見つかったデバイス)",
                        onClick = {
                            deviceList = MidiDeviceScanner.list(context)
                            deviceExpanded = true
                        },
                        trailing = { Icon(Chevron, contentDescription = null) },
                    )
                    DropdownMenu(expanded = deviceExpanded, onDismissRequest = { deviceExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("自動 (先頭のデバイス)") },
                            onClick = {
                                config = config.copy(midiDevice = null)
                                ConfigRepository.update(context, config)
                                deviceExpanded = false
                            }
                        )
                        deviceList.forEachIndexed { idx, item ->
                            DropdownMenuItem(
                                text = { Text("$idx: ${item.name}") },
                                onClick = {
                                    config = config.copy(midiDevice = item.name)
                                    ConfigRepository.update(context, config)
                                    deviceExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // ---------------- RTP MIDI 送信 ----------------
        val rtpStatus by RtpMidiBus.status.collectAsStateWithLifecycle()
        val notifPermission = Manifest.permission.POST_NOTIFICATIONS
        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        SectionHeader(Icons.Filled.WifiTethering, "RTP MIDI 送信")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Filled.WifiTethering,
                            contentDescription = null,
                            tint = if (config.rtpMidiEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column {
                            Text("鍵盤の MIDI をネットワークへ転送", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "macOS / rtpmidid / Windows rtpMIDI と接続",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = config.rtpMidiEnabled,
                        onCheckedChange = { on ->
                            if (on && Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, notifPermission)
                                    != PackageManager.PERMISSION_GRANTED
                            ) {
                                notifLauncher.launch(notifPermission)
                            }
                            config = config.copy(rtpMidiEnabled = on)
                            ConfigRepository.update(context, config)
                            if (on) RtpMidiService.start(context)
                        }
                    )
                }

                if (config.rtpMidiEnabled) {
                    var hostText by remember(config.rtpMidiHost) { mutableStateOf(config.rtpMidiHost ?: "") }
                    var portText by remember(config.rtpMidiPort) { mutableStateOf(config.rtpMidiPort.toString()) }

                    OutlinedTextField(
                        value = hostText,
                        onValueChange = { hostText = it },
                        label = { Text("接続先 IP / ホスト (空欄なら待ち受けのみ)") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Computer, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter { c -> c.isDigit() } },
                            label = { Text("ポート") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Filled.SettingsEthernet, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(170.dp),
                        )
                        TextButton(onClick = {
                            val port = portText.toIntOrNull()?.takeIf { p -> p in 1..65534 }
                                ?: run {
                                    statusMsg = "ポートは 1〜65534 で入力してください"
                                    return@TextButton
                                }
                            config = config.copy(
                                rtpMidiHost = hostText.trim().ifEmpty { null },
                                rtpMidiPort = port,
                            )
                            ConfigRepository.update(context, config)
                        }) { Text("適用") }
                    }

                    // ---- 接続状態 ----
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "待ち受けポート: ${rtpStatus.localPort}" +
                                (rtpStatus.target?.let { "  /  接続先: $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (rtpStatus.sessions.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Schedule, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)
                                )
                                Text("セッションなし (接続待ち…)", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            rtpStatus.sessions.forEach { s ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        if (s.connected) Icons.Filled.CheckCircle else Icons.Filled.Schedule,
                                        contentDescription = if (s.connected) "接続中" else "接続中 (確立待ち)",
                                        tint = if (s.connected) Color(0xFF4CAF50)
                                               else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        "${s.name}  ${s.address}${if (s.incoming) "  (相手から)" else ""}",
                                        style = MaterialTheme.typography.bodySmall
                                            .copy(fontFamily = FontFamily.Monospace),
                                    )
                                }
                            }
                        }
                        if (rtpStatus.notesSent > 0) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Filled.MusicNote, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("送信ノート: ${rtpStatus.notesSent}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        rtpStatus.lastError?.let {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Filled.ErrorOutline, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)
                                )
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // ---------------- マッピング ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(
                Icons.Filled.Keyboard,
                "マッピング (${if (config.kanaMode) "かな" else "英数"}: ${activeMappings.size})"
            )
            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("追加")
            }
        }

        if (activeMappings.isEmpty()) {
            Text(
                "和音とキーの対応が未登録です。「追加」で作成できます。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            activeMappings.forEachIndexed { idx, m ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    SettingsRow(
                        icon = Icons.Filled.MusicNote,
                        title = "keys: ${m.keys.joinToString(", ")}",
                        subtitle = "value: ${m.value}",
                        trailing = {
                            Row {
                                IconButton(onClick = { editingIndex = idx }) {
                                    Icon(Icons.Default.Edit, contentDescription = "編集")
                                }
                                IconButton(onClick = {
                                    val newList =
                                        activeMappings.toMutableList().also { it.removeAt(idx) }
                                    setActiveMappings(newList)
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "削除")
                                }
                            }
                        }
                    )
                }
            }
        }

        // ---------------- 詳細設定 ----------------
        Card(Modifier.fillMaxWidth()) {
            SettingsRow(
                icon = Icons.Filled.Tune,
                title = "詳細設定",
                subtitle = "見た目 / チャンネル / MIDI モニター / TOML",
                onClick = onOpenAdvanced,
                trailing = { Icon(Chevron, contentDescription = null) },
            )
        }

        statusMsg?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(24.dp))
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

// ============================================================================
// 詳細設定ページ
// ============================================================================

@Composable
fun AdvancedSettingsContent(
    context: Context,
    themeColor: Color,
    bgColor: Color,
    onThemeColorChange: (Color) -> Unit,
    onBgColorChange: (Color) -> Unit,
) {
    var config by remember { mutableStateOf(ConfigRepository.current) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showBgPicker by remember { mutableStateOf(false) }
    var showTomlDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val l: (Config) -> Unit = { config = it }
        ConfigRepository.addListener(l)
        onDispose { ConfigRepository.removeListener(l) }
    }

    // ---- MIDI モニター ----
    var monitorOn by remember { mutableStateOf(false) }
    var heldNotes by remember { mutableStateOf<List<Int>>(emptyList()) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var monitorListener by remember { mutableStateOf<MidiListener?>(null) }

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
        SectionHeader(Icons.Filled.Palette, "見た目")
        Card(Modifier.fillMaxWidth()) {
            Column {
                SettingsRow(
                    icon = Icons.Filled.ColorLens,
                    title = "テーマカラー",
                    subtitle = String.format("#%08X", themeColor.toArgb()),
                    onClick = { showThemePicker = true },
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(themeColor)
                                .border(1.dp, Color.Gray, CircleShape)
                        )
                    }
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Filled.FormatColorFill,
                    title = "背景色",
                    subtitle = String.format("#%08X", bgColor.toArgb()),
                    onClick = { showBgPicker = true },
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(bgColor)
                                .border(1.dp, Color.Gray, CircleShape)
                        )
                    }
                )
            }
        }

        // ---------------- MIDI 入力の詳細 ----------------
        SectionHeader(Icons.Filled.GraphicEq, "MIDI 入力")
        Card(Modifier.fillMaxWidth()) {
            Column {
                var chExpanded by remember { mutableStateOf(false) }
                Box {
                    SettingsRow(
                        icon = Icons.Filled.Equalizer,
                        title = "MIDI チャンネル",
                        subtitle = config.midiChannel?.let { "CH $it のみ扱う" } ?: "全チャンネルを扱う",
                        onClick = { chExpanded = true },
                        trailing = {
                            Text(
                                config.midiChannel?.let { "CH $it" } ?: "全て",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                    )
                    DropdownMenu(expanded = chExpanded, onDismissRequest = { chExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("全チャンネル") },
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
                HorizontalDivider()
                // MIDI モニター
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = if (monitorOn) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column {
                                Text("MIDI モニター", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "押されている鍵盤を確認できます",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(checked = monitorOn, onCheckedChange = { monitorOn = it })
                    }

                    if (monitorOn) {
                        Text(
                            "押されている鍵盤: " +
                                if (heldNotes.isEmpty()) "(なし)"
                                else heldNotes.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (log.isNotEmpty()) {
                            Text(
                                text = log.reversed().joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp, max = 220.dp)
                                    .background(Color.Black.copy(alpha = 0.06f))
                                    .padding(8.dp),
                            )
                        } else {
                            Text("鍵盤を弾いてください…", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { log = emptyList() }) { Text("ログをクリア") }
                    }
                }
            }
        }

        // ---------------- RTP MIDI の詳細 ----------------
        SectionHeader(Icons.Filled.WifiTethering, "RTP MIDI 詳細")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var nameText by remember(config.rtpMidiName) { mutableStateOf(config.rtpMidiName) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = { Text("セッション名") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Badge, contentDescription = null) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val name = nameText.trim().ifEmpty { "Pianotype Android" }
                        config = config.copy(rtpMidiName = name)
                        ConfigRepository.update(context, config)
                    }) { Text("適用") }
                }

                var chExpanded by remember { mutableStateOf(false) }
                Box {
                    SettingsRow(
                        icon = Icons.Filled.MusicNote,
                        title = "送信チャンネル",
                        subtitle = config.rtpMidiChannel?.let { "すべて CH $it で送る" }
                            ?: "受信したチャンネルのまま送る",
                        onClick = { chExpanded = true },
                        trailing = {
                            Text(
                                config.rtpMidiChannel?.let { "CH $it" } ?: "そのまま",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                    )
                    DropdownMenu(expanded = chExpanded, onDismissRequest = { chExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("受信チャンネルのまま") },
                            onClick = {
                                config = config.copy(rtpMidiChannel = null)
                                ConfigRepository.update(context, config)
                                chExpanded = false
                            }
                        )
                        (1..16).forEach { ch ->
                            DropdownMenuItem(
                                text = { Text("CH $ch") },
                                onClick = {
                                    config = config.copy(rtpMidiChannel = ch)
                                    ConfigRepository.update(context, config)
                                    chExpanded = false
                                }
                            )
                        }
                    }
                }

                Text(
                    "接続先やポートの設定はメイン画面の「RTP MIDI 送信」で行います。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------- 設定ファイル ----------------
        SectionHeader(Icons.Filled.Folder, "設定ファイル")
        Card(Modifier.fillMaxWidth()) {
            Column {
                SettingsRow(
                    icon = Icons.Filled.Code,
                    title = "TOML を表示 / 編集",
                    subtitle = "PC 版 pianotype と相互に貼り付け可能",
                    onClick = { showTomlDialog = true },
                    trailing = { Icon(Chevron, contentDescription = null) },
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Filled.RestartAlt,
                    title = "おすすめ設定に戻す",
                    subtitle = "マッピングを含めすべて既定に戻します",
                    iconTint = MaterialTheme.colorScheme.error,
                    onClick = { showResetConfirm = true },
                )
            }
        }

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
}
