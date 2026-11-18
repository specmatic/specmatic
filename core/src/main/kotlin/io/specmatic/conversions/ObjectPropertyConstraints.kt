package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.pattern.AdditionalProperties
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.describePropertyCount
import io.specmatic.core.pattern.isOptional
import io.swagger.v3.oas.models.media.Schema

internal class ObjectPropertyConstraints(
    schema: Schema<*>,
    propertyPatterns: Map<String, Pattern>,
    additionalProperties: AdditionalProperties,
    collectorContext: CollectorContext
) {
    private val requiredPropertyNames = propertyPatterns.keys.filterNot(::isOptional)

    val minProperties = schema.minProperties?.let { minimum ->
        val nonNegativeMinimum = collectorContext.requireMinimum(
            "minProperties", minimum, 0, OpenApiLintViolations.INVALID_MIN_PROPERTIES,
            message = { value, _ -> "minProperties $value is invalid: an object cannot have a negative number of properties." }
        )
        when (additionalProperties) {
            AdditionalProperties.NoAdditionalProperties -> collectorContext.check(
                name = "minProperties", value = nonNegativeMinimum, isValid = { it <= propertyPatterns.size }
            ).violation {
                OpenApiLintViolations.INVALID_MIN_PROPERTIES
            }.message {
                "minProperties $nonNegativeMinimum requires at least ${describePropertyCount(nonNegativeMinimum)}, " +
                    "but the schema declares only ${describePropertyCount(propertyPatterns.size)} and additional properties are not allowed. " +
                    "No object can satisfy these constraints."
            }.orUse { propertyPatterns.size }.build()
            else -> nonNegativeMinimum
        }
    }

    val maxProperties = schema.maxProperties?.let { maximum ->
        val minimumRequiredBySchema = maxOf(schema.minProperties ?: 0, requiredPropertyNames.size, 0)
        collectorContext.check(
            name = "maxProperties", value = maximum, isValid = { it >= minimumRequiredBySchema }
        ).violation {
            OpenApiLintViolations.INVALID_MAX_PROPERTIES
        }.message {
            maximumConflictMessage(maximum, schema.minProperties)
        }.orUse {
            maxOf(maximum, minProperties ?: 0, requiredPropertyNames.size)
        }.build()
    }

    private fun maximumConflictMessage(maximum: Int, originalMinimum: Int?): String {
        val limit = when {
            maximum < 0 -> "maxProperties $maximum is invalid: an object cannot have a negative number of properties."
            else -> "maxProperties $maximum allows at most ${describePropertyCount(maximum)}."
        }
        val minimumReason = originalMinimum?.takeIf { it > 0 && it > maximum }?.let {
            "minProperties $it requires at least ${describePropertyCount(it)}."
        }
        val requiredReason = requiredPropertyNames.takeIf { it.isNotEmpty() && it.size > maximum }?.let {
            "The schema requires ${describePropertyCount(it.size)} (${it.joinToString(", ")}), which must always be present."
        }
        return (listOf(limit) + listOfNotNull(minimumReason, requiredReason) + "No object can satisfy these constraints.")
            .joinToString(" ")
    }
}
