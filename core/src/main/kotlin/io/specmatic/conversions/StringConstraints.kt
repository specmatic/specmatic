package io.specmatic.conversions

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.RegExSpec
import io.swagger.v3.oas.models.media.StringSchema

private const val REASONABLE_STRING_LENGTH = 4 * 1024 * 1024

class StringConstraints(schema: StringSchema, patternName: String, breadCrumb: String) {
    private val resolvedBreadCrumb = if (patternName.isNotBlank()) "schema $patternName" else breadCrumb
    val resolvedMaxLength: Int?
    val downsampledMax: Boolean
    val resolvedMinLength: Int?
    val downsampledMin: Boolean

    init {
        val (maxLength, didDownSampleMax) = rightSizedLength(schema.maxLength, "maxLength", resolvedBreadCrumb)
        val (minLength, didDownSampleMin) = rightSizedLength(schema.minLength, "minLength", resolvedBreadCrumb)
        val (downSizedMin, downSizedMax) = downSizeUntilRegexSucceeds(schema.pattern, minLength, maxLength)

        resolvedMaxLength = downSizedMax ?: maxLength
        downsampledMax = downSizedMax != null || didDownSampleMax
        resolvedMinLength = downSizedMin ?: minLength
        downsampledMin = downSizedMin != null || didDownSampleMin
    }

    companion object {
        private val memoizedDownSize = memoizeFunc<Triple<String, Int, String>, Int>(
            inputToCacheKey = { (regex, size) -> "$regex-$size" },
            fn = { downSizeUntilSucceeds(it.first, it.second, it.third) },
            onHit = { (regex, size, breadCrumb), finalSize ->
                logger.log("""
                |WARNING: Failed to generate a string of length $size that matches the regex $regex for $breadCrumb
                |Boundary testing will not be done for this regex, Final string length of $finalSize will be used instead
                """.trimMargin())
            }
        )

        private fun downSizeUntilRegexSucceeds(regex: String?, minLength: Int?, maxLength: Int?): Pair<Int?, Int?> {
            if (regex == null) return null to null
            if (minLength == null && maxLength == null) return null to null
            val downSizedMinLength = minLength?.let { memoizedDownSize(Triple(regex, it, "minLength")) }
            val downSizedMaxLength = maxLength?.let { memoizedDownSize(Triple(regex, it, "maxLength")) }
            return downSizedMinLength.takeUnless { it == minLength } to downSizedMaxLength.takeUnless { it == maxLength }
        }

        private fun rightSizedLength(length: Int?, paramName: String, breadCrumb: String): Pair<Int?, Boolean> {
            if (length == null) return null to false
            return if (length > REASONABLE_STRING_LENGTH) {
                val warningMessage =
                    "WARNING: The $paramName of $length for $breadCrumb is very large. We will use a more reasonable $paramName of 4MB. Boundary testing will not be done for this parameter, and string lengths generated for test or stub will not exceed 4MB in length. Please review the $paramName of $length on this field."
                logger.log(warningMessage)
                REASONABLE_STRING_LENGTH to true
            } else {
                length to false
            }
        }

        private tailrec fun downSizeUntilSucceeds(regex: String, size: Int, breadCrumb: String): Int {
            val result = runCatching { RegExSpec(regex).validateLength((size * 1.5).toInt()) }
            if (result.isSuccess) return size
            logger.log("""
            |WARNING: Failed to generate a string of length $size that matches the regex $regex for $breadCrumb
            |Boundary testing will not be done for this regex, Halving the size to ${size / 2} and trying again...
            """.trimMargin())
            return downSizeUntilSucceeds(regex, size / 2, breadCrumb)
        }
    }
}

internal fun <T, U> memoizeFunc(fn: (T) -> U, inputToCacheKey: (T) -> Any, onHit: (T, U) -> Unit = { _, _ -> }): (T) -> U {
    val cache = mutableMapOf<Any, U>()
    return { input ->
        val cacheKey = inputToCacheKey(input)
        if (cache.containsKey(cacheKey)) onHit(input, cache.getValue(cacheKey))
        cache.getOrPut(cacheKey) { fn(input) }
    }
}