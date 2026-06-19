package io.specmatic.core

import io.specmatic.core.pattern.AdditionalProperties
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.ListPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.QueryParameterArrayPattern
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.resolvedHop
import io.specmatic.core.pattern.withOptionality

internal fun Pattern.nestedQueryLeafPattern(): Pattern {
    return when (this) {
        is QueryParameterScalarPattern -> pattern
        is QueryParameterArrayPattern -> pattern.firstOrNull() ?: this
        else -> this
    }
}

internal fun Pattern.patternAt(tokens: List<QueryObjectPathToken>, resolver: Resolver): Pattern? {
    if (tokens.isEmpty()) return this

    return when (val resolvedPattern = resolvedHop(this, resolver)) {
        is QueryParameterScalarPattern -> resolvedPattern.pattern.patternAt(tokens, resolver)
        is JSONObjectPattern -> resolvedPattern.childPattern(tokens.first())?.patternAt(tokens.drop(1), resolver)
        is ListPattern -> {
            if (tokens.first() !is QueryObjectPathToken.Index) return null
            resolvedPattern.pattern.patternAt(tokens.drop(1), resolver)
        }
        else -> null
    }
}

private fun JSONObjectPattern.childPattern(token: QueryObjectPathToken): Pattern? {
    if (token !is QueryObjectPathToken.Property) return null

    return pattern[token.name]
        ?: pattern[withOptionality(token.name)]
        ?: when (additionalProperties) {
            is AdditionalProperties.PatternConstrained -> additionalProperties.pattern
            AdditionalProperties.FreeForm, AdditionalProperties.NoAdditionalProperties -> null
        }
}
