package io.specmatic.core

import io.specmatic.core.config.SpecmaticConfigVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class FlagsBasedTest {

    @Test
    fun `strategiesFromFlags should enable fuzzy matching from config`() {
        val config = SpecmaticConfigV1V2Common(
            fuzzy = true,
            version = SpecmaticConfigVersion.VERSION_2
        )

        val result = strategiesFromFlags(config)

        assertThat(result.useFuzzyMatching).isTrue()
    }

    @Test
    fun `strategiesFromFlags should disable fuzzy matching when config is false`() {
        val config = SpecmaticConfigV1V2Common(
            fuzzy = false,
            version = SpecmaticConfigVersion.VERSION_2
        )

        val result = strategiesFromFlags(config)

        assertThat(result.useFuzzyMatching).isFalse()
    }

    @Test
    fun `strategiesFromFlags should use schema example default from config`() {
        val config = SpecmaticConfigV1V2Common(
            schemaExampleDefault = true,
            version = SpecmaticConfigVersion.VERSION_2
        )

        val result = strategiesFromFlags(config)

        assertThat(result.defaultExampleResolver).isInstanceOf(UseDefaultExample::class.java)
    }

    @Test
    fun `strategiesFromFlags should not use schema example default when config is false`() {
        val config = SpecmaticConfigV1V2Common(
            schemaExampleDefault = false,
            version = SpecmaticConfigVersion.VERSION_2
        )

        val result = strategiesFromFlags(config)

        assertThat(result.defaultExampleResolver).isInstanceOf(DoNotUseDefaultExample::class.java)
    }

    @Test
    fun `strategiesFromFlags should set maxTestRequestCombinations from config`() {
        val config = SpecmaticConfigV1V2Common(
            test = TestConfiguration(maxTestRequestCombinations = 42),
            version = SpecmaticConfigVersion.VERSION_2
        )

        val result = strategiesFromFlags(config)

        assertThat(result.maxTestRequestCombinations).isEqualTo(42)
    }

    @Test
    fun `strategiesFromFlags should use defaults when config has no overrides`() {
        val config = SpecmaticConfig()

        val result = strategiesFromFlags(config)

        assertThat(result.useFuzzyMatching).isFalse()
        assertThat(result.defaultExampleResolver).isInstanceOf(DoNotUseDefaultExample::class.java)
        assertThat(result.maxTestRequestCombinations).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `FlagsBased update should propagate fuzzy matching into resolver`() {
        val flagsBased = FlagsBased(
            defaultExampleResolver = DoNotUseDefaultExample,
            generation = NonGenerativeTests,
            unexpectedKeyCheck = null,
            positivePrefix = "",
            negativePrefix = "",
            allPatternsAreMandatory = false,
            useFuzzyMatching = true,
            maxTestRequestCombinations = Int.MAX_VALUE
        )

        val resolver = flagsBased.update(Resolver())

        assertThat(resolver.findKeyErrorCheck).isInstanceOf(FuzzyKeyCheck::class.java)
    }

    @Test
    fun `FlagsBased update should not use fuzzy matching when disabled`() {
        val flagsBased = FlagsBased(
            defaultExampleResolver = DoNotUseDefaultExample,
            generation = NonGenerativeTests,
            unexpectedKeyCheck = null,
            positivePrefix = "",
            negativePrefix = "",
            allPatternsAreMandatory = false,
            useFuzzyMatching = false,
            maxTestRequestCombinations = Int.MAX_VALUE
        )

        val resolver = flagsBased.update(Resolver())

        assertThat(resolver.findKeyErrorCheck).isNotInstanceOf(FuzzyKeyCheck::class.java)
    }

    @Test
    fun `FlagsBased update should propagate maxTestRequestCombinations into resolver`() {
        val flagsBased = FlagsBased(
            defaultExampleResolver = DoNotUseDefaultExample,
            generation = NonGenerativeTests,
            unexpectedKeyCheck = null,
            positivePrefix = "",
            negativePrefix = "",
            allPatternsAreMandatory = false,
            useFuzzyMatching = false,
            maxTestRequestCombinations = 42
        )

        val resolver = flagsBased.update(Resolver())

        assertThat(resolver.maxTestRequestCombinations).isEqualTo(42)
    }

    @Test
    fun `FlagsBased update should propagate defaultExampleResolver into resolver`() {
        val flagsBased = FlagsBased(
            defaultExampleResolver = UseDefaultExample,
            generation = NonGenerativeTests,
            unexpectedKeyCheck = null,
            positivePrefix = "",
            negativePrefix = "",
            allPatternsAreMandatory = false,
            useFuzzyMatching = false,
            maxTestRequestCombinations = Int.MAX_VALUE
        )

        val resolver = flagsBased.update(Resolver())

        assertThat(resolver.defaultExampleResolver).isInstanceOf(UseDefaultExample::class.java)
    }

    @Test
    fun `end-to-end config to FlagsBased to Resolver should propagate all values`() {
        val config = SpecmaticConfigV1V2Common(
            fuzzy = true,
            schemaExampleDefault = true,
            test = TestConfiguration(maxTestRequestCombinations = 50),
            version = SpecmaticConfigVersion.VERSION_2
        )

        val flagsBased = strategiesFromFlags(config)
        val resolver = flagsBased.update(Resolver())

        assertThat(resolver.findKeyErrorCheck).isInstanceOf(FuzzyKeyCheck::class.java)
        assertThat(resolver.defaultExampleResolver).isInstanceOf(UseDefaultExample::class.java)
        assertThat(resolver.maxTestRequestCombinations).isEqualTo(50)
    }
}
