package com.selxo.rougo

import com.selxo.rougo.dictionary.de.GermanDeinflector
import com.selxo.rougo.dictionary.fr.FrenchDeinflector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryLookupTest {

    @Test
    fun frenchAdverbDeinflectsToAdjectiveCandidates() {
        val candidates = FrenchDeinflector.preProcess("régulièrement")
            .flatMap { FrenchDeinflector.deinflect(it, "fr") }
            .map { it.text }

        assertTrue(candidates.contains("régulière"))
        assertTrue(candidates.contains("régulier"))
    }

    @Test
    fun subtitleTapExtractsWholeNonCjkWord() {
        val text = "Elle parle régulièrement."
        val offset = text.indexOf("régulièrement") + 4

        assertEquals("régulièrement", extractDictionaryLookupText(text, offset))
    }

    @Test
    fun germanSubtitleTapIncludesRightContextForSeparablePrefix() {
        val text = "Er hilft schon viel mit."
        val offset = text.indexOf("hilft") + 2

        assertEquals("hilft schon viel mit", extractDictionaryLookupText(text, offset, "de"))
    }

    @Test
    fun germanSeparatedPrefixDeinflectsToCombinedInfinitive() {
        val candidates = GermanDeinflector.preProcess("hilft schon viel mit")
            .flatMap { GermanDeinflector.deinflect(it, "de") }
            .map { it.text }

        assertTrue(candidates.contains("hilft mit"))
        assertTrue(candidates.contains("mithelfen"))
    }

    @Test
    fun subtitleTapKeepsCjkChunkLookup() {
        val text = "漢字かな交じり文です"

        assertEquals("漢字かな交じり文", extractDictionaryLookupText(text, 0))
    }
}
