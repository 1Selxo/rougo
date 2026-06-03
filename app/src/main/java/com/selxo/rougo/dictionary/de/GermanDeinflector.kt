package com.selxo.rougo.dictionary.de

import com.selxo.rougo.dictionary.DeinflectionResult
import com.selxo.rougo.dictionary.Deinflector
import com.selxo.rougo.dictionary.Rule
import com.selxo.rougo.dictionary.RuleDeinflector
import com.selxo.rougo.dictionary.prefixInflection
import com.selxo.rougo.dictionary.suffixInflection

object GermanDeinflector : Deinflector {

    private class FiniteVerbRule(
        val inflectedPattern: String,
        val toInfinitive: (String) -> String,
    ) {
        val isInflected = Regex("^$inflectedPattern$")
    }

    override fun preProcess(text: String): List<String> = GermanTextPreprocessors.allVariants(text)

    override fun deinflect(
        text: String,
        languageCode: String,
    ): List<DeinflectionResult> {
        return (deinflector.deinflect(text) + separatedPrefixInfinitives(text).map { DeinflectionResult(it, 0) })
            .distinctBy { it.text }
    }

    private val separablePrefixes = listOf(
        "ab", "an", "auf", "aus", "auseinander", "bei", "da", "dabei", "dar", "daran",
        "dazwischen", "durch", "ein", "empor", "entgegen", "entlang", "entzwei", "fehl",
        "fern", "fest", "fort", "frei", "gegenüber", "gleich", "heim", "her", "herab",
        "heran", "herauf", "heraus", "herbei", "herein", "herüber", "herum", "herunter",
        "hervor", "hin", "hinab", "hinauf", "hinaus", "hinein", "hinterher", "hinunter",
        "hinweg", "hinzu", "hoch", "los", "mit", "nach", "nebenher", "nieder", "statt",
        "um", "vor", "voran", "voraus", "vorbei", "vorüber", "vorweg", "weg", "weiter",
        "wieder", "zu", "zurecht", "zurück", "zusammen",
    )

    private val GERMAN_LETTERS = "a-zA-ZäöüßÄÖÜẞ"
    private val separablePrefixDisjunction = separablePrefixes.joinToString("|") { Regex.escape(it) }
    private val separatedPrefixCandidateRegex = Regex("^([$GERMAN_LETTERS]+)(?: .+)? ($separablePrefixDisjunction)$")

    private val irregularFiniteVerbs = mapOf(
        "bin" to "sein",
        "bist" to "sein",
        "ist" to "sein",
        "sind" to "sein",
        "seid" to "sein",
        "werde" to "werden",
        "wirst" to "werden",
        "wird" to "werden",
        "werdet" to "werden",
        "habe" to "haben",
        "hast" to "haben",
        "hat" to "haben",
        "habt" to "haben",
        "hilfst" to "helfen",
        "hilft" to "helfen",
        "gibst" to "geben",
        "gibt" to "geben",
        "nimmst" to "nehmen",
        "nimmt" to "nehmen",
        "siehst" to "sehen",
        "sieht" to "sehen",
        "liest" to "lesen",
        "sprichst" to "sprechen",
        "spricht" to "sprechen",
        "triffst" to "treffen",
        "trifft" to "treffen",
        "wirfst" to "werfen",
        "wirft" to "werfen",
        "isst" to "essen",
        "esst" to "essen",
        "fährst" to "fahren",
        "fährt" to "fahren",
        "läufst" to "laufen",
        "läuft" to "laufen",
        "hältst" to "halten",
        "hält" to "halten",
        "fällst" to "fallen",
        "fällt" to "fallen",
        "schläfst" to "schlafen",
        "schläft" to "schlafen",
        "trägst" to "tragen",
        "trägt" to "tragen",
        "wäschst" to "waschen",
        "wäscht" to "waschen",
        "lädst" to "laden",
        "lädt" to "laden",
        "rätst" to "raten",
        "rät" to "raten",
        "gräbst" to "graben",
        "gräbt" to "graben",
        "bläst" to "blasen",
        "stößt" to "stoßen",
    )

