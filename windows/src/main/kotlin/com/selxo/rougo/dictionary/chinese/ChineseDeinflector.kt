package com.selxo.rougo.dictionary.chinese

import com.selxo.rougo.dictionary.DeinflectionResult
import com.selxo.rougo.dictionary.Deinflector

/**
 * Chinese deinflector.
 * Chinese is largely analytic and doesn't have morphological deinflection
 * in the same way Japanese or Arabic do.
 */
object ChineseDeinflector : Deinflector {

    override fun preProcess(text: String): List<String> = ChineseTextPreprocessors.process(text)

    override fun deinflect(
        text: String,
        languageCode: String,
    ): List<DeinflectionResult> {
        return listOf(DeinflectionResult(text, 0))
    }
}
