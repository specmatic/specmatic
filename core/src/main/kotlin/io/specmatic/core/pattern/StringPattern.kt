package io.specmatic.core.pattern

import io.specmatic.conversions.SchemaLintViolations
import io.specmatic.conversions.StringConstraints
import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.constraintMismatchResult
import io.specmatic.core.dataTypeMismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.CDATAValue
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.nio.charset.StandardCharsets
import java.util.*

data class StringPattern (
    override val typeAlias: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    override val example: String? = null,
    val regex: String? = null,
    private val downsampledMin: Boolean = false,
    private val downsampledMax: Boolean = false,
) : Pattern, ScalarType, HasDefaultExample {
    private val regExSpec get() = RegExSpec(regex)
    private val effectiveMinLength get() = minLength ?: 0

    init {
        if (effectiveMinLength < 0) {
            throw IllegalArgumentException("minLength $effectiveMinLength cannot be less than 0")
        }
        maxLength?.let {
            if (effectiveMinLength > it) {
                throw IllegalArgumentException("maxLength $it cannot be less than minLength $effectiveMinLength")
            }
        }
        regExSpec.validateMinLength(minLength)
        regExSpec.validateMaxLength(maxLength)
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData?.hasTemplate() == true)
            return Result.Success()

        val sampleData =
            when (sampleData) {
                is StringValue -> sampleData
                is CDATAValue -> StringValue(sampleData.toStringLiteral())
                else -> return dataTypeMismatchResult(this, sampleData, resolver.mismatchMessages)
            }

        if (lengthBelowLowerBound(sampleData)) return constraintMismatchResult(
            "string with minLength $effectiveMinLength",
            sampleData, resolver.mismatchMessages
        )

        if (lengthAboveUpperBound(sampleData)) return constraintMismatchResult(
            "string with maxLength $maxLength",
            sampleData, resolver.mismatchMessages
        )

        if (!regExSpec.match(sampleData)) {
            return constraintMismatchResult(
                """string that matches regex $regex""",
                sampleData,
                resolver.mismatchMessages
            )
        }

        return Result.Success()
    }

    private fun lengthAboveUpperBound(sampleData: StringValue) =
        maxLength?.let { sampleData.toStringLiteral().length > it } ?: false

    private fun lengthBelowLowerBound(sampleData: StringValue) =
        sampleData.toStringLiteral().length < effectiveMinLength

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        return encompasses(this, otherPattern, thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override fun generate(resolver: Resolver): Value {
        val defaultExample = resolver.resolveExample(example, this)

        defaultExample?.let {
            val result = matches(it, resolver)
            result.throwOnFailure()
            return it
        }

        return resolver.provideString(this) ?: regExSpec.generateRandomString(effectiveMinLength, maxLength)
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> = sequence {
        yield(HasValue(this@StringPattern))

        minLength?.let { minLen ->
            val exampleString = regExSpec.generateShortestStringOrRandom(minLen)
            yield(HasValue(ExactValuePattern(StringValue(exampleString)), "is set to the shortest possible string"))
        }

        maxLength?.let { maxLen ->
            val exampleString = regExSpec.generateLongestStringOrRandom(maxLen)
            yield(HasValue(ExactValuePattern(StringValue(exampleString)), "is set to the longest possible string"))
        }
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> = sequenceOf(this)

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        val current = this

        return sequence {
            if (config.withDataTypeNegatives) {
                yieldAll(scalarAnnotation(current, sequenceOf(NullPattern, NumberPattern(), BooleanPattern())))
            }

            if (maxLength != null && !downsampledMax) {
                val pattern = copy(
                    minLength = maxLength.inc(),
                    maxLength = maxLength.inc(),
                    regex = null
                )
                yield(
                    HasValue(pattern, "is set to a value with length greater than maxLength '$maxLength'")
                )
            }

            if (minLength != null && minLength != 0 && !downsampledMin) {
                val pattern = copy(
                    minLength = effectiveMinLength.dec(),
                    maxLength = effectiveMinLength.dec(),
                    regex = null
                )
                yield(
                    HasValue(
                        pattern, "is set to a value with length lesser than minLength '$effectiveMinLength'"
                    )
                )
            }

            regExSpec.negativeBasedOn(minLength, maxLength)?.let { (regex, minLength, maxLength) ->
                val pattern = copy(regex = regex, minLength = minLength, maxLength = maxLength)
                yield(HasValue(pattern, "is set to a value matching an invalid regex '$regex'"))
            }
        }
    }

    override fun parse(value: String, resolver: Resolver): Value = StringValue(value)
    override val typeName: String = "string"

    override val pattern: Any = "(string)"
    override fun toString(): String = pattern.toString()

    companion object {
        fun from(stringConstraints: StringConstraints, regex: String? = null, example: String? = null, collectorContext: CollectorContext): StringPattern {
            val effectiveMinLength = stringConstraints.resolvedMinLength?.let {
                collectorContext.requireMinimum(
                    name = "minLength",
                    value = it,
                    minimum = 0,
                    ruleViolation = SchemaLintViolations.BAD_VALUE,
                    message = { current, minimum ->
                        "minLength should never be less than $minimum, but it is $current. Please use a positive minLength, or drop the constraint."
                    }
                )
            }

            val effectiveMaxLength = stringConstraints.resolvedMaxLength?.let {
                collectorContext.requireGreaterThanOrEqualOrDrop(
                    name = "maxLength",
                    value = it,
                    minimum = effectiveMinLength ?: 0,
                    ruleViolation = SchemaLintViolations.CONFLICTING_CONSTRAINTS,
                    message = { current, minimum ->
                        "maxLength $current should have been greater than minLength $minimum. Please make sure that maxLength and minLength are not in conflict."
                    },
                )
            }

            val regexSpec = collectorContext.at("pattern").safely(fallback = { null }, message = "Invalid Regex format", ruleViolation = SchemaLintViolations.BAD_VALUE) {
                if (regex == null) return@safely null
                RegExSpec(regex)
            }

            val effectiveRegex: String? = regexSpec?.let {
                val regexMatchesMinLength = collectorContext.at("pattern").attempt(
                    message = "The regex pattern \"$regex\" is incompatible with minLength $effectiveMinLength because its longest possible value is shorter than minLength. Drop minLength or fix the pattern.",
                    ruleViolation = SchemaLintViolations.CONFLICTING_CONSTRAINTS,
                    block = { regexSpec.validateMinLength(effectiveMinLength) }
                )

                val regexMatchesMaxLength = collectorContext.at("pattern").attempt(
                    message = "The regex pattern \"$regex\" is incompatible with maxLength $effectiveMaxLength because its shortest possible value is greater than maxLength. Drop maxLength or fix the pattern.",
                    ruleViolation = SchemaLintViolations.CONFLICTING_CONSTRAINTS,
                    block = { regexSpec.validateMaxLength(effectiveMaxLength) },
                )

                regex.takeIf { regexMatchesMinLength && regexMatchesMaxLength }
            }

            return collectorContext.safely(fallback = { StringPattern() }) {
                StringPattern(
                    example = example,
                    regex = effectiveRegex,
                    minLength = effectiveMinLength,
                    maxLength = effectiveMaxLength,
                    downsampledMax = stringConstraints.downsampledMax,
                    downsampledMin = stringConstraints.downsampledMin,
                )
            }
        }
    }
}

fun randomString(length: Int = 5): String {
    val array = ByteArray(length)
    val random = Random()
    for (index in array.indices) {
        array[index] = (random.nextInt(25) + 65).toByte()
    }
    return String(array, StandardCharsets.UTF_8)
}
