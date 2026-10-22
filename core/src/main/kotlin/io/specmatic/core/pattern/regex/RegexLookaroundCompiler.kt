package io.specmatic.core.pattern.regex

import dk.brics.automaton.Automaton
import dk.brics.automaton.RegExp
import io.specmatic.core.pattern.RegexMatchMode

internal sealed interface RegexLookaroundCompilation {
    data object NotApplicable : RegexLookaroundCompilation
    data class Unsupported(val reason: String) : RegexLookaroundCompilation
    data class Compiled(val automaton: Automaton) : RegexLookaroundCompilation
}

internal class RegexLookaroundCompiler(private val matchMode: RegexMatchMode, private val cleanRegularContent: (String) -> String) {
    fun compile(regex: String): RegexLookaroundCompilation {
        val scan = RegexScanner().scan(regex)
        if (scan.tokens.none { it is RegexToken.GroupStart.Lookaround }) {
            return RegexLookaroundCompilation.NotApplicable
        }

        return compileScanned(regex, scan)
    }

    private fun compileScanned(regex: String, scan: RegexScanner.Result): RegexLookaroundCompilation {
        val scanned = when (scan) {
            is RegexScanner.Result.Scanned -> scan
            is RegexScanner.Result.Malformed -> return RegexLookaroundCompilation.Unsupported(scan.reason)
        }

        RegexLookaroundSupport(regex, scanned).unsupportedReason()?.let {
            return RegexLookaroundCompilation.Unsupported(it)
        }

        val branches = RegexLookaroundBranches().split(regex, scanned)
        if (branches.size > 1) return compileBranches(branches)

        return when (val extracted = RegexLookaroundExtractor().extract(regex, scanned)) {
            is RegexLookaroundExtraction.Extracted -> compileExtracted(extracted)
            is RegexLookaroundExtraction.Unsupported -> RegexLookaroundCompilation.Unsupported(extracted.reason)
        }
    }

    private fun compileBranches(branches: List<String>): RegexLookaroundCompilation {
        var language = Automaton.makeEmpty()

        for (branch in branches) {
            val result = compileScanned(branch, RegexScanner().scan(branch))
            if (result !is RegexLookaroundCompilation.Compiled) return result
            language = language.union(result.automaton)
            language.minimize()
        }

        return RegexLookaroundCompilation.Compiled(language)
    }

    private fun compileExtracted(pattern: RegexLookaroundExtraction.Extracted): RegexLookaroundCompilation {
        return try {
            // Strip capture prefixes before examining anchors: $ in (?<$id>a) belongs to the name.
            val cleanedExpression = cleanRegularContent(pattern.expression)
            val alphabet = RegexCandidateAlphabet(pattern.assertions)
            val anchors = OpenApiRegexAnchorNormalizer(
                anyCharacterSequence = alphabet.contextRegex,
                allowUnanchoredSides = matchMode == RegexMatchMode.SEARCH,
            ).normalize(cleanedExpression)

            if (anchors is OpenApiRegexAnchorNormalizer.Result.Unsupported) {
                return RegexLookaroundCompilation.Unsupported(anchors.reason)
            }

            compileNormalized(pattern, anchors, alphabet)
        } catch (e: IllegalArgumentException) {
            RegexLookaroundCompilation.Unsupported(e.message ?: "Could not compile regular content with dk.brics")
        }
    }

    private fun compileNormalized(pattern: RegexLookaroundExtraction.Extracted, anchors: OpenApiRegexAnchorNormalizer.Result, alphabet: RegexCandidateAlphabet): RegexLookaroundCompilation {
        val main = compileMain(anchors, alphabet)
        val constrained = constrainAssertions(main, pattern.assertions, alphabet)
        return RegexLookaroundCompilation.Compiled(eraseMarkers(constrained, pattern.assertions))
    }

    private fun compileMain(anchors: OpenApiRegexAnchorNormalizer.Result, alphabet: RegexCandidateAlphabet): Automaton {
        val consuming = RegexMarkedAutomaton(alphabet.character).compile(anchors.regex)
        if (matchMode != RegexMatchMode.SEARCH || anchors !is OpenApiRegexAnchorNormalizer.Result.Unchanged) return consuming
        return alphabet.strings.concatenate(consuming).concatenate(alphabet.strings)
    }

    private fun constrainAssertions(main: Automaton, assertions: List<MarkedAssertion>, alphabet: RegexCandidateAlphabet): Automaton {
        var language = main
        language.minimize()

        for (assertion in assertions) {
            val condition = assertionCondition(assertion, alphabet)
            language = when (assertion.lookaround.polarity) {
                LookaroundPolarity.NEGATIVE -> language.minus(condition)
                LookaroundPolarity.POSITIVE -> {
                    language
                        .intersection(condition)
                        .union(language.minus(markerPresence(assertion.marker)))
                }
            }

            language.minimize()
        }

        return language
    }

    private fun markerPresence(marker: Char): Automaton {
        return Automaton.makeAnyString().concatenate(Automaton.makeChar(marker)).concatenate(Automaton.makeAnyString())
    }

    private fun eraseMarkers(language: Automaton, assertions: List<MarkedAssertion>): Automaton {
        val unmarked = assertions.fold(language) { automaton, assertion -> automaton.subst(assertion.marker, "") }
        unmarked.determinize()
        unmarked.minimize()
        return unmarked
    }

    private fun assertionCondition(assertion: MarkedAssertion, alphabet: RegexCandidateAlphabet): Automaton {
        val body = compileRegular(cleanRegularContent(assertion.body))
            .intersection(alphabet.strings)
            .shuffle(alphabet.otherMarkerStrings(assertion.marker))

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
}
