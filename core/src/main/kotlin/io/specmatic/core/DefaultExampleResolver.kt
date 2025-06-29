package io.specmatic.core

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.Value

interface DefaultExampleResolver {
    fun resolveExample(example: String?, pattern: Pattern, resolver: Resolver): Value?
    fun resolveExample(example: List<String?>?, pattern: Pattern, resolver: Resolver): JSONArrayValue?
    fun resolveExample(example: String?, pattern: List<Pattern>, resolver: Resolver): Value?
    fun theDefaultExampleForThisKeyIsNotOmit(valuePattern: Pattern): Boolean
    fun hasExample(pattern: Pattern): Boolean
}