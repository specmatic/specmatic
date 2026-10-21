package io.specmatic.core.pattern.regex

import dk.brics.automaton.Automaton
import dk.brics.automaton.RegExp
import io.specmatic.core.pattern.RegexMatchMode
import io.specmatic.core.utilities.Flags

internal sealed interface RegexLookaroundCompilation {
    data object NotApplicable : RegexLookaroundCompilation
    data class Unsupported(val reason: String) : RegexLookaroundCompilation
    data class Compiled(val automaton: Automaton) : RegexLookaroundCompilation
}

internal class RegexLookaroundCompiler(private val matchMode: RegexMatchMode, private val cleanRegularContent: (String) -> String) {
    private val branchLimit = Flags.getStringValue(Flags.SPECMATIC_REGEX_LOOKAROUND_BRANCH_BUDGET)
        ?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_BRANCH_BUDGET

    fun compile(regex: String): RegexLookaroundCompilation {
        val scan = RegexScanner().scan(regex)
        if (scan.tokens.none { it is RegexToken.GroupStart.Lookaround }) {
            return RegexLookaroundCompilation.NotApplicable
        }

        return compileScanned(regex, scan, BranchBudget(branchLimit))
    }

    private fun compileScanned(regex: String, scan: RegexScanner.Result, budget: BranchBudget): RegexLookaroundCompilation {
        val scanned = when (scan) {
            is RegexScanner.Result.Scanned -> scan
            is RegexScanner.Result.Malformed -> return RegexLookaroundCompilation.Unsupported(scan.reason)
        }

        RegexLookaroundSupport(regex, scanned).unsupportedReason()?.let {
            return RegexLookaroundCompilation.Unsupported(it)
        }

        val branches = RegexLookaroundBranches().split(regex, scanned)
        if (branches.size > 1) {
            return compileBranches(branches, budget)
        }

        if (!budget.tryConsume()) {
            return RegexLookaroundCompilation.Unsupported(
                reason = """
                Lookaround alternative expansion exceeds the branch budget of $branchLimit;
                increase ${Flags.SPECMATIC_REGEX_LOOKAROUND_BRANCH_BUDGET} to allow more branches
                """.trimIndent()
            )
        }

        return when (val extracted = RegexLookaroundExtractor().extract(regex, scanned)) {
            is RegexLookaroundExtraction.Extracted -> compileExtracted(extracted)
            is RegexLookaroundExtraction.Unsupported -> RegexLookaroundCompilation.Unsupported(extracted.reason)
        }
    }

    private fun compileBranches(branches: List<String>, budget: BranchBudget): RegexLookaroundCompilation {
        val automata = mutableListOf<Automaton>()
        for (branch in branches) {
            val result = compileScanned(branch, RegexScanner().scan(branch), budget)
            if (result !is RegexLookaroundCompilation.Compiled) return result
            automata += result.automaton
        }

        return RegexLookaroundCompilation.Compiled(Automaton.union(automata))
    }

    private fun compileExtracted(pattern: RegexLookaroundExtraction.Extracted): RegexLookaroundCompilation {
        return try {
            // Strip capture prefixes before examining anchors: $ in (?<$id>a) belongs to the name.
            val cleanedExpression = cleanRegularContent(pattern.expression)
            val anchors = OpenApiRegexAnchorNormalizer(
                anyCharacterSequence = "[\u0000-\u007F]*",
                allowUnanchoredSides = matchMode == RegexMatchMode.SEARCH,
            ).normalize(cleanedExpression)

            if (anchors is OpenApiRegexAnchorNormalizer.Result.Unsupported) {
                return RegexLookaroundCompilation.Unsupported(anchors.reason)
            }

            val asciiStrings = Automaton.makeCharRange('\u0000', '\u007F').repeat()
            val main = compileMain(anchors, asciiStrings)
            val constrained = constrainAssertions(main, pattern.assertions, asciiStrings)
            RegexLookaroundCompilation.Compiled(eraseMarkers(constrained, pattern.assertions))
        } catch (e: IllegalArgumentException) {
            RegexLookaroundCompilation.Unsupported(e.message ?: "Could not compile regular content with dk.brics")
        }
    }

    private fun compileMain(anchors: OpenApiRegexAnchorNormalizer.Result, asciiStrings: Automaton): Automaton {
        val consuming = compileRegular(anchors.regex)
        if (matchMode != RegexMatchMode.SEARCH || anchors !is OpenApiRegexAnchorNormalizer.Result.Unchanged) return consuming
        return asciiStrings.concatenate(consuming).concatenate(asciiStrings)
    }

    private fun constrainAssertions(main: Automaton, assertions: List<MarkedAssertion>, asciiStrings: Automaton): Automaton {
        val markerStrings = Automaton.makeCharSet(assertions.map { it.marker }.joinToString("")).repeat()
        var language = main.intersection(markerSequence(assertions, asciiStrings))
        for (assertion in assertions) {
            val condition = assertionCondition(assertion, asciiStrings, markerStrings)
            language = when (assertion.lookaround.polarity) {
                LookaroundPolarity.NEGATIVE -> language.minus(condition)
                LookaroundPolarity.POSITIVE -> language.intersection(condition)
            }
        }

        return language
    }

    private fun eraseMarkers(language: Automaton, assertions: List<MarkedAssertion>): Automaton {
        val unmarked = assertions.fold(language) { automaton, assertion -> automaton.subst(assertion.marker, "") }
        unmarked.determinize()
        unmarked.minimize()
        return unmarked
    }

    private fun markerSequence(assertions: List<MarkedAssertion>, asciiStrings: Automaton): Automaton {
        // Exactly one occurrence per marker also prevents consuming dots/classes from swallowing markers.
        return assertions.fold(asciiStrings) { sequence, assertion ->
            sequence.concatenate(Automaton.makeChar(assertion.marker)).concatenate(asciiStrings)
        }
    }

    private fun assertionCondition(assertion: MarkedAssertion, asciiStrings: Automaton, markerStrings: Automaton): Automaton {
        // No search normalisation here: a body must match immediately next to its marker.
        // It consumes only input characters, skipping other zero-width assertion markers.
        val body = compileRegular(cleanRegularContent(assertion.body)).intersection(asciiStrings).shuffle(markerStrings)
        val marker = Automaton.makeChar(assertion.marker)
        val local = when (assertion.lookaround) {
            is RegexToken.GroupStart.Lookaround.Ahead -> marker.concatenate(body)
            is RegexToken.GroupStart.Lookaround.Behind -> body.concatenate(marker)
        }

        return Automaton.makeAnyString().concatenate(local).concatenate(Automaton.makeAnyString())
    }

    private fun compileRegular(regex: String): Automaton {
        return RegExp(regex, 0).toAutomaton()
    }

    // Shared only within one compilation. Splits are free; every final route consumes one unit.
    private class BranchBudget(private var remaining: Int) {
        fun tryConsume(): Boolean {
            if (remaining == 0) return false
            remaining--
            return true
        }
    }

    private companion object {
        const val DEFAULT_BRANCH_BUDGET = 64
    }
}
