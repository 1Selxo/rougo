package com.selxo.rougo

import com.selxo.rougo.dictionary.de.GermanDeinflector
import com.selxo.rougo.dictionary.fr.FrenchDeinflector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun frenchContractionDeinflectsToEtre() {
        val straightCandidates = FrenchDeinflector.preProcess("c'est")
            .flatMap { FrenchDeinflector.deinflect(it, "fr") }
            .map { it.text }
        val curlyCandidates = FrenchDeinflector.preProcess("c’est")
            .flatMap { FrenchDeinflector.deinflect(it, "fr") }
            .map { it.text }

        assertTrue(straightCandidates.contains("est"))
        assertTrue(straightCandidates.contains("être"))
        assertTrue(curlyCandidates.contains("être"))
    }

    @Test
    fun frenchPastParticiplesDeinflectToInfinitiveCandidates() {
        val motivatedCandidates = FrenchDeinflector.preProcess("motivé")
            .flatMap { FrenchDeinflector.deinflect(it, "fr") }
            .map { it.text }
        val motivatedAgreementCandidates = FrenchDeinflector.preProcess("motivées")
            .flatMap { FrenchDeinflector.deinflect(it, "fr") }
            .map { it.text }
        val understoodCandidates = FrenchDeinflector.preProcess("compris")
            .flatMap { FrenchDeinflector.deinflect(it, "fr") }
            .map { it.text }

        assertTrue(motivatedCandidates.contains("motiver"))
        assertTrue(motivatedAgreementCandidates.contains("motiver"))
        assertTrue(understoodCandidates.contains("comprendre"))
    }

    @Test
    fun frenchApostropheContractionsAlsoTryTheRightHandWord() {
        val reflexiveCandidates = FrenchDeinflector.preProcess("s'est")
            .flatMap { FrenchDeinflector.deinflect(it, "fr") }
            .map { it.text }
        val articleCandidates = FrenchDeinflector.preProcess("l'autre")
            .flatMap { FrenchDeinflector.deinflect(it, "fr") }
            .map { it.text }
        val lexicalizedCandidates = FrenchDeinflector.preProcess("aujourd'hui")
            .map { it }

        assertTrue(reflexiveCandidates.contains("est"))
        assertTrue(reflexiveCandidates.contains("être"))
        assertTrue(articleCandidates.contains("autre"))
        assertFalse(lexicalizedCandidates.contains("hui"))
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
    fun germanSubtitleTapContinuesPastCommonPeriodAbbreviations() {
        val text = "Er hilft z.T. schon viel mit."
        val offset = text.indexOf("hilft") + 2

        assertEquals("hilft z.T. schon viel mit", extractDictionaryLookupText(text, offset, "de"))
    }

    @Test
    fun germanSubtitleTapContinuesPastSpacedPeriodAbbreviations() {
        val text = "Er hilft z. T. schon viel mit."
        val offset = text.indexOf("hilft") + 2

        assertEquals("hilft z. T. schon viel mit", extractDictionaryLookupText(text, offset, "de"))
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
    fun germanSeparatedPrefixIgnoresPeriodAbbreviationBetweenVerbParts() {
        val candidates = GermanDeinflector.preProcess("hilft z.T. schon viel mit")
            .flatMap { GermanDeinflector.deinflect(it, "de") }
            .map { it.text }

        assertTrue(candidates.contains("hilft mit"))
        assertTrue(candidates.contains("mithelfen"))
    }

    @Test
    fun germanSeparatedPrefixIgnoresSpacedPeriodAbbreviationBetweenVerbParts() {
        val candidates = GermanDeinflector.preProcess("hilft z. T. schon viel mit")
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