    private val finiteVerbRules: List<FiniteVerbRule> = buildList {
        irregularFiniteVerbs.forEach { (inflected, infinitive) ->
            add(FiniteVerbRule(Regex.escape(inflected)) { infinitive })
        }

        add(FiniteVerbRule("[$GERMAN_LETTERS]+en") { it })
        add(FiniteVerbRule("[$GERMAN_LETTERS]+eln") { it })
        add(FiniteVerbRule("[$GERMAN_LETTERS]+ern") { it })
        add(FiniteVerbRule("[$GERMAN_LETTERS]+elst") { it.dropLast(2) + "n" })
        add(FiniteVerbRule("[$GERMAN_LETTERS]+elt") { it.dropLast(1) + "n" })
        add(FiniteVerbRule("[$GERMAN_LETTERS]+erst") { it.dropLast(2) + "n" })
        add(FiniteVerbRule("[$GERMAN_LETTERS]+ert") { it.dropLast(1) + "n" })
        add(FiniteVerbRule("[$GERMAN_LETTERS]+est") { it.dropLast(2) + "en" })
        add(FiniteVerbRule("[$GERMAN_LETTERS]+et") { it.dropLast(2) + "en" })
        add(FiniteVerbRule("[$GERMAN_LETTERS]+e") { it.dropLast(1) + "en" })
        add(FiniteVerbRule("[$GERMAN_LETTERS]+st") { it.dropLast(2) + "en" })
        add(FiniteVerbRule("[$GERMAN_LETTERS]+t") { it.dropLast(1) + "en" })
    }

    private val allRules: List<Rule> = buildList {
        // nominalization: -ung -> -en
        add(suffixInflection("ung", "en", emptySet(), setOf("v")))
        add(suffixInflection("lung", "eln", emptySet(), setOf("v")))
        add(suffixInflection("rung", "rn", emptySet(), setOf("v")))

        // -bar: adjective from verb
        add(suffixInflection("bar", "en", setOf("adj"), setOf("v")))
        add(suffixInflection("bar", "n", setOf("adj"), setOf("v")))

        // negative prefix un-
        add(prefixInflection("un", "", emptySet(), setOf("adj")))

        // past participle: ge...t -> ...en/...n
        val basicPastRegex = Regex("^ge([$GERMAN_LETTERS]+)t$")
        for (suffix in listOf("n", "en")) {
            add(Rule.Custom(
                conditionsIn = emptySet(),
                conditionsOut = setOf("vw"),
                isInflected = basicPastRegex,
                deinflectFn = { term -> term.replace(basicPastRegex, "$1$suffix") },
            ))
        }

        // separable past participle: prefix ge...t -> prefix...en/...n
        val prefixDisjunction = separablePrefixes.joinToString("|")
        val separablePastRegex = Regex("^($prefixDisjunction)ge([$GERMAN_LETTERS]+)t$")
        for (suffix in listOf("n", "en")) {
            add(Rule.Custom(
                conditionsIn = emptySet(),
                conditionsOut = setOf("vw"),
                isInflected = separablePastRegex,
                deinflectFn = { term -> term.replace(separablePastRegex, "$1$2$suffix") },
            ))
        }

        // separated prefix: "word ... prefix" -> "word prefix"
        for (prefix in separablePrefixes) {
            val sepRegex = Regex("^([$GERMAN_LETTERS]+) .+ $prefix$")
            add(Rule.Custom(
                conditionsIn = emptySet(),
                conditionsOut = emptySet(),
                isInflected = sepRegex,
                deinflectFn = { term -> term.replace(sepRegex, "$1 $prefix") },
            ))
        }

        // zu-infinitive: prefixzu -> prefix
        for (prefix in separablePrefixes) {
            add(prefixInflection(prefix + "zu", prefix, emptySet(), setOf("v")))
        }

        // -heit/-keit noun suffixes
        add(suffixInflection("heit", "", setOf("n"), setOf("adj", "n")))
        add(suffixInflection("keit", "", setOf("n"), setOf("adj", "n")))
    }

    private val deinflector = RuleDeinflector(allRules)

    private fun separatedPrefixInfinitives(text: String): List<String> {
        val match = separatedPrefixCandidateRegex.matchEntire(text) ?: return emptyList()
        val finiteVerb = match.groupValues[1]
        val prefix = match.groupValues[2]
        return finiteVerbRules
            .asSequence()
            .filter { it.isInflected.matches(finiteVerb) }
            .map { prefix + it.toInfinitive(finiteVerb) }
            .distinct()
            .toList()
    }
}
