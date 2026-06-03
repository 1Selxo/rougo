package com.selxo.rougo.dictionary

/*
 * Kotlin deinflection model adapted from Chimahon's dictionary package, which ports
 * Yomitan language transforms for non-Japanese dictionary lookup.
 */

/**
 * Result of a single deinflection step.
 * [text] is the candidate dictionary form; [conditionsOut] are the grammar tags
 * that must be satisfied by the next rule in the chain.
 */
data class DeinflectionResult(val text: String, val conditionsOut: Int)

/**
 * A morphological transformation rule.
 * - [Prefix] strips or replaces a leading segment.
 * - [Suffix] strips or replaces a trailing segment.
 * - [WholeWord] replaces an exact form.
 * - [Sandwich] strips or replaces both ends simultaneously.
 */
sealed class Rule {
    abstract val conditionsIn: Set<String>
    abstract val conditionsOut: Set<String>
    abstract val isInflected: Regex

    data class Prefix(
        val inflectedPrefix: String,
        val deinflectedPrefix: String,
        override val conditionsIn: Set<String>,
        override val conditionsOut: Set<String>,
        override val isInflected: Regex,
    ) : Rule()

    data class Suffix(
        val inflectedSuffix: String,
        val deinflectedSuffix: String,
        override val conditionsIn: Set<String>,
        override val conditionsOut: Set<String>,
        override val isInflected: Regex,
    ) : Rule()

    data class WholeWord(
        val inflectedWord: String,
        val deinflectedWord: String,
        override val conditionsIn: Set<String>,
        override val conditionsOut: Set<String>,
        override val isInflected: Regex,
    ) : Rule()

    data class Sandwich(
        val inflectedPrefix: String,
        val deinflectedPrefix: String,
        val inflectedSuffix: String,
        val deinflectedSuffix: String,
        override val conditionsIn: Set<String>,
        override val conditionsOut: Set<String>,
        override val isInflected: Regex,
    ) : Rule()

    /**
     * Custom rule that uses a lambda for deinflection.
     * Used for complex patterns (e.g. German past participles, phrasal verbs).
     */
    data class Custom(
        override val conditionsIn: Set<String>,
        override val conditionsOut: Set<String>,
        override val isInflected: Regex,
        val deinflectFn: (String) -> String,
    ) : Rule()
}

interface Deinflector {
    /** Pre-process the raw query (e.g., normalization, cleaning). */
    fun preProcess(text: String): List<String> = listOf(text)

    /** Generate candidate dictionary forms from the (possibly preprocessed) text. */
    fun deinflect(text: String, languageCode: String): List<DeinflectionResult>
}
