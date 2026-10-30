package com.pianotype.android.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** KanaComposition (かなモード composing 状態機) の JVM 単体テスト */
class KanaCompositionTest {

    /** テスト用の簡易変換器: 「きょう」+ 残り を 2 文節に分けた固定の文節を返す */
    private class FakeConverter : KanjiConverter {
        private var _ready = true

        override val ready: Boolean
            get() = _ready

        // きょう → [今日, 経要], は → [羽, は]
        override fun convertBest(hiragana: String): List<KanjiClause>? {
            return if (hiragana.startsWith("きょう")) {
                listOf(
                    KanjiClause("きょう", listOf("今日", "経要")),
                    KanjiClause(hiragana.drop(3), listOf("羽", "は")),
                )
            } else {
                listOf(KanjiClause(hiragana, listOf(hiragana)))
            }
        }
    }

    private lateinit var comp: KanaComposition

    @Before
    fun setup() {
        comp = KanaComposition(FakeConverter())
    }

    // ---------- 未変換状態 ----------

    @Test
    fun appendKana_displayKana() {
        comp.appendKana("きょうは")
        assertFalse(comp.isConverted)
        assertEquals("きょうは", comp.displayingText)
    }

    @Test
    fun convert_bestSentence() {
        comp.appendKana("きょうは")
        assertTrue(comp.convert())
        assertTrue(comp.isConverted)
        // 各文節の 0 番 (最良) 候補を連結
        assertEquals("今日羽", comp.displayingText)
        assertEquals(2, comp.clauseCount)
    }

    @Test
    fun backspace_dropsLastChar_beforeConversion() {
        comp.appendKana("ねこ")
        assertTrue(comp.dropLastChar())
        assertEquals("ね", comp.displayingText)
    }

    // ---------- 変換済み状態 ----------

    @Test
    fun space_cyclesCandidates_thenMovesFocusLeft() {
        comp.appendKana("きょうは")
        comp.convert()
        // 対象 = 「は」 (末尾文節), 候補[羽, は]
        assertEquals("今日羽", comp.displayingText)
        comp.cycleOrConvert()                    // は → 1 番 (羽 から は へ)
        assertEquals("今日は", comp.displayingText)
        comp.cycleOrConvert()                    // は を一周 → 最良に戻して きょう に移動
        assertEquals(0, comp.focusIndex)
        assertEquals("今日羽", comp.displayingText)
        comp.cycleOrConvert()                    // きょう → 1 番 (経要)
        assertEquals("経要羽", comp.displayingText)
    }

    @Test
    fun arrows_moveFocusBetweenClauses() {
        comp.appendKana("きょうは")
        comp.convert()
        assertEquals(1, comp.focusIndex)         // 末尾の文節が対象
        assertTrue(comp.moveFocus(true))         // RIGHT → 先頭へ (循環)
        assertEquals(0, comp.focusIndex)
        assertTrue(comp.moveFocus(false))        // LEFT → 末尾へ (循環)
        assertEquals(1, comp.focusIndex)
    }

    @Test
    fun backspace_onConvertedState_returnToKana() {
        comp.appendKana("きょうは")
        comp.convert()
        assertTrue(comp.undoConversion())
        assertFalse(comp.isConverted)
        // 読み (平仮名) そのままに戻る
        assertEquals("きょうは", comp.displayingText)
        assertTrue(comp.dropLastChar())
        assertEquals("きょう", comp.displayingText)
    }

    // ---------- エンジンが使えないとき ----------

    @Test
    fun engineUnavailable_convertFails() {
        comp = KanaComposition(object : KanjiConverter {
            override val ready: Boolean get() = false
            override fun convertBest(hiragana: String): List<KanjiClause>? = null
        })
        comp.appendKana("ねこ")
        assertFalse(comp.cycleOrConvert())       // 変換失敗
        assertFalse(comp.isConverted)
        assertEquals("ねこ", comp.displayingText) // 平仮名のまま
    }
}
