package com.pianotype.android.ime

import jp.co.omronsoft.openwnn.OpenWnnDictionaryImpl
import jp.co.omronsoft.openwnn.JAJP.OpenWnnClauseConverterJAJP
import jp.co.omronsoft.openwnn.WnnClause

/**
 * 音節 (かな) → 漢字変換エンジン実体。
 *
 * AOSP OpenWnn (Apache License 2.0 / OMRON SOFTWARE Co., Ltd.) の
 * 辞書エンジン (jp.co.omronsoft.openwnn パッケージ + libwnndict.so) を利用して
 * 平仮名列を連文節変換する。
 */
class WnnKanjiEngine : KanjiConverter {

    private var dict: OpenWnnDictionaryImpl? = null
    private var converter: OpenWnnClauseConverterJAJP? = null

    /** ネイティブ辞書がロード済みなら true。失敗したら IME は平仮名のみ入力にフォールバックする */
    override val ready: Boolean
        get() = converter != null

    /**
     * エンジンを初期化する。日本語辞書のロードに数百msかかるため、
     * 呼び出し側はワーカースレッドから 1 度だけ呼ぶ。
     */
    fun start() {
        if (converter != null) return
        try {
            // dicLibPath = null → 辞書データは libwnndict.so に静的リンクされている
            val d = OpenWnnDictionaryImpl(null, null)
            val conv = OpenWnnClauseConverterJAJP()
            conv.setDictionary(d) // 接続行列・品詞タグの取得
            dict = d
            converter = conv
        } catch (t: Throwable) {
            dict = null
            converter = null
        }
    }

    /**
     * 平仮名の列を連文節変換し、文節ごとの候補リストを返す。
     * 失敗時は null (呼び出し側は平仮名のまま扱う)。
     */
    override fun convertBest(input: String): List<KanjiClause>? {
        if (!ready || input.isEmpty()) return null
        val conv = converter!!

        val sentence = try {
            conv.consecutiveClauseConvert(input)
        } catch (t: Throwable) {
            null
        } ?: return null

        @Suppress("UNCHECKED_CAST")
        val elements = sentence.elements as? java.util.ArrayList<WnnClause>
        if (elements.isNullOrEmpty()) {
            return listOf(KanjiClause(input, listOf(input, input, input)))
        }

        return elements
            .filter { it.stroke.isNotEmpty() }
            .map { clause ->
                val candidates = clauseCandidates(clause.stroke)
                KanjiClause(clause.stroke, if (candidates.isNullOrEmpty()) listOf(clause.stroke) else candidates)
            }
    }

    /** 1 文節ぶんの候補を列挙する。辞書に無い語でも「読みそのもの」は最低限返る */
    private fun clauseCandidates(stroke: String): List<String>? {
        val conv = converter!!
        val out = ArrayList<String>(32)
        try {
            val it = conv.convert(stroke) ?: return null
            while (it.hasNext() && out.size < MAX_CANDIDATES_PER_CLAUSE) {
                val clause = it.next() as WnnClause
                val candidate = clause.candidate
                if (candidate.isNotEmpty() && candidate !in out) out.add(candidate)
            }
        } catch (t: Throwable) {
            return if (out.isNotEmpty()) out else null
        }
        return if (out.isEmpty()) listOf(stroke) else out
    }

    /** (任意) リソース解放 */
    fun close() {
        dict = null
        converter = null
    }

    companion object {
        /** 1 文節あたりの最大候補数 */
        const val MAX_CANDIDATES_PER_CLAUSE = 50
    }
}
