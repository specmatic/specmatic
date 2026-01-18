package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.swagger.v3.oas.models.media.Schema

const val REASONABLE_STRING_LENGTH = 4 * 1024 * 1024

class StringConstraints(schema: Schema<*>, patternName: String, collectorContext: CollectorContext = CollectorContext()) {
    val resolvedMaxLength: Int?
    val downsampledMax: Boolean
    val resolvedMinLength: Int?
    val downsampledMin: Boolean

    init {
        rightSizedLength(schema.maxLength, "maxLength", patternName, collectorContext).also {
            resolvedMaxLength = it.first
            downsampledMax = it.second
        }

        rightSizedLength(schema.minLength, "minLength", patternName, collectorContext).also {
            resolvedMinLength = it.first
            downsampledMin = it.second
        }
    }
}

internal fun rightSizedLength(length: Int?, paramName: String, patternName: String, collectorContext: CollectorContext = CollectorContext()): Pair<Int?, Boolean> {
    length ?: return null to false
    if (length <= REASONABLE_STRING_LENGTH) return length to false
    collectorContext.at(paramName).record(
        message = "A length of $length is impractical. Limiting the $paramName for now to the more practical 4MB, which is enough for most purposes. Please double-check the $paramName needed for this value and adjust accordingly.",
        isWarning = true,
        ruleViolation = SchemaLintViolations.LENGTH_EXCEEDS_LIMIT
    )
    return REASONABLE_STRING_LENGTH to true
}
