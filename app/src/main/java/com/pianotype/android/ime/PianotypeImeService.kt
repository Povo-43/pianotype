package com.pianotype.android.ime

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.pianotype.android.config.Config
import com.pianotype.android.config.ConfigRepository
import com.pianotype.android.keyboard.OutputAction
import com.pianotype.android.keyboard.ValueParser
import com.pianotype.android.midi.MidiEvent
import com.pianotype.android.midi.MidiListener

class PianotypeImeService : InputMethodService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var midiListener: MidiListener? = null
    private var engine: MappingEngine? = null
    private var statusView: TextView? = null

    private val configListener: (Config) -> Unit = { cfg ->
        mainHandler.post {
            // モード切替などでマッピングが変わったので、押下状態を作り直す。
            engine?.reset()
            restartMidi(cfg)
            updateStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // ★追加: IMEウィンドウ自体の背景を透明にする（白い帯を消す）
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        ConfigRepository.load(this)
        engine = MappingEngine(
            configProvider = { ConfigRepository.current },
            onToggle = { _ ->
                mainHandler.post { updateStatus() }
            },
            onShiftArmed = {
                mainHandler.post { showShiftArmed() }
            },
            onAction = { value, shift ->
                mainHandler.post { handleAction(value, shift) }
            },
        )
        ConfigRepository.addListener(configListener)
    }

    /** 画面を覆わない */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val tv = TextView(this).apply {
            text = "Pianotype: OFF"
            textSize = 13f
            setPadding(32, 16, 32, 16)
            gravity = Gravity.CENTER
            // ★追加: 背景が暗くなるのでテキストを明るいグレーに
            setTextColor(0xFFAAAAAA.toInt())
        }
        statusView = tv

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // ★追加: 背景色を指定して白い帯を消す
            // ダークテーマに合わせる場合は 0xFF1F1F1F（濃いグレー）などを指定
            // 完全に透明にして後ろのアプリを見せたい場合は Color.TRANSPARENT を指定
            setBackgroundColor(0xFF1F1F1F.toInt())
            
            addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        updateStatus()
        return row
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        engine?.reset()
        restartMidi(ConfigRepository.current)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        midiListener?.close()
        midiListener = null
    }

    override fun onDestroy() {
        ConfigRepository.removeListener(configListener)
        midiListener?.close()
        midiListener = null
        super.onDestroy()
    }

    private fun restartMidi(cfg: Config) {
        midiListener?.close()
        midiListener = MidiListener(this, cfg.midiDevice, cfg.midiChannel) { event ->
            val e = engine ?: return@MidiListener
            when (event) {
                is MidiEvent.NoteOn -> e.onNoteOn(event.note)
                is MidiEvent.NoteOff -> e.onNoteOff(event.note)
            }
        }.also { it.start() }
    }

    private fun updateStatus() {
        val enabled = engine?.isEnabled() == true
        statusView?.apply {
            text = "Pianotype: ${if (enabled) "ON" else "OFF"} / ${modeLabel()}"
            // 背景が暗いので、ONの時は明るい緑、OFFの時は明るいグレー
            setTextColor(if (enabled) 0xFF4CAF50.toInt() else 0xFFAAAAAA.toInt())
        }
    }

    private fun showShiftArmed() {
        val enabled = engine?.isEnabled() == true
        statusView?.apply {
            text = "Pianotype: ${if (enabled) "ON" else "OFF"} / SHIFT / ${modeLabel()}"
            setTextColor(0xFF4CAF50.toInt())
        }
    }

    private fun modeLabel(): String =
        if (ConfigRepository.current.kanaMode) "かな" else "英数"

    private fun handleAction(value: String, shift: Boolean) {
        val ic = currentInputConnection ?: return
        val action = ValueParser.parse(value, shift) ?: return
        when (action) {
            is OutputAction.Text -> {
                ic.commitText(action.text, 1)
            }
            is OutputAction.Key -> {
                val meta = buildMetaState(action.ctrl, action.alt, action.shift)
                val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, action.keyCode, 0, meta)
                val up = KeyEvent(0, 0, KeyEvent.ACTION_UP, action.keyCode, 0, meta)
                ic.sendKeyEvent(down)
                ic.sendKeyEvent(up)
            }
        }
    }

    private fun buildMetaState(ctrl: Boolean, alt: Boolean, shift: Boolean): Int {
        var m = 0
        if (ctrl) m = m or KeyEvent.META_CTRL_ON
        if (alt) m = m or KeyEvent.META_ALT_ON
        if (shift) m = m or KeyEvent.META_SHIFT_ON
        return m
    }
}