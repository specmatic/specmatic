package io.specmatic.core

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.Value

object DoNotUseDefaultExample : DefaultExampleResolver {
    override fun resolveExample(example: String?, pattern: Pattern, resolver: Resolver): Value? {
        return null
    }

    override fun resolveExample(example: List<String?>?, pattern: Pattern, resolver: Resolver): JSONArrayValue? {
        return null
    }

    override fun resolveExample(example: String?, pattern: List<Pattern>, resolver: Resolver): Value? {
        return null
    }

    override fun theDefaultExampleForThisKeyIsNotOmit(valuePattern: Pattern): Boolean {
        return true
    }

    override fun hasExample(pattern: Pattern): Boolean {
        return false
    }

}