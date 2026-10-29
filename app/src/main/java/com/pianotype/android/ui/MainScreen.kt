package com.pianotype.android.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private sealed class Page(val label: String, val icon: ImageVector) {
    data object Settings : Page("設定", Icons.Default.Settings)
    data object Advanced : Page("詳細設定", Icons.Default.Tune)
    data object About : Page("このアプリについて", Icons.Default.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    context: Context,
    themeColor: Color,
    bgColor: Color,
    onThemeColorChange: (Color) -> Unit,
    onBgColorChange: (Color) -> Unit,
    onOpenImeSettings: () -> Unit,
) {
    var current by remember { mutableStateOf<Page>(Page.Settings) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 詳細設定中はシステムバックでメイン設定へ戻る
    BackHandler(enabled = current === Page.Advanced) { current = Page.Settings }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Pianotype",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                DrawerItem(
                    Page.Settings.label, Page.Settings.icon,
                    selected = current === Page.Settings || current === Page.Advanced,
                ) {
                    current = Page.Settings
                    scope.launch { drawerState.close() }
                }
                DrawerItem(Page.About.label, Page.About.icon, current === Page.About) {
                    current = Page.About
                    scope.launch { drawerState.close() }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (current) {
                                is Page.Settings -> "Pianotype 設定"
                                is Page.Advanced -> "詳細設定"
                                is Page.About -> "このアプリについて"
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (current === Page.Advanced) {
                                current = Page.Settings
                            } else {
                                scope.launch { drawerState.open() }
                            }
                        }) {
                            Icon(
                                if (current === Page.Advanced) Icons.AutoMirrored.Filled.ArrowBack
                                else Icons.Default.Menu,
                                contentDescription = if (current === Page.Advanced) "戻る" else "メニュー",
                            )
                        }
                    },
                    actions = {
                        if (current === Page.Settings) {
                            TextButton(onClick = onOpenImeSettings) {
                                Icon(Icons.Default.Keyboard, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("IME設定")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                when (current) {
                    is Page.Settings -> SettingsContent(
                        context = context,
                        themeColor = themeColor,
                        bgColor = bgColor,
                        onThemeColorChange = onThemeColorChange,
                        onBgColorChange = onBgColorChange,
                        onOpenAdvanced = { current = Page.Advanced },
                    )
                    is Page.Advanced -> AdvancedSettingsContent(
                        context = context,
                        themeColor = themeColor,
                        bgColor = bgColor,
                        onThemeColorChange = onThemeColorChange,
                        onBgColorChange = onBgColorChange,
                    )
                    is Page.About -> AboutContent()
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    )
}
