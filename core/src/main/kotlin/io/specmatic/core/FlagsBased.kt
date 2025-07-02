package io.specmatic.core

import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.SCHEMA_EXAMPLE_DEFAULT
import io.specmatic.core.utilities.Flags.Companion.getBooleanValue

const val POSITIVE_TEST_DESCRIPTION_PREFIX = "+ve "
const val NEGATIVE_TEST_DESCRIPTION_PREFIX = "-ve "

data class FlagsBased(
    val defaultExampleResolver: DefaultExampleResolver,
    val generation: GenerationStrategies,
    val unexpectedKeyCheck: UnexpectedKeyCheck?,
    val positivePrefix: String,
    val negativePrefix: String,
    val allPatternsAreMandatory: Boolean
) {
    fun update(resolver: Resolver): Resolver {
        val findKeyErrorCheck = if(unexpectedKeyCheck != null) {
            resolver.findKeyErrorCheck.copy(unexpectedKeyCheck = unexpectedKeyCheck)
        } else
            resolver.findKeyErrorCheck

        return resolver.copy(
            defaultExampleResolver = defaultExampleResolver,
            generation = generation,
            findKeyErrorCheck = findKeyErrorCheck,
            allPatternsAreMandatory = allPatternsAreMandatory
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
        defaultExampleResolver = if (getBooleanValue(SCHEMA_EXAMPLE_DEFAULT)) UseDefaultExample else DoNotUseDefaultExample,
        generation = when {
            specmaticConfig.isResiliencyTestingEnabled() -> GenerativeTestsEnabled(positiveOnly = specmaticConfig.isOnlyPositiveTestingEnabled())
            else -> NonGenerativeTests
        },
        unexpectedKeyCheck = if (specmaticConfig.isExtensibleSchemaEnabled()) IgnoreUnexpectedKeys else null,
        positivePrefix = positivePrefix,
        negativePrefix = negativePrefix,
        allPatternsAreMandatory = specmaticConfig.getAllPatternsMandatory()
    )
}

val DefaultStrategies = FlagsBased (
    DoNotUseDefaultExample,
    NonGenerativeTests,
    null,
    "",
    "",
    Flags.getBooleanValue(Flags.ALL_PATTERNS_MANDATORY, false)
)
