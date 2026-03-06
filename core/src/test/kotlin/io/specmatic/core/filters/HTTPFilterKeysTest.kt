package io.specmatic.core.filters

import io.specmatic.core.HttpPathPattern
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Scenario
import io.specmatic.core.ScenarioInfo
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

class HTTPFilterKeysTest {

    @Test
    fun `fromKey returns correct enum for exact match`() {
        val result = HTTPFilterKeys.fromKey("PATH")
        assertThat(result).isEqualTo(HTTPFilterKeys.PATH)
    }

    @Test
    fun `fromKey returns correct enum for prefix match`() {
        val result = HTTPFilterKeys.fromKey("PARAMETERS.HEADER.SomeValue")
        assertThat(result).isEqualTo(HTTPFilterKeys.PARAMETERS_HEADER_WITH_SPECIFIC_VALUE)
    }

    @Test
    fun `fromKey throws exception for invalid key`() {
        assertThatThrownBy { HTTPFilterKeys.fromKey("INVALID.KEY") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid filter key: INVALID.KEY")
    }

    @Test
    fun `fromKey returns correct enum for another exact match`() {
        val result = HTTPFilterKeys.fromKey("METHOD")
        assertThat(result).isEqualTo(HTTPFilterKeys.METHOD)
    }

    @Test
    fun `fromKey returns correct enum for another prefix match`() {
        val result = HTTPFilterKeys.fromKey("PARAMETERS.QUERY.SomeValue")
        assertThat(result).isEqualTo(HTTPFilterKeys.PARAMETERS_QUERY_WITH_SPECIFIC_VALUE)
    }

    @Test
    fun `fromKey key cannot be empty`() {
        assertThatThrownBy { HTTPFilterKeys.fromKey("") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid filter key: ")
    }

    @Test
    fun `fromKey key cannot be blank`() {
        assertThatThrownBy { HTTPFilterKeys.fromKey("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid filter key:    ")
    }

    @Test
    fun `fromKey key is case sensitive`() {
        assertThatThrownBy { HTTPFilterKeys.fromKey("path") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid filter key: path")
    }

    @Test
    fun `fromKey invalid key`() {
        assertThatThrownBy { HTTPFilterKeys.fromKey("INVALID_KEY") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid filter key: INVALID_KEY")
    }

    @ParameterizedTest(name = "scenario path \"{0}\" with filter \"{1}\" should be {2}")
    @MethodSource("pathFilterCases")
    fun `PATH includes handles trailing slash and path parameter edge cases`(scenario: Scenario, filterValue: String, expected: Boolean) {
        assertThat(HTTPFilterKeys.PATH.includes(scenario, "PATH", filterValue)).isEqualTo(expected)
    }

    @Test
    fun `caseInsensitiveContains returns true when exact string is present`() {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseInsensitiveContains("banana")).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "BANANA",
        "APPLE",
        "Cherry"
    )
    fun `caseInsensitiveContains returns true when string is present with different casing`(needle: String) {
        val items = listOf("apple", "BANANA", "cherry")
        assertThat(items.caseInsensitiveContains(needle)).isTrue()
    }

    @Test
    fun `caseInsensitiveContains returns false when string is not present`() {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseInsensitiveContains("orange")).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "apple",
        "cherry",
        "banana"
    )
    fun `caseInsensitiveContains handles trailing question mark in haystack items`(needle: String) {
        val items = listOf("apple?", "banana", "cherry?")
        assertThat(items.caseInsensitiveContains(needle)).isTrue()
    }

    @Test
    fun `caseInsensitiveContains works with empty lists`() {
        val emptyItems = emptyList<String>()
        assertThat(emptyItems.caseInsensitiveContains("anything")).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "apple",
        "BANANA",
        "Cherry"
    )
    fun `caseInsensitiveContains handles whitespace and trimming`(needle: String) {
        val items = listOf("  apple  ", "\tbanana\t", " cherry ")
        assertThat(items.caseInsensitiveContains(needle)).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "apple",
        "BANANA",
        "Cherry"
    )
    fun `caseInsensitiveContains handles whitespace with question marks`(needle: String) {
        val items = listOf("  apple?  ", "\tbanana?\t", " cherry? ")
        assertThat(items.caseInsensitiveContains(needle)).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "apple?",
        "banana?"
    )
    fun `caseInsensitiveContains does not match needle with question mark when haystack has no question mark`(needle: String) {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseInsensitiveContains(needle)).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "banana,true",
        "apple,true"
    )
    fun `caseSensitiveContains returns true when exact string is present`(needle: String, expected: Boolean) {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "APPLE,false",
        "banana,false",
        "Cherry,false",
        "apple,true",
        "BANANA,true",
        "cherry,true"
    )
    fun `caseSensitiveContains returns false when string is present with different casing`(needle: String, expected: Boolean) {
        val items = listOf("apple", "BANANA", "cherry")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "orange,false",
        "grape,false"
    )
    fun `caseSensitiveContains returns false when string is not present`(needle: String, expected: Boolean) {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "apple,true",
        "cherry,true",
        "banana,true"
    )
    fun `caseSensitiveContains handles trailing question mark in haystack items`(needle: String, expected: Boolean) {
        val items = listOf("apple?", "banana", "cherry?")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @Test
    fun `caseSensitiveContains works with empty lists`() {
        val emptyItems = emptyList<String>()
        assertThat(emptyItems.caseSensitiveContains("anything")).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "apple,true",
        "banana,true",
        "cherry,true",
        "APPLE,false"
    )
    fun `caseSensitiveContains handles whitespace and trimming`(needle: String, expected: Boolean) {
        val items = listOf("  apple  ", "\tbanana\t", " cherry ")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "apple,true",
        "banana,true",
        "cherry,true",
        "APPLE,false"
    )
    fun `caseSensitiveContains handles whitespace with question marks`(needle: String, expected: Boolean) {
        val items = listOf("  apple?  ", "\tbanana?\t", " cherry? ")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "apple?,false",
        "banana?,false"
    )
    fun `caseSensitiveContains does not match needle with question mark when haystack has no question mark`(needle: String, expected: Boolean) {
        val items = listOf("apple", "banana", "cherry")
        assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "apple?,true",
        "banana,true"
    )
    fun `both functions handle multiple question marks correctly`(needle: String, expected: Boolean) {
        val items = listOf("apple??", "banana?", "cherry")
        if(needle == "apple?") {
            assertThat(items.caseInsensitiveContains(needle)).isEqualTo(expected)
            assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
        } else {
            assertThat(items.caseInsensitiveContains(needle)).isEqualTo(expected)
            assertThat(items.caseSensitiveContains(needle)).isEqualTo(expected)
        }
    }

    @Test
    fun `both functions handle empty strings in collections`() {
        val items = listOf("", "apple", "banana")
        assertThat(items.caseInsensitiveContains("")).isTrue()
        assertThat(items.caseSensitiveContains("")).isTrue()
    }

    @Test
    fun `both functions handle collections with only empty and whitespace strings`() {
        val items = listOf("", "   ", "\t\t")
        assertThat(items.caseInsensitiveContains("")).isTrue()
        assertThat(items.caseSensitiveContains("")).isTrue()
    }

    @ParameterizedTest(name = "scenario path \"{0}\" with parameter \"{1}\" should be {2}")
    @MethodSource("parametersPathCases")
    fun `PARAMETERS_PATH includes should handle templated and interpolated path parameter styles`(scenario: Scenario, filterValue: String, expected: Boolean) {
        assertThat(HTTPFilterKeys.PARAMETERS_PATH.includes(scenario, "PARAMETERS.PATH", filterValue)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "request content type in scenario \"{0}\" should match filter value \"{1}\" as {2}")
    @CsvSource(
        "application/json,application/json,true",
        "application/json,application/*,true",
        "application/json,text/*,false",
        "text/plain,text/plain,true",
        "text/plain,application/*,false",
        "invalid-content-type,application/*,false",
        "application/json,not a content type,false"
    )
    fun `REQUEST_BODY_CONTENT_TYPE includes should safely match scenario request content types`(requestContentType: String, filterValue: String, expected: Boolean) {
        val scenario = scenarioWithContentTypes(requestContentType, "application/json")
        assertThat(HTTPFilterKeys.REQUEST_BODY_CONTENT_TYPE.includes(scenario, "REQUEST-BODY.CONTENT-TYPE", filterValue)).isEqualTo(expected)
    }

    @Test
    fun `REQUEST_BODY_CONTENT_TYPE includes should return false when scenario request content type is absent`() {
        val scenario = scenarioWithContentTypes(null, "application/json")
        assertThat(HTTPFilterKeys.REQUEST_BODY_CONTENT_TYPE.includes(scenario, "REQUEST-BODY.CONTENT-TYPE", "application/json")).isFalse()
    }

    @ParameterizedTest(name = "blank request filter value should not match scenario request content type: \"{0}\"")
    @CsvSource(
        "'',false",
        "'   ',false"
    )
    fun `REQUEST_BODY_CONTENT_TYPE includes should return false when request filter value is blank for scenario`(filterValue: String, expected: Boolean) {
        val scenario = scenarioWithContentTypes("application/json", "application/json")
        assertThat(HTTPFilterKeys.REQUEST_BODY_CONTENT_TYPE.includes(scenario, "REQUEST-BODY.CONTENT-TYPE", filterValue)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "response content type in scenario \"{0}\" should match filter value \"{1}\" as {2}")
    @CsvSource(
        "application/json,application/json,true",
        "application/json,application/*,true",
        "application/json,text/*,false",
        "application/xml,application/*,true",
        "application/xml,text/*,false",
        "invalid-content-type,application/*,false",
        "application/json,not a content type,false"
    )
    fun `RESPONSE_CONTENT_TYPE includes should safely match scenario response content types`(responseContentType: String, filterValue: String, expected: Boolean) {
        val scenario = scenarioWithContentTypes("application/json", responseContentType)
        assertThat(HTTPFilterKeys.RESPONSE_CONTENT_TYPE.includes(scenario, "RESPONSE.CONTENT-TYPE", filterValue)).isEqualTo(expected)
    }

    @Test
    fun `RESPONSE_CONTENT_TYPE includes should return false when scenario response content type is absent`() {
        val scenario = scenarioWithContentTypes("application/json", null)
        assertThat(HTTPFilterKeys.RESPONSE_CONTENT_TYPE.includes(scenario, "RESPONSE.CONTENT-TYPE", "application/json")).isFalse()
    }

    @ParameterizedTest(name = "blank response filter value should not match scenario response content type: \"{0}\"")
    @CsvSource(
        "'',false",
        "'   ',false"
    )
    fun `RESPONSE_CONTENT_TYPE includes should return false when response filter value is blank for scenario`(filterValue: String, expected: Boolean) {
        val scenario = scenarioWithContentTypes("application/json", "application/json")
        assertThat(HTTPFilterKeys.RESPONSE_CONTENT_TYPE.includes(scenario, "RESPONSE.CONTENT-TYPE", filterValue)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "stub request body should match request filter value \"{0}\" as {1}")
    @CsvSource(
        "application/json,true",
        "application/*,true",
        "text/*,false",
        "not a content type,false"
    )
    fun `REQUEST_BODY_CONTENT_TYPE includes should safely match stub request body content types`(filterValue: String, expected: Boolean) {
        val stub = ScenarioStub(request = HttpRequest(body = JSONObjectValue()))
        assertThat(HTTPFilterKeys.REQUEST_BODY_CONTENT_TYPE.includes(stub, "REQUEST-BODY.CONTENT-TYPE", filterValue)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "blank request filter value should not match stub request content type: \"{0}\"")
    @CsvSource(
        "'',false",
        "'   ',false"
    )
    fun `REQUEST_BODY_CONTENT_TYPE includes should return false when request filter value is blank for stub`(filterValue: String, expected: Boolean) {
        val stub = ScenarioStub(request = HttpRequest(body = JSONObjectValue()))
        assertThat(HTTPFilterKeys.REQUEST_BODY_CONTENT_TYPE.includes(stub, "REQUEST-BODY.CONTENT-TYPE", filterValue)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "stub response body should match response filter value \"{0}\" as {1}")
    @CsvSource(
        "application/json,true",
        "application/*,true",
        "text/*,false",
        "not a content type,false"
    )
    fun `RESPONSE_CONTENT_TYPE includes should safely match stub response body content types`(filterValue: String, expected: Boolean) {
        val stub = ScenarioStub(response = HttpResponse(200, body = JSONObjectValue()))
        assertThat(HTTPFilterKeys.RESPONSE_CONTENT_TYPE.includes(stub, "RESPONSE.CONTENT-TYPE", filterValue)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "blank response filter value should not match stub response content type: \"{0}\"")
    @CsvSource(
        "'',false",
        "'   ',false"
    )
    fun `RESPONSE_CONTENT_TYPE includes should return false when response filter value is blank for stub`(filterValue: String, expected: Boolean) {
        val stub = ScenarioStub(response = HttpResponse(200, body = JSONObjectValue()))
        assertThat(HTTPFilterKeys.RESPONSE_CONTENT_TYPE.includes(stub, "RESPONSE.CONTENT-TYPE", filterValue)).isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        fun pathFilterCases() = listOf(
            Arguments.of(scenarioForPath("/test/status"), "/test/status", true),
            Arguments.of(scenarioForPath("/test/status"), "/test/{id}/status", false),
            Arguments.of(scenarioForPath("/order/(id:string)/"), "/order/{id}/", true),
            Arguments.of(scenarioForPath("/order/(id:string)"), "/order/{id}", true),
            Arguments.of(scenarioForPath("/order/(id:string)/"), "/order/*/", true),
            Arguments.of(scenarioForPath("/order/(id:string)/"), "/order/*", true),
            Arguments.of(scenarioForPath("/order/(id:string)/events"), "/order/*", true),
            Arguments.of(scenarioForPath("/order/(id:string)/"), "/order/{id}", false),
            Arguments.of(scenarioForPath("/order/(id:string)"), "/order/{id}/", false),
            Arguments.of(scenarioForPath("/order/(id:string)/"), "/ORDER/{id}/", false),
            Arguments.of(scenarioForPath("/order/(id:string)/"), "/order/{orderId}/", false),
            Arguments.of(scenarioForPath("/order/(id:string)/events"), "/order/{id}", false),
            Arguments.of(scenarioForPath("/test/(id1:string),(id2:string)/status"), "/test/{id1},{id2}/status", true),
            Arguments.of(scenarioForPath("/test/(id1:string),(id2:string)/status"), "/test/{id1}-{id2}/status", false),
        )

        @JvmStatic
        fun parametersPathCases() = listOf(
            Arguments.of(scenarioForPath("/catalog/item-(sku:string)/"), "sku", true),
            Arguments.of(scenarioForPath("/catalog/item/(sku:string)"), "sku", true),
            Arguments.of(scenarioForPath("/catalog/item-(sku:string)/"), "catalog", false),
            Arguments.of(scenarioForPath("/catalog/item/(sku:string)"), "catalog", false),
            Arguments.of(scenarioForPath("/test/(id1:string),(id2:string)/status"), "id1", true),
            Arguments.of(scenarioForPath("/test/(id1:string),(id2:string)/status"), "id2", true),
            Arguments.of(scenarioForPath("/test/(id1:string)/(id2:string)/status"), "id1", true),
            Arguments.of(scenarioForPath("/test/(id1:string)/(id2:string)/status"), "id2", true),
            Arguments.of(scenarioForPath("/test/(id1:string),(id2:string)/status"), "id3", false),
            Arguments.of(scenarioForPath("/test/(id1:string)/(id2:string)/status"), "id3", false),
        )

        private fun scenarioForPath(path: String): Scenario {
            return Scenario(
                ScenarioInfo(
                    scenarioName = "GET $path",
                    httpRequestPattern = HttpRequestPattern(method = "GET", httpPathPattern = HttpPathPattern.from(path)),
                    httpResponsePattern = HttpResponsePattern(status = 200),
                    protocol = SpecmaticProtocol.HTTP,
                    specType = SpecType.OPENAPI
                )
            )
        }

        private fun scenarioWithContentTypes(requestContentType: String?, responseContentType: String?): Scenario {
            return Scenario(
                ScenarioInfo(
                    specType = SpecType.OPENAPI,
                    scenarioName = "POST /orders",
                    protocol = SpecmaticProtocol.HTTP,
                    httpRequestPattern = HttpRequestPattern(method = "POST", httpPathPattern = HttpPathPattern.from("/orders"), headersPattern = HttpHeadersPattern(contentType = requestContentType)),
                    httpResponsePattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(contentType = responseContentType)),
                )
            )
        }
    }
}
