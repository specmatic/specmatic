package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.discriminator.DiscriminatorBasedItem
import io.specmatic.core.value.Value

interface SubSchemaCompositePattern {
    val pattern: List<Pattern>
    val discriminator: Discriminator?

    fun isDiscriminatorPresent(): Boolean

    fun generateForEveryDiscriminatorValue(resolver: Resolver): List<DiscriminatorBasedItem<Value>>

    fun calculatePath(
        value: Value,
        resolver: Resolver,
    ): Set<String>

    fun getDiscriminatorBasedPattern(
        updatedPatterns: List<Pattern>,
        discriminatorValue: String,
        resolver: Resolver,
    ): JSONObjectPattern?

    fun fixValue(
        value: Value,
        resolver: Resolver,
        discriminatorValue: String,
        onValidDiscValue: () -> Value?,
        onInvalidDiscValue: (Result.Failure) -> Value?,
    ): Value?

    fun generateValue(resolver: Resolver, discriminatorValue: String = ""): Value
    fun matchesValue(sampleData: Value?, resolver: Resolver, discriminatorValue: String, discMisMatchBreadCrumb: String? = null): Result
}
