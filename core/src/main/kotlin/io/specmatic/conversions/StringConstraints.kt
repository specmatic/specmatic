package io.specmatic.conversions

import io.specmatic.core.log.logger
import io.swagger.v3.oas.models.media.StringSchema

private const val REASONABLE_STRING_LENGTH = 4 * 1024 * 1024

class StringConstraints(
    schema: StringSchema,
    patternName: String,
    breadCrumb: String,
) {
    private val resolvedBreadCrumb = if (patternName.isNotBlank()) "schema $patternName" else breadCrumb
    val resolvedMaxLength: Int?
    val downsampledMax: Boolean
    val resolvedMinLength: Int?
    val downsampledMin: Boolean

    init {
        rightSizedLength(schema.maxLength, "maxLength", resolvedBreadCrumb).also {
            resolvedMaxLength = it.first
            downsampledMax = it.second
        }

        rightSizedLength(schema.minLength, "minLength", resolvedBreadCrumb).also {
            resolvedMinLength = it.first
            downsampledMin = it.second
        }
    }
}

internal fun rightSizedLength(
    length: Int?,
    paramName: String,
    breadCrumb: String,
): Pair<Int?, Boolean> {
    length ?: return null to false

    return if (length > REASONABLE_STRING_LENGTH) {
        val warningMessage =
            "WARNING: The $paramName of $length for $breadCrumb is very large. We will use a more reasonable $paramName of 4MB. Boundary testing will not be done for this parameter, and string lengths generated for test or stub will not exceed 4MB in length. Please review the $paramName of $length on this field."
        logger.log(warningMessage)
        REASONABLE_STRING_LENGTH to true
    } else {
        length to false
    }
}
