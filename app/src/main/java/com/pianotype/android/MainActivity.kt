package com.pianotype.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pianotype.android.config.AppPreferences
import com.pianotype.android.config.ConfigRepository
import com.pianotype.android.rtpmidi.RtpMidiService
import com.pianotype.android.ui.MainScreen
import com.pianotype.android.ui.PianotypeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigRepository.load(this)
        // RTP MIDI 送信が有効なら常駐サービスを起動
        if (ConfigRepository.current.rtpMidiEnabled) {
            RtpMidiService.start(this)
        }
        setContent {
            val dark = isSystemInDarkTheme()

            var themeColor by remember {
                mutableStateOf(Color(AppPreferences.getThemeColor(this)))
            }
            var bgColor by remember(dark) {
                mutableStateOf(Color(AppPreferences.getBackgroundColor(this, dark)))
            }

            PianotypeTheme(primary = themeColor, background = bgColor) {
                Surface(modifier = Modifier.fillMaxSize(), color = bgColor) {
                    MainScreen(
                        context = this,
                        themeColor = themeColor,
                        bgColor = bgColor,
                        onThemeColorChange = { c ->
                            themeColor = c
                            AppPreferences.setThemeColor(this, c.toArgb())
                        },
                        onBgColorChange = { c ->
                            bgColor = c
                            AppPreferences.setBackgroundColor(this, c.toArgb())
                        },
                        onOpenImeSettings = { openImeSettings() },
                    )
                }
            }
        }
    }

    private fun openImeSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    @Suppress("unused")
    private fun showImePicker(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.showInputMethodPicker()
    }
}