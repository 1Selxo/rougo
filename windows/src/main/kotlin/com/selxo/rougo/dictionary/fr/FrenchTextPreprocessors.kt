package com.selxo.rougo.dictionary.fr

object FrenchTextPreprocessors {

    private val apostrophePrefixes = setOf(
        "c",
        "d",
        "j",
        "l",
        "m",
        "n",
        "qu",
        "s",
        "t",
    )

    fun apostropheVariants(text: String): List<String> = listOf(
        text,
        text.replace('\'', '\u2019'),
        text.replace('\u2019', '\''),
    )

    fun decapitalize(text: String): List<String> {
        val lower = text.lowercase()
        return if (lower != text) listOf(text, lower) else listOf(text)
    }

    fun allVariants(text: String): List<String> {
        return decapitalize(text)
            .flatMap { apostropheVariants(it) }
            .flatMap { variant -> listOfNotNull(variant, apostropheTailVariant(variant)) }
            .distinct()
    }

    private fun apostropheTailVariant(text: String): String? {
        val apostropheIndex = text.indexOfAny(charArrayOf('\'', '\u2019'))
        if (apostropheIndex <= 0 || apostropheIndex == text.lastIndex) return null

        val prefix = text.take(apostropheIndex).lowercase()
        return if (prefix in apostrophePrefixes) text.substring(apostropheIndex + 1) else null
    }
}
