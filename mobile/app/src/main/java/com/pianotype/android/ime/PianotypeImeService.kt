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
    /** かな漢字変換エンジン (OpenWnn)。辞書のロードは onCreate でバックグラウンドスレッドを開始 */
    private val kanjiEngine = WnnKanjiEngine()
    /** かなモードの composing 状態 (未変換の平仮名 ↔ 変換済みの文節一覧) */
    private val composing = KanaComposition(kanjiEngine)

    private var midiListener: MidiListener? = null
    private var engine: MappingEngine? = null
    private var statusView: TextView? = null

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

        // かな漢字変換辞書 (OpenWnn) をバックグラウンドでロードする
        Thread {
            kanjiEngine.start()
        }.start()

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
                composing.reset()
                kanaConverter.reset()
                ic.setComposingText("", 1)
                return
            }
            "confirm", "commit" -> {
                commitComposing(ic)
                return
            }
        }

        // 変換済み状態でさらに文字が来た場合は、いったん確定して新しい composing を始める
        if (composing.isConverted) {
            commitComposing(ic)
        }

        // かな文字はそのまま、ローマ字は KanaConverter で変換して composing に積む
        val added = StringBuilder()
        for (ch in text) {
            val kana = kanaConverter.input(ch)
            if (kana.isNotEmpty()) added.append(kana)
        }
        if (added.isEmpty()) return

        composing.appendKana(added.toString())
        updateComposing(ic)
    }

    private fun handleKanaKey(keyCode: Int): Boolean {
        val ic = currentInputConnection ?: return false

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (!composing.isEmpty || composing.isConverted) {
                    commitComposing(ic)
                    return true
                }
            }

            KeyEvent.KEYCODE_DEL -> {
                if (composing.isConverted) {
                    // 変換をやめる (読み = 平仮名へ戻す)
                    composing.undoConversion()
                    kanaConverter.reset()
                    updateComposing(ic)
                    return true
                }
                if (!composing.isEmpty) {
                    composing.dropLastChar()
                    updateComposing(ic)
                    return true
                }
            }

            KeyEvent.KEYCODE_SPACE -> {
                if (!composing.isEmpty) {
                    // 対象文節の移動・候補切り替え
                    if (!composing.isConverted) {
                        // ローマ字の pending も吐き出したうえで連文節変換
                        val flushed = kanaConverter.flush()
                        if (flushed.isNotEmpty()) composing.appendKana(flushed)
                        var committed = false
                        if (composing.convert()) {
                            updateComposing(ic)
                            committed = true
                        }
                        if (!committed) {
                            // エンジン未ロード / 変換失敗 → 従来どおり平仮名のまま確定
                            commitComposing(ic)
                        }
                    } else {
                        // 候補切り替え・対象文節移動
                        composing.cycleOrConvert()
                        updateComposing(ic)
                    }
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (composing.isConverted) {
                    // 変換済み: 対象文節を移動する
                    composing.moveFocus(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    updateComposing(ic)
                    return true
                }
            }
        }

        return false
    }

    /** composing の表示を現状に合わせて更新 */
    private fun updateComposing(ic: InputConnection) {
        if (composing.isEmpty && !composing.isConverted) {
            ic.setComposingText("", 1)
            return
        }
        ic.setComposingText(composing.displayingText, 1)
    }

    private fun commitComposing(ic: InputConnection) {
        // ローマ字の残留 (n など) も忘れず吐き出す
        val flushed = kanaConverter.flush()
        if (flushed.isNotEmpty() && !composing.isConverted) composing.appendKana(flushed)

        if (composing.isEmpty && !composing.isConverted) return
        ic.commitText(composing.displayingText, 1)
        resetComposingState()
    }

    private fun backspaceComposing(ic: InputConnection) {
        if (composing.isConverted) {
            composing.undoConversion()
            kanaConverter.reset()
            updateComposing(ic)
            return
        }
        if (composing.dropLastChar()) updateComposing(ic)
    }

    private fun resetComposingState() {
        composing.reset()
        kanaConverter.reset()
    }

    private fun resetComposing() {
        resetComposingState()
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
