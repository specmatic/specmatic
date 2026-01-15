package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.conversions.lenient.requireGreaterThanOrEqualOrDrop
import io.specmatic.conversions.lenient.requireMinimum
import io.specmatic.core.pattern.NumberPattern
import java.math.BigDecimal

enum class NumericBoundSource(val field: String) {
    MINIMUM("minimum"),
    EXCLUSIVE_MINIMUM("exclusiveMinimum"),
    MAXIMUM("maximum"),
    EXCLUSIVE_MAXIMUM("exclusiveMaximum");
}

data class NumberConstraints(
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minimum: BigDecimal? = null,
    val maximum: BigDecimal? = null,
    val minSource: NumericBoundSource? = null,
    val maxSource: NumericBoundSource? = null,
    val isExclusiveMinimum: Boolean,
    val isExclusiveMaximum: Boolean,
    val isDoubleFormat: Boolean,
    val isBoundaryTestingEnabled: Boolean,
    val example: String? = null
) {
    private val smallestInc = BigDecimal("1")

    fun toPattern(collectorContext: CollectorContext): NumberPattern = collectorContext.safely(fallback = { NumberPattern() }) {
        val effectiveMinLength = minLength?.let { checkMinLength(it, collectorContext) }
        val effectiveMaxLength = maxLength?.let { checkMaxLength(it, effectiveMinLength, collectorContext) }
        val (correctedMin, correctedMax) = computeNumericBounds(collectorContext)
        NumberPattern(
            minLength = effectiveMinLength ?: 1,
            maxLength = effectiveMaxLength ?: Int.MAX_VALUE,
            minimum = correctedMin,
            exclusiveMinimum = isExclusiveMinimum,
            maximum = correctedMax,
            exclusiveMaximum = isExclusiveMaximum,
            example = example,
            isDoubleFormat = isDoubleFormat,
            boundaryTestingEnabled = isBoundaryTestingEnabled
        )
    }

    private fun checkMinLength(min: Int?, collectorContext: CollectorContext): Int? {
        return min?.let {
            collectorContext.requireMinimum("minLength", it, 1, ruleViolation = OpenApiLintViolations.INVALID_MIN_LENGTH)
        }
    }

    private fun checkMaxLength(max: Int?, effectiveMin: Int?, collectorContext: CollectorContext): Int? {
        return max?.let {
            collectorContext.requireGreaterThanOrEqualOrDrop(
                name = "maxLength",
                value = it,
                minimum = effectiveMin ?: 1,
                message = { current, minimum -> "maxLength $current cannot be less than minLength $minimum" },
                ruleViolation = OpenApiLintViolations.INVALID_MAX_LENGTH
            )
        }
    }

    private fun computeNumericBounds(collectorContext: CollectorContext): Pair<BigDecimal?, BigDecimal?> {
        if (minimum == null || maximum == null) return minimum to maximum
        val realizedMaxSource = maxSource?.field ?: NumericBoundSource.MAXIMUM.field
        val realizedMinSource = minSource?.field ?: NumericBoundSource.MINIMUM.field

        val effectiveMin = if (isExclusiveMinimum) minimum.plus(smallestInc) else minimum
        val effectiveMax = if (isExclusiveMaximum) maximum.minus(smallestInc) else maximum
        val correctedEffectiveMaximum = collectorContext.check(
            name = realizedMaxSource, value = maximum,
            isValid = { effectiveMax >= effectiveMin }
        ).violation {
            OpenApiLintViolations.INVALID_NUMERIC_BOUNDS
        }.message {
            "$realizedMaxSource $effectiveMax cannot be less than $realizedMinSource $effectiveMin"
        }.orUse {
            effectiveMin.plus(smallestInc)
        }.build()

        return Pair(minimum, correctedEffectiveMaximum)
    }
}
