package io.specmatic.core.pattern.regex

import dk.brics.automaton.Automaton
import io.specmatic.core.pattern.RegExSpec
import io.specmatic.core.pattern.RegexMatchMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

internal class RegexLookaroundCorpusTest {
    private fun compiler() = RegexLookaroundCompiler(RegexMatchMode.SEARCH, RegExSpec(null)::cleanRegularRegexContent)

    @Nested
    inner class SupportedPatterns {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.regex.RegexLookaroundCorpusTest#supportedCases")
        fun `accepts expected candidates and generates matching witnesses`(fixture: RegexConformanceFixture) {
            verifyWitnesses(fixture)
        }
    }

    @Nested
    inner class EmptyLanguages {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.regex.RegexLookaroundCorpusTest#emptyCases")
        fun `compiles successfully without an ASCII witness`(fixture: RegexConformanceFixture) {
            assertThat(compile(fixture.pattern).isEmpty).describedAs(fixture.id).isTrue()
        }
    }

    @Nested
    inner class UnsupportedPatterns {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.regex.RegexLookaroundCorpusTest#unsupportedCases")
        fun `declines compilation with a documented reason`(fixture: RegexConformanceFixture) {
            verifyUnsupportedFixture(fixture)
        }
    }

    @Nested
    inner class DeferredCleanupInaccuracies {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.regex.RegexLookaroundCorpusTest#deferredCases")
        fun `retains the actual compiler outcome of inherited inaccuracies`(fixture: RegexConformanceFixture) {
            verifyDeferredFixture(fixture)
        }
    }

    private fun verifyDeferredFixture(fixture: RegexConformanceFixture) {
        assertThat(fixture.deferredReason).describedAs(fixture.id).isNotBlank()
        when (fixture.generation) {
            "UNSUPPORTED" -> verifyUnsupportedFixture(fixture)
            "SUPPORTED" -> assertThat(compile(fixture.pattern).isEmpty).describedAs(fixture.id).isFalse()
            "NO_WITNESS_IN_GENERATION_DOMAIN" -> assertThat(compile(fixture.pattern).isEmpty).describedAs(fixture.id).isTrue()
            else -> error("${fixture.id}: unexpected deferred disposition ${fixture.generation}")
        }
    }

    private fun verifyUnsupportedFixture(fixture: RegexConformanceFixture) {
        val result = compiler().compile(fixture.pattern)
        assertThat(result).describedAs(fixture.id).isInstanceOf(RegexLookaroundCompilation.Unsupported::class.java)
        result as RegexLookaroundCompilation.Unsupported
        assertThat(result.reason).describedAs("${fixture.id}: implementation diagnostic").isNotBlank()
        assertThat(fixture.reason).describedAs("${fixture.id}: deferred work explanation").isNotBlank()
    }

    private fun verifyWitnesses(fixture: RegexConformanceFixture) {
        val automaton = compile(fixture.pattern)
        assertThat(automaton.isEmpty).describedAs(fixture.id).isFalse()
        val generated = generatedWitnesses(fixture.pattern, automaton)
        val runtimeRegex = runtimeMatcher(fixture)
        for (value in generated) {
            assertThat(automaton.run(value)).describedAs(fixture.id).isTrue()
            assertThat(value.all { it.code < 128 }).describedAs(fixture.id).isTrue()
            if (runtimeRegex != null) {
                assertThat(runtimeRegex.containsMatchIn(value))
                    .describedAs("JVM cross-check of ${fixture.id}: $value").isTrue()
            }
        }

        val asciiCandidates = fixture.candidates.filter { it.value.all { ch -> ch.code < 128 } }
        asciiCandidates.forEach { candidate ->
            assertThat(automaton.run(candidate.value))
                .describedAs("${fixture.id}: ${candidate.value}").isEqualTo(candidate.matches)
        }
    }

    private fun generatedWitnesses(pattern: String, automaton: Automaton): Set<String> {
        val generator = RegexBasedStringGenerator(pattern, automaton)
        return buildSet {
            add(generator.generateShortest())
            generator.generateLongest(50)?.let { add(it) }
            repeat(20) { add(generator.random(0, 50)) }
            boundedStrings().filterTo(this) { automaton.run(it) }
        }
    }

    private fun runtimeMatcher(fixture: RegexConformanceFixture): Regex? {
        if (fixture.generation != "SKIPPED_JVM_DIALECT") return Regex(fixture.pattern)
        val runtime = runCatching { Regex(fixture.pattern) }.getOrNull()
        val differs = runtime == null || fixture.candidates.any { runtime.containsMatchIn(it.value) != it.matches }
        assertThat(differs).describedAs("${fixture.id}: JVM syntax or matching differs from ECMAScript").isTrue()
        return null
    }

    private fun compile(pattern: String): Automaton {
        val result = compiler().compile(pattern)
        assertThat(result).describedAs(pattern).isInstanceOf(RegexLookaroundCompilation.Compiled::class.java)
        return (result as RegexLookaroundCompilation.Compiled).automaton
    }

    private fun boundedStrings(): List<String> {
        val alphabet = listOf('a', 'b', 'c', '0', 'A', ' ', '\t', '\n', '\u007F')
        var level = listOf("")
        val strings = mutableListOf("")
        repeat(3) {
            level = level.flatMap { prefix -> alphabet.map { prefix + it } }
            strings += level
        }

        return strings
    }

    companion object {
        @JvmStatic
        fun supportedCases() = RegexConformanceFixtures.read().cases.filter {
            it.deferredReason == null && it.generation in listOf("SUPPORTED", "SKIPPED_JVM_DIALECT")
        }

        @JvmStatic
        fun emptyCases() = RegexConformanceFixtures.read().cases.filter {
            it.deferredReason == null && it.generation == "NO_WITNESS_IN_GENERATION_DOMAIN"
        }

        @JvmStatic
        fun unsupportedCases() = RegexConformanceFixtures.read().cases.filter {
            it.deferredReason == null && it.generation == "UNSUPPORTED"
        }

        @JvmStatic
        fun deferredCases() = RegexConformanceFixtures.read().cases.filter { it.deferredReason != null }
    }
}
