package io.specmatic.core.pattern

import io.specmatic.core.Resolver

interface PossibleJsonObjectPatternContainer {
    fun removeKeysNotPresentIn(keys: Set<String>, resolver: Resolver): Pattern
    fun jsonObjectPattern(resolver: Resolver): JSONObjectPattern?

    val extensions: Map<String, Any>
}