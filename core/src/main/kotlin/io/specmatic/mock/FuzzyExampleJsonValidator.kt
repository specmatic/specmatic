package io.specmatic.mock

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.FuzzyKeyCheck
import io.specmatic.core.MismatchMessages
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.fuzzy.LevenshteinStrategy
import io.specmatic.core.log.CompositePrinter
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value

private class NoLogPrinter: CompositePrinter() {
    override fun print(msg: LogMessage, indentation: String) {
        return
    }
}

object FuzzyExampleMisMatchMessages : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Example format expected $expected, but example contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" is invalid"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "Example format expected mandatory $keyLabel \"$keyName\" to be present but was missing from the example"
    }

    override fun optionalKeyMissing(keyLabel: String, keyName: String): String {
        return "Expected optional $keyLabel \"$keyName\" to be present but was missing from the example"
    }
}

object FuzzyExampleJsonValidator {
    private const val EXAMPLE_SCHEMA_PATH: String = "/schemas/external_example.yaml"
    private val patterns: Map<String, Pattern> by lazy {
        val noLogStrategy = Verbose(NoLogPrinter())
        val schemaContent = this::class.java.getResourceAsStream(EXAMPLE_SCHEMA_PATH)?.bufferedReader()?.use { it.readText() } ?: throw IllegalStateException("CRITICAL: External Example Schema not found at '$EXAMPLE_SCHEMA_PATH'. Verify the file exists in resources")
        try {
            OpenApiSpecification.fromYAML(schemaContent, EXAMPLE_SCHEMA_PATH, logger = noLogStrategy).parseUnreferencedSchemas()
        } catch (e: Throwable) {
            throw IllegalStateException("CRITICAL: Failed to parse External Example Schema YAML at '$EXAMPLE_SCHEMA_PATH'.\nError: ${e.message}", e)
        }
    }

    private val resolver: Resolver = Resolver(
        newPatterns = patterns,
        mismatchMessages = FuzzyExampleMisMatchMessages,
        findKeyErrorCheck = FuzzyKeyCheck(unexpectedKeyCheck = IgnoreUnexpectedKeys),
    )

    fun matches(rawValue: Map<String, Value>): Result {
        return matches(JSONObjectValue(rawValue))
    }

    fun matches(value: JSONObjectValue): Result {
        return try {
            val examplePattern = resolvePatternToUse(value.jsonObject)
            examplePattern.matches(value, resolver)
        } catch (e: Exception) {
            logger.log(e, "Unexpected error during External Example validation")
            Result.Failure("Critical internal error validating external example: ${e.message}")
        }
    }

    fun fix(value: JSONObjectValue): JSONObjectValue {
        return try {
            val examplePattern = resolvePatternToUse(value.jsonObject)
            examplePattern.fixValue(value, resolver) as? JSONObjectValue ?: value
        } catch (e: Exception) {
            logger.log(e, "Unexpected error during External Example fix")
            value
        }
    }

    fun matchesRequest(value: Map<String, Value>): Result {
        val requestPattern = patterns["(RequestSchema)"] ?: throw IllegalStateException("CRITICAL: External Example Schema is missing the required schema named 'RequestSchema'")
        return requestPattern.matches(JSONObjectValue(value), resolver)
    }

    fun matchesResponse(value: Map<String, Value>): Result {
        val requestPattern = patterns["(ResponseSchema)"] ?: throw IllegalStateException("CRITICAL: External Example Schema is missing the required schema named 'ResponseSchema'")
        return requestPattern.matches(JSONObjectValue(value), resolver)
    }

    private fun resolvePatternToUse(rawJson: Map<String, Value>): Pattern {
        return if (isPartialRawValue(rawJson)) {
            patterns["(PartialExample)"] ?: throw IllegalStateException("CRITICAL: External Example Schema is missing the required schema named 'PartialExample'")
        } else {
            patterns["(Example)"] ?: throw IllegalStateException("CRITICAL: External Example Schema is missing the required schema named 'Example'")
        }
    }

    private fun isPartialRawValue(rawValue: Map<String, Value>): Boolean {
        val strategy = LevenshteinStrategy()
        val maxAllowedDistance = strategy.maxAllowedDistance(PARTIAL.length)
        return rawValue.any { strategy.score(it.key, PARTIAL) <= maxAllowedDistance }
    }
}
