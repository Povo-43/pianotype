package com.pianotype.android.ime

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
    private val kanaConverter = KanaConverter()

    private var midiListener: MidiListener? = null
    private var engine: MappingEngine? = null
    private var statusView: TextView? = null

    // 未確定のかな（composing 中の文字列）
    private var composingKana = ""

    private val configListener: (Config) -> Unit = { cfg ->
        mainHandler.post {
            // 設定画面でのモード切替やマッピング変更に合わせ、押下状態を作り直す
            engine?.reset()
            resetComposing()
            restartMidi(cfg)
            updateStatus(engine?.isEnabled() ?: false)
        }
    }

    override fun onCreate() {
        super.onCreate()

        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        ConfigRepository.load(this)

        engine = MappingEngine(
            configProvider = { ConfigRepository.current },
            onToggle = { enabled ->
                mainHandler.post { updateStatus(enabled) }
            },
            onShiftArmed = {
                mainHandler.post { showShiftArmed() }
            },
            // 「KANA」コマンド: かなモードを反転して永続化する (TOML の kana_mode)
            onKanaToggle = { next ->
                mainHandler.post {
                    val cur = ConfigRepository.current
                    if (cur.kanaMode != next) {
                        resetComposing()
                        ConfigRepository.update(this, cur.copy(kanaMode = next))
                    }
                    updateStatus(engine?.isEnabled() ?: false)
                }
            },
            onAction = { value, shift ->
                mainHandler.post { handleAction(value, shift) }
            }
        )

        ConfigRepository.addListener(configListener)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val tv = TextView(this).apply {
            textSize = 13f
            setPadding(32, 16, 32, 16)
            gravity = Gravity.CENTER
            setTextColor(0xFFAAAAAA.toInt())
        }
        statusView = tv

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF1F1F1F.toInt())
            addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        engine?.let { updateStatus(it.isEnabled()) }

        return row
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        engine?.reset()
        resetComposing()
        restartMidi(ConfigRepository.current)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        midiListener?.close()
        midiListener = null
        resetComposing()
    }

    override fun onDestroy() {
        ConfigRepository.removeListener(configListener)
        midiListener?.close()
        midiListener = null
        super.onDestroy()
    }

    private fun restartMidi(cfg: Config) {
        midiListener?.close()

        midiListener = MidiListener(
            this,
            cfg.midiDevice,
            cfg.midiChannel
        ) { event ->
            val e = engine ?: return@MidiListener
            when (event) {
                is MidiEvent.NoteOn -> e.onNoteOn(event.note)
                is MidiEvent.NoteOff -> e.onNoteOff(event.note)
            }
        }.also { it.start() }
    }

    private fun updateStatus(enabled: Boolean) {
        statusView?.apply {
            text = "Pianotype: ${if (enabled) "ON" else "OFF"} / ${modeLabel()}"
            // 背景が暗いので、ONの時は明るい緑、OFFの時は明るいグレー
            setTextColor(if (enabled) 0xFF4CAF50.toInt() else 0xFFAAAAAA.toInt())
        }
    }

    private fun showShiftArmed() {
        val enabled = engine?.isEnabled() ?: false
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
                if (ConfigRepository.current.kanaMode) {
                    handleKanaText(ic, action.text)
                } else {
                    ic.commitText(action.text, 1)
                }
            }

            is OutputAction.Key -> {
                if (ConfigRepository.current.kanaMode && handleKanaKey(action.keyCode)) return
                sendKeyEvent(ic, action)
            }
        }
    }

    /** KANAモードでの1アクション分の処理 */
    private fun handleKanaText(ic: InputConnection, text: String) {
        if (text.isEmpty()) return

        // 制御系の値は先に処理
        when (text.lowercase()) {
            "backspace" -> {
                backspaceComposing(ic)
                return
            }
            "delete" -> {
                composingKana = ""
                ic.commitText("", 1)
                return
            }
            "confirm", "commit" -> {
                commitComposing(ic)
                return
            }
        }

        // かな文字はそのまま、ローマ字は KanaConverter で変換して composingKana に積む
        val added = StringBuilder()
        for (ch in text) {
            val kana = kanaConverter.input(ch)
            if (kana.isNotEmpty()) added.append(kana)
        }
        if (added.isEmpty()) return

        composingKana += added.toString()
        ic.setComposingText(composingKana, 1)
    }

    private fun handleKanaKey(keyCode: Int): Boolean {
        val ic = currentInputConnection ?: return false

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (composingKana.isNotEmpty()) {
                    commitComposing(ic)
                    return true
                }
            }

            KeyEvent.KEYCODE_DEL -> {
                if (composingKana.isNotEmpty()) {
                    backspaceComposing(ic)
                    return true
                }
            }

            KeyEvent.KEYCODE_SPACE -> {
                if (composingKana.isNotEmpty()) {
                    // とりあえず未変換のかなを確定
                    commitComposing(ic)
                    return true
                }
            }
        }

        return false
    }

    private fun commitComposing(ic: InputConnection) {
        if (composingKana.isEmpty()) return
        ic.commitText(composingKana, 1)
        composingKana = ""
    }

    private fun backspaceComposing(ic: InputConnection) {
        if (composingKana.isEmpty()) return
        composingKana = composingKana.dropLast(1)
        ic.setComposingText(composingKana, 1)
    }

    private fun resetComposing() {
        composingKana = ""
    }

    private fun sendKeyEvent(ic: InputConnection, action: OutputAction.Key) {
        val meta = buildMetaState(action.ctrl, action.alt, action.shift)
        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, action.keyCode, 0, meta)
        val up = KeyEvent(0, 0, KeyEvent.ACTION_UP, action.keyCode, 0, meta)
        ic.sendKeyEvent(down)
        ic.sendKeyEvent(up)
    }

    private fun buildMetaState(ctrl: Boolean, alt: Boolean, shift: Boolean): Int {
        var m = 0
        if (ctrl) m = m or KeyEvent.META_CTRL_ON
        if (alt) m = m or KeyEvent.META_ALT_ON
        if (shift) m = m or KeyEvent.META_SHIFT_ON
        return m
    }
}
