package com.pianotype.android.ime

/** 1 文節ぶんの変換候補 (複数文節変換で集めたもの) */
data class KanjiClause(
    /** 読み (平仮名) */
    val stroke: String,
    /** 変換候補。0 番が最良、末尾に読みそのもの (平仮名) が入る */
    val candidates: List<String>,
    /** 現在選択されている候補の番号 */
    var selectedIndex: Int = 0,
)

/** 漢字変換できなくなったとき (loadModel 失敗時) も IME を動かすための抽象 */
interface KanjiConverter {
    /** 変換エンジンが使用可能か */
    val ready: Boolean

    /**
     * 平仮名の列を連文節変換し、文節ごとの候補一覧を返す。
     * 失敗時は null (呼び出し側は平仮名のまま扱う)。
     */
    fun convertBest(hiragana: String): List<KanjiClause>?
}

/**
 * かなモードの composing 状態を管理するクラス。
 *
 * ・未変換状態 : ローマ字から作った平仮名列を「そのまま」表示 (例: きょう)
 * ・変換済み状態 : 文節ごとに漢字候補を持つ (例: 今日 は いい 天気 です)
 *
 * SPACE で変換・候補切り替え、LEFT/RIGHT で対象文節移動、
 * BACKSPACE で変換解除 → 平仮名 ← の読みに戻す。
 */
class KanaComposition(private val converter: KanjiConverter) {

    /** 未変換の読み (平仮名) */
    private var kana = ""

    /** 変換済みの文節一覧 (null = 未変換) */
    private var clauses: List<KanjiClause>? = null

    /** 候補操作の対象となっている文節の index (-1 = 未変換) */
    private var focus = -1

    /** 変換済みか */
    val isConverted: Boolean
        get() = clauses != null

    /** composing が空か */
    val isEmpty: Boolean
        get() = kana.isEmpty()

    /** 文節数 (未変換時は 0) */
    val clauseCount: Int
        get() = clauses?.size ?: 0

    /** 対象文節の index (未変換時は -1) */
    val focusIndex: Int
        get() = focus

    /** 平仮名 (読み) を composing に積む。変換済みの場合は一度確定してから呼ぶこと */
    fun appendKana(text: String) {
        kana += text
    }

    /** 未変換の読みから末尾 1 文字を削除する */
    fun dropLastChar(): Boolean {
        if (isConverted || kana.isEmpty()) return false
        kana = kana.dropLast(1)
        return true
    }

    /**
     * 未変換の読みを漢字かな混じり文に変換する。
     * エンジンが無い・変換できない場合は false (未変換のまま)。
     */
    fun convert(): Boolean {
        if (isConverted || !converter.ready || kana.isEmpty()) return false
        val result = converter.convertBest(kana) ?: return false
        if (result.isEmpty()) return false
        clauses = result
        focus = result.size - 1
        return true
    }

    /**
     * SPACE: 未変換なら変換する。変換済みなら対象文節の次の候補に切り替え、
     * 候補を一周したら 0 番に戻して対象を左隣の文節へ移す (一般的な IME と同じ挙動)。
     */
    fun cycleOrConvert(): Boolean {
        if (!isConverted) return convert()
        val cs = clauses ?: return false
        val c = cs.getOrNull(focus) ?: return false
        val next = c.selectedIndex + 1
        if (next < c.candidates.size) {
            c.selectedIndex = next
            return true
        }
        // 候補を一周した → 最良候補に戻して対象を左隣の文節へ
        c.selectedIndex = 0
        return moveFocus(false)
    }

    /** 変換済みのとき、候補操作の対象文節を左右に移動する */
    fun moveFocus(forward: Boolean): Boolean {
        val cs = clauses ?: return false
        if (cs.size <= 1) return false
        focus = if (forward) (focus + 1) % cs.size else (focus - 1 + cs.size) % cs.size
        return true
    }

    /** BACKSPACE: 変換済みなら変換をやめて読み (平仮名) に戻す */
    fun undoConversion(): Boolean {
        if (!isConverted) return false
        clauses = null
        focus = -1
        return true
    }

    /** いま画面に表示する (確定するとそのまま送出する) 文字列 */
    val displayingText: String
        get() {
            val cs = clauses ?: return kana
            return cs.joinToString("") { it.displayCandidate() }
        }

    /** 状態をリセット (onStartInput などから呼ぶ) */
    fun reset() {
        kana = ""
        clauses = null
        focus = -1
    }

    private fun KanjiClause.displayCandidate(): String =
        candidates.getOrNull(selectedIndex) ?: stroke
}
