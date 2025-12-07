package io.specmatic.mock

import io.specmatic.core.FORM_FIELDS_JSON_KEY
import io.specmatic.core.FuzzyUnexpectedKeyCheck
import io.specmatic.core.KeyCheck
import io.specmatic.core.MULTIPART_FORMDATA_JSON_KEY
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.AnyValuePattern
import io.specmatic.core.pattern.BooleanPattern
import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.ListPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import java.math.BigDecimal

const val OLD_MOCK_HTTP_REQUEST_KEY = "mock-http-request"
const val OLD_MOCK_HTTP_RESPONSE_KEY = "mock-http-response"

object ScenarioStubValidator {
    private val fuzzyUnexpectedKeyCheck = FuzzyUnexpectedKeyCheck(delegate = IgnoreUnexpectedKeys)
    private val resolver: Resolver = Resolver(findKeyErrorCheck = KeyCheck(unexpectedKeyCheck = fuzzyUnexpectedKeyCheck))

    private val metadataPattern: Map<String, Pattern> = mapOf(
        "name?" to StringPattern(),
        "$IS_TRANSIENT_MOCK?" to BooleanPattern(),
        "$DELAY_IN_SECONDS?" to NumberPattern(minimum = BigDecimal(0)),
        "$DELAY_IN_MILLISECONDS?" to NumberPattern(minimum = BigDecimal(0)),
        "$TRANSIENT_MOCK_ID?" to StringPattern()
    )

    private val httpRequestPattern: Map<String, Pattern> = mapOf(
        "path" to StringPattern(),
        "method" to StringPattern(),
        "query?" to JSONObjectPattern(),
        "headers?" to JSONObjectPattern(),
        "body?" to AnyValuePattern,
        "$FORM_FIELDS_JSON_KEY?" to JSONObjectPattern(),
        "$MULTIPART_FORMDATA_JSON_KEY?" to ListPattern(JSONObjectPattern()),
        "$REQUEST_BODY_REGEX?" to StringPattern()
    )

    private val httpResponsePattern: Map<String, Pattern> = mapOf(
        "status" to NumberPattern(minimum = BigDecimal(0)),
        "headers?" to JSONObjectPattern(),
        "body?" to AnyValuePattern,
        "externalisedResponseCommand?" to StringPattern()
    )

    fun matches(rawValue: JSONObjectValue): Result {
        val rootObj = rawValue.jsonObject
        return if (rootObj.containsKey("partial")) {
            validatePartial(rawValue)
        } else {
            validateStandard(rawValue)
        }
    }

    private fun validateStandard(rawValue: JSONObjectValue): Result {
        val httpPatterns = resolveHttpPatterns(rawValue.jsonObject)
        return JSONObjectPattern(pattern = metadataPattern + httpPatterns).matches(rawValue, resolver)
    }

    private fun validatePartial(rawValue: JSONObjectValue): Result {
        val partialObj = (rawValue.jsonObject["partial"] as? JSONObjectValue)?.jsonObject ?: emptyMap()
        val innerHttpPatterns = resolveHttpPatterns(partialObj)
        val partialPattern = JSONObjectPattern(pattern = metadataPattern + mapOf("partial" to JSONObjectPattern(pattern = metadataPattern + innerHttpPatterns)))
        return partialPattern.matches(rawValue, resolver)
    }

    private fun resolveHttpPatterns(json: Map<String, Value>): Map<String, Pattern> {
        val reqKey = if (json.containsKey(OLD_MOCK_HTTP_REQUEST_KEY)) OLD_MOCK_HTTP_REQUEST_KEY else MOCK_HTTP_REQUEST
        val resKey = if (json.containsKey(OLD_MOCK_HTTP_RESPONSE_KEY)) OLD_MOCK_HTTP_RESPONSE_KEY else MOCK_HTTP_RESPONSE
        return mapOf(reqKey to JSONObjectPattern(httpRequestPattern), resKey to JSONObjectPattern(httpResponsePattern))
    }
}
