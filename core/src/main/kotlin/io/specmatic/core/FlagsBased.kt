package io.specmatic.core

import io.specmatic.core.pattern.IgnoreUnexpectedKeys

const val POSITIVE_TEST_DESCRIPTION_PREFIX = "+ve "
const val NEGATIVE_TEST_DESCRIPTION_PREFIX = "-ve "

data class FlagsBased(
    val defaultExampleResolver: DefaultExampleResolver,
    val generation: GenerationStrategies,
    val unexpectedKeyCheck: UnexpectedKeyCheck?,
    val positivePrefix: String,
    val negativePrefix: String,
    val allPatternsAreMandatory: Boolean,
    val useFuzzyMatching: Boolean,
    val maxTestRequestCombinations: Int
) {
    fun update(resolver: Resolver): Resolver {
        val findKeyErrorCheck = resolver.findKeyErrorCheck
            .let { unexpectedKeyCheck?.let(it::withUnexpectedKeyCheck) ?: it }
            .let { if (useFuzzyMatching) FuzzyKeyCheck(it) else it }

        return resolver.copy(
            defaultExampleResolver = defaultExampleResolver,
            generation = generation,
            findKeyErrorCheck = findKeyErrorCheck,
            allPatternsAreMandatory = allPatternsAreMandatory,
            maxTestRequestCombinations = maxTestRequestCombinations
        )
    }

    fun withoutGenerativeTests(): FlagsBased {
        return this.copy(generation = NonGenerativeTests)
    }
}

fun strategiesFromFlags(specmaticConfig: SpecmaticConfig): FlagsBased {
    val (positivePrefix, negativePrefix) =
        if (specmaticConfig.isResiliencyTestingEnabled())
            Pair(POSITIVE_TEST_DESCRIPTION_PREFIX, NEGATIVE_TEST_DESCRIPTION_PREFIX)
        else
            Pair("", "")

    return FlagsBased(
        defaultExampleResolver = if (specmaticConfig.getSchemaExampleDefault()) UseDefaultExample else DoNotUseDefaultExample,
        generation = when {
            specmaticConfig.isResiliencyTestingEnabled() -> GenerativeTestsEnabled(positiveOnly = specmaticConfig.isOnlyPositiveTestingEnabled())
            else -> NonGenerativeTests
        },
        unexpectedKeyCheck = if (specmaticConfig.isExtensibleSchemaEnabled()) IgnoreUnexpectedKeys else null,
        positivePrefix = positivePrefix,
        negativePrefix = negativePrefix,
        allPatternsAreMandatory = specmaticConfig.getAllPatternsMandatory(),
        useFuzzyMatching = specmaticConfig.getFuzzyMatchingEnabled(),
        maxTestRequestCombinations = specmaticConfig.getMaxTestRequestCombinations() ?: Int.MAX_VALUE
    )
}

val DefaultStrategies: FlagsBased
    get() = strategiesFromFlags(SpecmaticConfig())
