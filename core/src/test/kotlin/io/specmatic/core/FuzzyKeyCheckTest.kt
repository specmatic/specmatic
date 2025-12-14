package io.specmatic.core

import io.specmatic.core.FuzzyKeyCheckTest.Companion.ExpectedError.Companion.fuzzy
import io.specmatic.core.FuzzyKeyCheckTest.Companion.ExpectedError.Companion.fuzzyOptional
import io.specmatic.core.FuzzyKeyCheckTest.Companion.ExpectedError.Companion.missing
import io.specmatic.core.FuzzyKeyCheckTest.Companion.ExpectedError.Companion.unexpected
import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.withOptionality
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class FuzzyKeyCheckTest {
    private fun assertScenario(config: FuzzyTestConfig, scenario: FuzzyScenario) {
        val errors = config.checker.validateAll(pattern, scenario.actual)
        val expected = scenario.expectations.getValue(config)

        assertThat(errors).hasSize(expected.size)
        expected.forEach { exp ->
            when (exp) {
                is ExpectedError.Missing -> {
                    val error = errors.filterIsInstance<MissingKeyError>().first { it.name == exp.name }
                    val failure = error.missingKeyToResult("key")
                    assertThat(failure.reportString()).isEqualTo(exp.message)
                    assertThat(failure.isPartial).isEqualTo(exp.isPartial)
                }

                is ExpectedError.Unexpected -> {
                    val error = errors.filterIsInstance<UnexpectedKeyError>().first { it.name == exp.name }
                    val failure = error.unknownKeyToResult("key")
                    assertThat(failure.reportString()).isEqualTo(exp.message)
                    assertThat(failure.isPartial).isEqualTo(exp.isPartial)
                }

                is ExpectedError.Fuzzy -> {
                    val error = errors.filterIsInstance<FuzzyKeyError>().first { it.name == exp.name }
                    val failure = when {
                        pattern.containsKey(error.canonicalKey) -> error.missingKeyToResult("key")
                        pattern.containsKey(withOptionality(error.canonicalKey)) -> error.missingOptionalKeyToResult("key")
                        else -> error.unknownKeyToResult("key")
                    }

                    assertThat(error.canonicalKey).isEqualTo(exp.candidate)
                    assertThat(failure.reportString()).isEqualTo(exp.message)
                    assertThat(failure.isPartial).isEqualTo(exp.isPartial)
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.specmatic.core.FuzzyKeyCheckTest#scenarios")
    fun ignorePatternButCheckUnexpected(scenario: FuzzyScenario) = assertScenario(FuzzyTestConfig.IgnorePatternButCheckUnexpected, scenario)

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.specmatic.core.FuzzyKeyCheckTest#scenarios")
    fun ignorePatternAndIgnoreUnexpected(scenario: FuzzyScenario) = assertScenario(FuzzyTestConfig.IgnorePatternAndIgnoreUnexpected, scenario)

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.specmatic.core.FuzzyKeyCheckTest#scenarios")
    fun checkPatternAndCheckUnexpected(scenario: FuzzyScenario) = assertScenario(FuzzyTestConfig.CheckPatternAndCheckUnexpected, scenario)

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.specmatic.core.FuzzyKeyCheckTest#scenarios")
    fun checkPatternButIgnoreUnexpected(scenario: FuzzyScenario) = assertScenario(FuzzyTestConfig.CheckPatternButIgnoreUnexpected, scenario)

    companion object {
        private val pattern = mapOf("name" to StringPattern(), "email" to StringPattern(), "age?" to StringPattern())

        @JvmStatic
        fun scenarios(): Stream<FuzzyScenario> = Stream.of(
            // Basic cases
            FuzzyScenario(
                name = "exact match",
                actual = mapOf("name" to "John", "email" to "john@example.com", "age" to "30"),
                overrideExpectations = emptyMap()
            ),
            FuzzyScenario(
                name = "Missing optional key",
                actual = mapOf("name" to "John", "email" to "john@example.com"),
                overrideExpectations = emptyMap()
            ),
            FuzzyScenario(
                name = "Missing mandatory key",
                actual = mapOf("email" to "john@example.com"),
                overrideExpectations = mapOf(
                    FuzzyTestConfig.CheckPatternButIgnoreUnexpected to listOf(missing("name")),
                    FuzzyTestConfig.CheckPatternAndCheckUnexpected to listOf(missing("name")),
                )
            ),
            FuzzyScenario(
                name = "Unexpected key matches nothing",
                actual = mapOf("name" to "John Doe", "email" to "john@example.com", "extra" to "extra"),
                overrideExpectations = mapOf(
                    FuzzyTestConfig.IgnorePatternButCheckUnexpected to listOf(unexpected("extra")),
                    FuzzyTestConfig.CheckPatternAndCheckUnexpected to listOf(unexpected("extra")),
                )
            ),

            // Fuzzy matching cases
            FuzzyScenario(
                name = "Missing optional key matches unexpected key",
                actual = mapOf("name" to "John", "email" to "john@example.com", "ages" to "30"),
                overrideExpectations = mapOf(
                    FuzzyTestConfig.IgnorePatternButCheckUnexpected to listOf(fuzzyOptional("ages", "age")),
                    FuzzyTestConfig.CheckPatternAndCheckUnexpected to listOf(fuzzyOptional("ages", "age")),
                    FuzzyTestConfig.CheckPatternButIgnoreUnexpected to listOf(fuzzyOptional("ages", "age", true)),
                )
            ),
            FuzzyScenario(
                name = "Missing mandatory key matches unexpected key",
                actual = mapOf("nam" to "John", "email" to "john@example.com"),
                overrideExpectations = mapOf(
                    FuzzyTestConfig.IgnorePatternButCheckUnexpected to listOf(fuzzy("nam", "name")),
                    FuzzyTestConfig.CheckPatternButIgnoreUnexpected to listOf(fuzzy("nam", "name")),
                    FuzzyTestConfig.CheckPatternAndCheckUnexpected to listOf(fuzzy("nam", "name")),
                ),
            ),

            // Edge cases
            FuzzyScenario(
                name = "Two fuzzy actual keys for the same mandatory key",
                actual = mapOf("nmae" to "John", "naem" to "Johnny", "email" to "john@example.com"),
                overrideExpectations = mapOf(
                    FuzzyTestConfig.IgnorePatternButCheckUnexpected to listOf(fuzzy("nmae", "name"), unexpected("naem")),
                    FuzzyTestConfig.CheckPatternAndCheckUnexpected to listOf(fuzzy("nmae", "name"), unexpected("naem")),
                    FuzzyTestConfig.CheckPatternButIgnoreUnexpected to listOf(fuzzy("nmae", "name"))
                )
            ),
            FuzzyScenario(
                name = "Two fuzzy actual keys for the same optional key",
                actual = mapOf("name" to "John", "email" to "john@example.com", "ages" to "30", "agex" to "30"),
                overrideExpectations = mapOf(
                    FuzzyTestConfig.IgnorePatternButCheckUnexpected to listOf(fuzzyOptional("ages", "age"), unexpected("agex")),
                    FuzzyTestConfig.CheckPatternAndCheckUnexpected to listOf(fuzzyOptional("ages", "age"), unexpected("agex")),
                    FuzzyTestConfig.CheckPatternButIgnoreUnexpected to listOf(fuzzyOptional("ages", "age", partial = true))
                )
            ),
            FuzzyScenario(
                name = "Multiple missing mandatory and unexpected keys",
                actual = mapOf("extra1" to "x", "extra2" to "y"),
                overrideExpectations = mapOf(
                    FuzzyTestConfig.CheckPatternAndCheckUnexpected to listOf(
                        missing("name"),
                        missing("email"),
                        unexpected("extra1"),
                        unexpected("extra2")
                    ),
                    FuzzyTestConfig.CheckPatternButIgnoreUnexpected to listOf(missing("name"), missing("email")),
                    FuzzyTestConfig.IgnorePatternButCheckUnexpected to listOf(unexpected("extra1"), unexpected("extra2"))
                )
            ),
            FuzzyScenario(
                name = "Missing mandatory and optional with fuzzy unexpected keys",
                actual = mapOf("nme" to "John", "ages" to "30"),
                overrideExpectations = mapOf(
                    FuzzyTestConfig.CheckPatternAndCheckUnexpected to listOf(fuzzy("nme", "name"), fuzzyOptional("ages", "age"), missing("email")),
                    FuzzyTestConfig.CheckPatternButIgnoreUnexpected to listOf(fuzzy("nme", "name"), fuzzyOptional("ages", "age", partial = true), missing("email")),
                    FuzzyTestConfig.IgnorePatternButCheckUnexpected to listOf(fuzzy("nme", "name"), fuzzyOptional("ages", "age"))
                )
            ),
            FuzzyScenario(
                name = "Empty actual vs non-empty pattern",
                actual = emptyMap<String, String>(),
                overrideExpectations = mapOf(
                    FuzzyTestConfig.CheckPatternAndCheckUnexpected to listOf(missing("name"), missing("email")),
                    FuzzyTestConfig.CheckPatternButIgnoreUnexpected to listOf(missing("name"), missing("email"))
                )
            ),
        )

        enum class FuzzyTestConfig(val checker: FuzzyKeyCheck) {
            IgnorePatternButCheckUnexpected(FuzzyKeyCheck(noPatternKeyCheck, ValidateUnexpectedKeys)),
            IgnorePatternAndIgnoreUnexpected(FuzzyKeyCheck(noPatternKeyCheck, IgnoreUnexpectedKeys)),
            CheckPatternAndCheckUnexpected(FuzzyKeyCheck(CheckOnlyPatternKeys, ValidateUnexpectedKeys)),
            CheckPatternButIgnoreUnexpected(FuzzyKeyCheck(CheckOnlyPatternKeys, IgnoreUnexpectedKeys))
        }

        sealed interface ExpectedError {
            val name: String
            val isPartial: Boolean

            data class Missing(override val name: String, val message: String, override val isPartial: Boolean) : ExpectedError
            data class Unexpected(override val name: String, val message: String, override val isPartial: Boolean) : ExpectedError
            data class Fuzzy(override val name: String, val candidate: String, val message: String, override val isPartial: Boolean) : ExpectedError

            companion object {
                fun missing(name: String, partial: Boolean = false) = Missing(name, "Expected key named \"$name\" was missing", partial)
                fun unexpected(name: String, partial: Boolean = false) = Unexpected(name, "Key named \"$name\" was unexpected", partial)
                fun fuzzy(name: String, candidate: String, partial: Boolean = false) = Fuzzy(name, candidate, "Expected key named \"$candidate\" was missing. Did you mean \"$candidate\"?", partial)
                fun fuzzyOptional(name: String, candidate: String, partial: Boolean = false) = Fuzzy(name, candidate, "Expected optional key named \"$candidate\" was missing. Did you mean \"$candidate\"?", partial)
            }
        }

        data class FuzzyScenario(val name: String, val actual: Map<String, Any>, val overrideExpectations: Map<FuzzyTestConfig, List<ExpectedError>>) {
            val expectations = defaultExpectations + overrideExpectations
            override fun toString() = name
            companion object {
                private val defaultExpectations = FuzzyTestConfig.entries.associateWith { emptyList<ExpectedError>() }
            }
        }
    }
}
