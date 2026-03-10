package io.specmatic.core.examples.source

import io.specmatic.core.Feature

data class FeatureAndUnusedExamples(
    val feature: Feature,
    val unusedExamples: Set<String>
) {
    constructor(pair: Pair<Feature, Set<String>>) : this(pair.first, pair.second)
}