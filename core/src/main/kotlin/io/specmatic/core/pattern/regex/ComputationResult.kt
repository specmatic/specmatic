package io.specmatic.core.pattern.regex

data class ComputationResult(
    val string: String,
    val dropResult: Boolean = false,
    val acceptableState: Boolean,
)
