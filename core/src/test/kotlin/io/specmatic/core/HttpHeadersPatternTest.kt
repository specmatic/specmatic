package io.specmatic.core

import io.specmatic.GENERATION
import io.specmatic.core.pattern.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.ktor.util.reflect.*
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.value.*
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.function.Consumer
import kotlin.collections.HashMap

internal class HttpHeadersPatternTest {
    @Test
    fun `should match a header`() {
        val httpHeaders = HttpHeadersPattern(mapOf("key" to stringToPattern("value", "key")))
        val headers: HashMap<String, String> = HashMap()
        headers["key"] = "value"

        val result = httpHeaders.matches(headers, Resolver())
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `header name match should be case insensitive`() {
        val httpHeaders = HttpHeadersPattern(mapOf("key" to stringToPattern("value", "key")))
        val headers: HashMap<String, String> = HashMap()
        headers["KEY"] = "value"

        val result = httpHeaders.matches(headers, Resolver())
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `ancestor header name match should be case insensitive`() {
        val httpHeaders = HttpHeadersPattern(mapOf("key" to stringToPattern("value", "key")), mapOf("key" to stringToPattern("value", "key")))
        val headers: HashMap<String, String> = HashMap()
        headers["KEY"] = "value"

        val result = httpHeaders.matches(headers, Resolver())
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `unexpected header match should be case insensitive`() {
        val httpHeaders = HttpHeadersPattern(mapOf("key" to stringToPattern("value", "key")))
        val headers: HashMap<String, String> = HashMap()
        headers["key"] = "value"
        headers["unexpected"] = "value"
        assertThat(httpHeaders.matches(headers, Resolver()) is Result.Success).isTrue()
    }

    @Test
    fun `should not accept duplicate headers with different case`() {
        assertThatThrownBy {
            HttpHeadersPattern(mapOf("key" to stringToPattern("value", "key"), "KEY" to stringToPattern("value", "KEY")))
        }.satisfies(Consumer {
            assertThat(it).instanceOf(ContractException::class)
        })
    }

    @Test
    fun `should pattern match a numeric string`() {
        val httpHeaders = HttpHeadersPattern(mapOf("key" to stringToPattern("(number)", "key"), "expected" to stringToPattern("(number)", "expected")))
        val headers: HashMap<String, String> = HashMap()
        headers["key"] = "123"
        headers["Expected"] = "123"
        assertThat(httpHeaders.matches(headers, Resolver()) is Result.Success).isTrue()
    }

    @Test
    fun `should pattern match string`() {
        val httpHeaders = HttpHeadersPattern(mapOf("key" to stringToPattern("(string)", "key")))
        val headers: HashMap<String, String> = HashMap()
        headers["key"] = "abc123"
        assertThat(httpHeaders.matches(headers, Resolver()) is Result.Success).isTrue()
    }

    @Test
    fun `should not pattern match a numeric string when value has alphabets`() {
        val httpHeaders = HttpHeadersPattern(mapOf("key" to stringToPattern("(number)", "key")))
        val headers: HashMap<String, String> = HashMap()
        headers["key"] = "abc"
        httpHeaders.matches(headers, Resolver()).let {
            assertThat(it is Result.Failure).isTrue()
            assertThat((it as Result.Failure).toMatchFailureDetails()).isEqualTo(MatchFailureDetails(
                listOf("HEADER", "key"), listOf("Expected number, actual was \"abc\""))
            )
        }
    }

    @Test
    fun `should not match when header is not present`() {
        val httpHeaders = HttpHeadersPattern(mapOf("key" to stringToPattern("(number)", "key")))
        val headers: HashMap<String, String> = HashMap()
        headers["anotherKey"] = "123"
        httpHeaders.matches(headers, Resolver()).let {
            assertThat(it is Result.Failure).isTrue()
            assertThat((it as Result.Failure).toMatchFailureDetails()).isEqualTo(MatchFailureDetails(
                listOf("HEADER", "key"), listOf("Expected header named \"key\" was missing"))
            )
        }
    }

    @Test
    fun `should not add numericString pattern to the resolver`() {
        val httpHeaders = HttpHeadersPattern(mapOf("key" to stringToPattern("(number)", "key")))
        val resolver = Resolver()
        httpHeaders.matches(HashMap(), resolver)
        assertThat(resolver.matchesPattern(null, resolver.getPattern("(number)"), StringValue("123")) is Result.Failure).isTrue()
    }

    @Test
    fun `should generate values`() {
        val httpHeaders = HttpHeadersPattern(
                mapOf("exactKey" to stringToPattern("value", "exactKey"), "numericKey" to stringToPattern("(number)", "numericKey"), "stringKey" to stringToPattern("(string)", "stringKey"), "serverStateKey" to stringToPattern("(string)", "serverStateKey")))
        val facts: HashMap<String, Value> = hashMapOf("serverStateKey" to StringValue("serverStateValue"))
        val resolver = Resolver(facts)
        val generatedResult = httpHeaders.generate(resolver)
        generatedResult.let {
            assertThat(it["exactKey"]).isEqualTo("value")
            assertThat(it["numericKey"]).matches("[0-9]+")
            assertThat(it["stringKey"]).matches("[0-9a-zA-Z]+")
            assertThat(it["serverStateKey"]).isEqualTo("serverStateValue")
        }
    }

    @Test
    fun `should generate json object values as unformatted strings`() {
        val httpHeaders = HttpHeadersPattern(
            mapOf("jsonHeaderKey" to ExactValuePattern(
                JSONObjectValue(jsonObject = mapOf("key" to StringValue("value"))))
            )
        )
        val generatedValue = httpHeaders.generate(Resolver())
        assertThat(generatedValue["jsonHeaderKey"]).isEqualTo("""{"key":"value"}""")
    }

    @Test
    fun `should not attempt to validate or match additional headers`() {
        val expectedHeaders = HttpHeadersPattern(mapOf("Content-Type" to stringToPattern("(string)", "Content-Type")))

        val actualHeaders = HashMap<String, String>().apply {
            put("Content-Type", "application/json")
            put("X-Unspecified-Header", "Should be ignored")
        }

        assertThat(expectedHeaders.matches(actualHeaders, Resolver()).isSuccess()).isTrue()
    }

    @Test
    fun `should validate extra headers when mocking`() {
        val expectedHeaders = HttpHeadersPattern(mapOf("X-Expected" to stringToPattern("(string)", "X-Expected")))

        val actualHeaders = HashMap<String, String>().apply {
            put("X-Expected", "application/json")
            put("X-Unspecified-Header", "Can't accept this header in a mock")
        }

        assertThat(expectedHeaders.matches(actualHeaders, Resolver(findKeyErrorCheck = DefaultKeyCheck.disableOverrideUnexpectedKeycheck()))).isInstanceOf(Result.Failure::class.java)
    }

    @Tag(GENERATION)
    @Test
    fun `should generate new header objects given an empty row`() {
        val headers = HttpHeadersPattern(mapOf("Content-Type" to stringToPattern("(string)", "Content-Type")))
        val newHeaders = headers.newBasedOn(Row(), Resolver()).toList()
        assertEquals("(string)", newHeaders[0].value.pattern.getValue("Content-Type").toString())
    }

    @Tag(GENERATION)
    @Test
    fun `should generate new header object with the value of the example in the given row`() {
        val headers = HttpHeadersPattern(mapOf("X-TraceID" to StringPattern()))
        val newHeaders = headers.newBasedOn(Row(mapOf("X-TraceID" to "123")), Resolver()).toList()
        assertThat(newHeaders[0].value.pattern.getValue("X-TraceID")).isEqualTo(ExactValuePattern(StringValue("123")))
    }

    @Tag(GENERATION)
    @Test
    fun `should generate two header object given one optional header and an empty row`() {
        val headers = HttpHeadersPattern(mapOf("X-TraceID" to StringPattern(), "X-Identifier?" to StringPattern()))
        val newHeaders = headers.newBasedOn(Row(), Resolver()).toList().map { it.value }

        assertThat(newHeaders).containsExactlyInAnyOrder(
            HttpHeadersPattern(mapOf("X-TraceID" to StringPattern())),
            HttpHeadersPattern(mapOf("X-TraceID" to StringPattern(), "X-Identifier" to StringPattern()))
        )
    }

    @Tag(GENERATION)
    @Test
    fun `should generate only one header object given one optional header an example of only the mandatory header`() {
        val headers = HttpHeadersPattern(mapOf("X-TraceID" to StringPattern(), "X-Identifier?" to StringPattern()))
        val newHeaders = headers.newBasedOn(
            Row(mapOf("X-TraceID" to "123")),
            Resolver()
        ).toList().map { it.value }

        assertThat(newHeaders).containsExactly(
            HttpHeadersPattern(mapOf("X-TraceID" to ExactValuePattern(StringValue("123")))),
        )
    }

    @Tag(GENERATION)
    @Test
    fun `should generate one header object given one optional header an example of the optional header`() {
        val headers = HttpHeadersPattern(mapOf("X-TraceID" to StringPattern()))
        val newHeaders = headers.newBasedOn(Row(mapOf("X-TraceID" to "123")), Resolver()).toList()
        assertThat(newHeaders[0].value.pattern.getValue("X-TraceID")).isEqualTo(ExactValuePattern(StringValue("123")))
    }

    @Tag(GENERATION)
    @Test
    fun `should generate negative values for a string`() {
        val headers = HttpHeadersPattern(mapOf("X-TraceID" to StringPattern()))
        val newHeaders = headers.negativeBasedOn(Row(), Resolver(), BreadCrumb.PARAM_HEADER.value).map { it.value }.toList()

        assertThat(newHeaders).containsExactlyInAnyOrder(
            HttpHeadersPattern(mapOf())
        )
    }

    @Tag(GENERATION)
    @Test
    fun `should generate negative values for a number`() {
        val headers = HttpHeadersPattern(mapOf("X-TraceID" to NumberPattern()))
        val newHeaders = headers.negativeBasedOn(Row(), Resolver(), BreadCrumb.PARAM_HEADER.value).map { it.value }.toList()

        assertThat(newHeaders).containsExactlyInAnyOrder(
            HttpHeadersPattern(mapOf("X-TraceID" to StringPattern())),
            HttpHeadersPattern(mapOf("X-TraceID" to BooleanPattern())),
            HttpHeadersPattern(mapOf())
        )
    }

    @Test
    fun `given ancestor headers from the creators header set the pattern should skip past all headers in the value map not in the ancestors list`() {
        val pattern = HttpHeadersPattern(mapOf("X-Required-Header" to StringPattern()), mapOf("X-Required-Header" to StringPattern()))
        val headers = mapOf("X-Required-Header" to "some value", "X-Extraneous-Header" to "some other value")

        assertThat(pattern.matches(headers, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should match if optional headers are present`() {
        val pattern = HttpHeadersPattern(mapOf("X-Optional-Header?" to StringPattern()))
        val headers = mapOf("X-Optional-Header?" to "some value")

        assertThat(pattern.matches(headers, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should match if optional headers are absent`() {
        val pattern = HttpHeadersPattern(mapOf("X-Optional-Header?" to StringPattern()))
        val headers = emptyMap<String, String>()

        assertThat(pattern.matches(headers, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Tag(GENERATION)
    @Test
    fun `an optional header should result in 2 new header patterns for newBasedOn`() {
        val pattern = HttpHeadersPattern(mapOf("X-Optional?" to StringPattern()))
        val list = pattern.newBasedOn(Row(), Resolver()).toList()

        assertThat(list).hasSize(2)

        val flags = list.map {
            when {
                it.value.pattern.contains("X-Optional") -> "with"
                else -> "without"
            }
        }

        flagsContain(flags, listOf("with", "without"))
    }

    @Test
    fun `it should encompass itself`() {
        val headersType = HttpHeadersPattern(mapOf("X-Required" to StringPattern()))
        assertThat(headersType.encompasses(headersType, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `a header pattern with required headers should encompass one with extra headers`() {
        val bigger = HttpHeadersPattern(mapOf("X-Required" to StringPattern()))
        val smaller = HttpHeadersPattern(mapOf("X-Required" to StringPattern(), "X-Extra" to StringPattern()))
        assertThat(bigger.encompasses(smaller, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `a header pattern with an optional header should match one without that header`() {
        val bigger = HttpHeadersPattern(mapOf("X-Required" to StringPattern(), "X-Optional?" to NumberPattern()))
        val smaller = HttpHeadersPattern(mapOf("X-Required" to StringPattern()))
        val result = bigger.encompasses(smaller, Resolver(), Resolver())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `a header pattern with an optional header should match one with that header if present`() {
        val bigger = HttpHeadersPattern(mapOf("X-Required" to StringPattern(), "X-Optional?" to NumberPattern()))
        val smaller = HttpHeadersPattern(mapOf("X-Required" to StringPattern(), "X-Optional" to StringPattern()))
        val result = bigger.encompasses(smaller, Resolver(), Resolver())
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should match a pattern only when resolver has mock matching on`() {
        val headersPattern = HttpHeadersPattern(mapOf("X-Data" to NumberPattern()))
        assertThat(headersPattern.matches(mapOf("X-Data" to "10"), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(headersPattern.matches(mapOf("X-Data" to "(number)"), Resolver(mockMode = true))).isInstanceOf(Result.Success::class.java)
        assertThat(headersPattern.matches(mapOf("X-Data" to "(number)"), Resolver(mockMode = false))).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `unexpected standard http headers are allowed and will not break a match check`() {
        val headersPattern = HttpHeadersPattern(mapOf("X-Data" to StringPattern()), ancestorHeaders = mapOf("X-Data" to StringPattern(), "Content-Type?" to StringPattern()))
        val resolver = Resolver()
        assertThat(headersPattern.matches(mapOf("X-Data" to "data", "Content-Type" to "text/plain"), resolver)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `unexpected but non-optional standard http headers are allowed and will break a match check`() {
        val headersPattern = HttpHeadersPattern(mapOf("X-Data" to StringPattern()), ancestorHeaders = mapOf("X-Data" to StringPattern(), "Content-Type" to StringPattern()))
        val resolver = Resolver(findKeyErrorCheck = DefaultKeyCheck.disableOverrideUnexpectedKeycheck())
        assertThat(headersPattern.matches(mapOf("X-Data" to "data", "Content-Type" to "text/plain"), resolver)).isInstanceOf(Result.Failure::class.java)
    }

    @Nested
    inner class ReturnMultipleErrrors {
        private val headersPattern = HttpHeadersPattern(mapOf("X-Data" to StringPattern(), "Y-Data" to NumberPattern()), ancestorHeaders = mapOf("X-Data" to StringPattern(), "Y-Data" to NumberPattern()))
        val resolver = Resolver()
        val result = headersPattern.matches(mapOf("Y-Data" to "data"), resolver)

        @Test
        fun `should return as many errors as there are problems`() {
            result as Result.Failure

            assertThat(result.toMatchFailureDetailList()).hasSize(2)
        }

        @Test
        fun `errors should mention the name of header`() {
            result as Result.Failure

            assertThat(result.toFailureReport().toText()).contains(">> HEADER.X-Data")
            assertThat(result.toFailureReport().toText()).contains(">> HEADER.Y-Data")

            println(result.toFailureReport().toText())
        }

        @Test
        fun `key errors appear before value errors`() {
            result as Result.Failure

            val resultText = result.toFailureReport().toText()

            assertThat(resultText.indexOf(">> HEADER.X-Data")).isLessThan(resultText.indexOf(">> HEADER.Y-Data"))

            println(result.toFailureReport().toText())
        }
    }

    @Test
    fun `all missing header backward compatibility errors together`() {
        val older = HttpHeadersPattern()
        val newer = HttpHeadersPattern(mapOf("X-Data" to StringPattern(), "Y-Data" to NumberPattern()), ancestorHeaders = mapOf("X-Data" to StringPattern(), "Y-Data" to NumberPattern()))

        val resultText = newer.encompasses(older, Resolver(), Resolver()).reportString()
        println(resultText)

        assertThat(resultText).contains("HEADER.X-Data")
        assertThat(resultText).contains("HEADER.Y-Data")
    }

    @Test
    fun `header presence and value backward compatibility errors together`() {
        val older = HttpHeadersPattern(mapOf("X-Data" to NumberPattern()))
        val newer = HttpHeadersPattern(mapOf("X-Data" to StringPattern(), "Y-Data" to NumberPattern()), ancestorHeaders = mapOf("X-Data" to StringPattern(), "Y-Data" to NumberPattern()))

        val resultText = newer.encompasses(older, Resolver(), Resolver()).reportString()
        println(resultText)

        assertThat(resultText).contains("HEADER.X-Data")
        assertThat(resultText).contains("HEADER.Y-Data")
    }

    @Test
    fun `all header value backward compatibility errors together`() {
        val older = HttpHeadersPattern(mapOf("X-Data" to StringPattern(), "Y-Data" to NumberPattern()), ancestorHeaders = mapOf("X-Data" to StringPattern(), "Y-Data" to NumberPattern()))
        val newer = HttpHeadersPattern(mapOf("X-Data" to NumberPattern(), "Y-Data" to StringPattern()), ancestorHeaders = mapOf("X-Data" to StringPattern(), "Y-Data" to NumberPattern()))

        val resultText = newer.encompasses(older, Resolver(), Resolver()).reportString()

        assertThat(resultText).contains("HEADER.X-Data")
        assertThat(resultText).contains("HEADER.Y-Data")
    }

    @Test
    fun `should match content type without charset`() {
        val headersPattern = HttpHeadersPattern(mapOf("X-Data" to NumberPattern()), contentType = "application/json")
        assertThat(headersPattern.matches(mapOf("X-Data" to "10", "Content-Type" to "application/json"), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should match content type with charset`() {
        val headersPattern = HttpHeadersPattern(mapOf("X-Data" to NumberPattern()), contentType = "application/json")
        assertThat(headersPattern.matches(mapOf("X-Data" to "10", "Content-Type" to "application/json; charset=UTF-8"), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should not match a different content type`() {
        val headersPattern = HttpHeadersPattern(mapOf("X-Data" to NumberPattern()), contentType = "application/json")
        assertThat(headersPattern.matches(mapOf("X-Data" to "10", "Content-Type" to "text/plain"), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Nested
    inner class NewBasedOnTests {
        @Test
        fun `newBasedOn should include additional headers from example`() {
            val headers = HttpHeadersPattern(mapOf("X-Existing" to StringPattern()))
            val row = Row(
                requestExample = HttpRequest(headers = mapOf(
                    "X-Existing" to "existingValue",
                    "X-New" to "newValue"
                ))
            )

            val newHeaders = headers.newBasedOn(row, Resolver()).toList()

            assertThat(newHeaders).hasSize(1)
            val newHeader = newHeaders[0].value
            assertThat(newHeader.pattern).containsKeys("X-Existing", "X-New")
        }

        @Test
        fun `newBasedOn should handle multiple additional headers`() {
            val headers = HttpHeadersPattern(mapOf("X-Existing" to StringPattern()))
            val row = Row(
                requestExample = HttpRequest(headers = mapOf(
                    "X-Existing" to "existingValue",
                    "X-New1" to "newValue1",
                    "X-New2" to "newValue2"
                ))
            )

            val newHeaders = headers.newBasedOn(row, Resolver()).toList()

            assertThat(newHeaders).hasSize(1)
            val newHeader = newHeaders[0].value
            assertThat(newHeader.pattern).containsKeys("X-Existing", "X-New1", "X-New2")
        }

        @Test
        fun `newBasedOn should not add additional headers when no new headers in example`() {
            val headers = HttpHeadersPattern(mapOf("X-Existing" to StringPattern()))
            val row = Row(
                requestExample = HttpRequest(headers = mapOf(
                    "X-Existing" to "existingValue"
                ))
            )

            val newHeaders = headers.newBasedOn(row, Resolver()).toList()

            assertThat(newHeaders).hasSize(1)
            val newHeader = newHeaders[0].value
            assertThat(newHeader.pattern).containsOnlyKeys("X-Existing")
        }

        @Test
        fun `newBasedOn should handle row without requestExample`() {
            val headers = HttpHeadersPattern(mapOf("X-Existing" to StringPattern()))
            val row = Row()

            val newHeaders = headers.newBasedOn(row, Resolver()).toList()

            assertThat(newHeaders).hasSize(1)
            val newHeader = newHeaders[0].value
            assertThat(newHeader.pattern).containsOnlyKeys("X-Existing")
            assertThat(newHeader.pattern["X-Existing"]).isInstanceOf(StringPattern::class.java)
        }

        @Test
        fun `should use allOrNothingCombination when generating patterns`() {
            val headers = HttpHeadersPattern(1.until(11).associate { index ->
                "Header${index}${"?".takeIf { index % 2 == 0 }}" to NumberPattern()
            })
            val newHeaders = headers.newBasedOn(Row(), Resolver()).toList()

            assertThat(newHeaders).hasSize(2)
            assertThat(newHeaders.first().value.pattern).hasSize(10).allSatisfy { key, _ ->
                assertThat(isOptional(key)).isFalse()
            }
            assertThat(newHeaders.last().value.pattern).hasSize(5).allSatisfy { key, _ ->
                assertThat(isOptional(key)).isFalse()
            }
        }
    }

    @Test
    fun `test fix for, +ve post scenario duplication, due to additional headers, for content-type`() {
        var positiveCount = 0
        val feature = OpenApiSpecification.fromFile("src/test/resources/openapi/specs_for_additional_headers_in_examples/additional_headers_test_content_type.yaml").toFeature().enableGenerativeTesting()
        feature.executeTests(object : TestExecutor{
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.toLogString())
                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                if(!scenario.isNegative) positiveCount++
                println(scenario.testDescription())
            }
        })
        assertThat(positiveCount).isEqualTo(1)
    }

    @Test
    fun `test fix for, +ve post scenario duplication, due to additional headers, for security headers`() {
        var positiveCount = 0
        var securityHeadersFound = false

        val feature = OpenApiSpecification
            .fromFile("src/test/resources/openapi/specs_for_additional_headers_in_examples/additional_headers_test_security_scheme.yaml")
            .toFeature()
            .enableGenerativeTesting()

        feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.toLogString())

                // Check for automatically added security headers
                val hasAuthHeader = request.headers.any {
                    it.key.equals("Authorization", ignoreCase = true)
                }

                if (hasAuthHeader) {
                    securityHeadersFound = true
                }

                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                if (!scenario.isNegative) positiveCount++
                println(scenario.testDescription())
            }
        })

        assertThat(positiveCount).isEqualTo(1)
        assertThat(securityHeadersFound).isTrue()
    }

    @Nested
    inner class FixValueTests {
        @Test
        fun `should be able to add missing values`() {
            val httpHeaders = HttpHeadersPattern(mapOf("key" to ExactValuePattern(StringValue("value"))))
            val invalidValue = emptyMap<String, String>()
            val fixedValue = httpHeaders.fixValue(invalidValue, Resolver())
            println(fixedValue)

            assertThat(fixedValue).isNotEmpty
            assertThat(fixedValue).containsExactlyInAnyOrderEntriesOf(mapOf(
                "key" to "value"
            ))
        }

        @Test
        fun `should be able to fix invalid values`() {
            val httpHeaders = HttpHeadersPattern(mapOf(
                "key" to ExactValuePattern(StringValue("value")),
                "type" to ExactValuePattern(StringValue("person")),
                "age" to NumberPattern()
            ))
            val invalidValue = mapOf("key" to "value", "type" to  "Invalid", "age" to "invalid")

            val dictionary = "PARAMETERS: { HEADER: { age: 999 } }".let(Dictionary::fromYaml)
            val fixedValue = httpHeaders.fixValue(
                invalidValue, Resolver(dictionary = dictionary).updateLookupPath(BreadCrumb.PARAMETERS.value)
            )
            println(fixedValue)

            assertThat(fixedValue).isNotEmpty
            assertThat(fixedValue).containsExactlyInAnyOrderEntriesOf(mapOf(
                "key" to "value",
                "type" to "person",
                "age" to "999"
            ))
        }

        @Test
        fun `should not add missing optional keys`() {
            val httpHeaders = HttpHeadersPattern(mapOf(
                "key" to ExactValuePattern(StringValue("value")),
                "optional?" to StringPattern()
            ))

            val validValue = mapOf("key" to "value")
            val fixedValue = httpHeaders.fixValue(validValue, Resolver())
            println(fixedValue)

            assertThat(fixedValue).isNotEmpty
            assertThat(fixedValue).isEqualTo(validValue)
        }

        @Test
        fun `should allow key-value pairs where key is not in the pattern`() {
            val httpHeaders = HttpHeadersPattern(mapOf(
                "key" to ExactValuePattern(StringValue("value")),
                "optional?" to StringPattern()
            ))

            val validValue = mapOf("key" to "value", "extraKey" to "extraValue")
            val fixedValue = httpHeaders.fixValue(validValue, Resolver())
            println(fixedValue)

            assertThat(fixedValue).isNotEmpty
            assertThat(fixedValue).isEqualTo(validValue)
        }

        @Test
        fun `should allow content-type through even if not in pattern`() {
            val httpHeaders = HttpHeadersPattern(emptyMap())
            val validValue = mapOf("Content-Type" to "application/json")
            val fixedValue = httpHeaders.fixValue(validValue, Resolver())
            println(fixedValue)

            assertThat(fixedValue).isEqualTo(validValue)
        }

        @Test
        fun `should fix content-type if key exists and value is known even when not in pattern`() {
            val httpHeaders = HttpHeadersPattern(emptyMap(), contentType = "application/json")
            val invalidValue = mapOf("Content-Type" to "invalid")
            val fixedValue = httpHeaders.fixValue(invalidValue, Resolver())
            println(fixedValue)

            assertThat(fixedValue).isEqualTo(mapOf("Content-Type" to "application/json"))
        }

        @Test
        fun `should not modify content-type if key exists but value is unknown`() {
            val httpHeaders = HttpHeadersPattern(emptyMap())
            val invalidValue = mapOf("Content-Type" to "invalid")
            val fixedValue = httpHeaders.fixValue(invalidValue, Resolver())
            println(fixedValue)

            assertThat(fixedValue).isEqualTo(mapOf("Content-Type" to "invalid"))
        }

        @Test
        fun `should be able to fix content-type if its in the pattern`() {
            val httpHeaders = HttpHeadersPattern(mapOf("Content-Type" to ExactValuePattern(StringValue("application/json"))))
            val invalidValue = mapOf("Content-Type" to "invalid")
            val fixedValue = httpHeaders.fixValue(invalidValue, Resolver())
            println(fixedValue)

            assertThat(fixedValue).isEqualTo(mapOf("Content-Type" to "application/json"))
        }

        @Test
        fun `should add content-type if key not exists`() {
            val httpHeaders = HttpHeadersPattern(emptyMap(), contentType = "application/json")
            val invalidValue = emptyMap<String, String>()
            val fixedValue = httpHeaders.fixValue(invalidValue, Resolver())
            println(fixedValue)

            assertThat(fixedValue).isEqualTo(mapOf("Content-Type" to "application/json"))
        }

        @Test
        fun `should fix values that do not match the declared header schema`() {
            val headerName = "key"

            val httpHeaders = HttpHeadersPattern(mapOf(
                headerName to BooleanPattern()
            ))

            val headersWithInvalidValue = mapOf(headerName to "abc123")
            val fixedHeaders = httpHeaders.fixValue(headersWithInvalidValue, Resolver())
            println(fixedHeaders)

            assertThat(fixedHeaders).containsKey(headerName)

            val headerValue = fixedHeaders.getValue(headerName)
            assertThat(headerValue).matches("true|false")
        }

        @Test
        fun `should retain pattern token if it matches when resolver is in mock mode`() {
            val httpHeaders = HttpHeadersPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val validValue = mapOf("number" to "(number)", "boolean" to "(boolean)")
            val fixedValue = httpHeaders.fixValue(validValue, Resolver(mockMode = true))

            println(fixedValue)
            assertThat(fixedValue).isEqualTo(validValue)
        }

        @Test
        fun `should generate value when pattern token does not match when resolver is in mock mode`() {
            val httpHeaders = HttpHeadersPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val validValue = mapOf("number" to "(string)", "boolean" to "(string)")
            val dictionary = "{ (number): 999, (boolean): true }".let(Dictionary::fromYaml)
            val fixedValue = httpHeaders.fixValue(validValue, Resolver(mockMode = true, dictionary = dictionary))

            println(fixedValue)
            assertThat(fixedValue).isEqualTo(mapOf("number" to "999", "boolean" to "true"))
        }

        @Test
        fun `should generate values even if pattern token matches but resolver is not in mock mode`() {
            val httpHeaders = HttpHeadersPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val validValue = mapOf("number" to "(number)", "boolean" to "(boolean)")
            val dictionary = "{ (number): 999, (boolean): true }".let(Dictionary::fromYaml)
            val fixedValue = httpHeaders.fixValue(validValue, Resolver(dictionary = dictionary))

            println(fixedValue)
            assertThat(fixedValue).isEqualTo(mapOf("number" to "999", "boolean" to "true"))
        }

        @Test
        fun `should not add missing mandatory keys when resolver is set to partial`() {
            val httpHeaders = HttpHeadersPattern(mapOf("number" to NumberPattern(), "string" to StringPattern()))
            val dictionary = "{ (number): 999, (string): TODO }".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary).partializeKeyCheck()
            val partialInvalidValue = mapOf("number" to "(string)")
            val fixedValue = httpHeaders.fixValue(partialInvalidValue, resolver)

            assertThat(fixedValue).isEqualTo(mapOf("number" to "999"))
        }
    }

    @ParameterizedTest
    @CsvSource(
        "Content-Type Header, Generated Content Type",
        "application/json, application/json",
        "application/json; charset=utf-8, application/json; charset=utf-8",
        "none, application/json",
        "present, application/json",
        useHeadersInDisplayName = true,
    )
    fun `content type header with specific value should override media type when generating headers`(contentTypeHeader: String, expectedContentType: String) {
        val mediaType = "application/json"

        val headersPattern = when(contentTypeHeader) {
            "none" -> HttpHeadersPattern(emptyMap(), contentType = mediaType)
            "present" -> HttpHeadersPattern(mapOf("Content-Type" to StringPattern()), contentType = mediaType)
            else -> HttpHeadersPattern(mapOf("Content-Type" to StringPattern(regex = contentTypeHeader)), contentType = mediaType)
        }

        val headers = headersPattern.generate(Resolver())

        assertThat(headers).containsEntry("Content-Type", expectedContentType)
    }

    @ParameterizedTest
    @CsvSource(
        "Content-Type Header In Spec, Actual Content Type, Matches",
        "application/json, application/json, true",
        "application/json; charset=utf-8, application/json; charset=utf-8, true",
        "none, application/json, true",
        "present, application/json, true",
        "application/json; charset=utf-8, application/json, false",
        useHeadersInDisplayName = true,
    )
    fun `content type header with specific value should override media type when matching headers`(contentTypeHeaderInSpec: String, actualContentType: String, matches: String) {
        val mediaType = "application/json"

        val headersPattern = when(contentTypeHeaderInSpec) {
            "none" -> HttpHeadersPattern(emptyMap(), contentType = mediaType)
            "present" -> HttpHeadersPattern(mapOf("Content-Type" to StringPattern()), contentType = mediaType)
            else -> HttpHeadersPattern(mapOf("Content-Type" to StringPattern(regex = contentTypeHeaderInSpec)), contentType = mediaType)
        }

        val actualHeaders = mapOf("Content-Type" to actualContentType)

        val matchResult = headersPattern.matches(actualHeaders, Resolver())

        if(matches == "true") {
            assertThat(matchResult).isInstanceOf(Result.Success::class.java)
        } else {
            assertThat(matchResult).isInstanceOf(Result.Failure::class.java)
        }
    }

    @Nested
    inner class FillInTheBlanksTests {
        @Test
        fun `should generate values for missing mandatory keys and pattern tokens`() {
            val httpHeaders = HttpHeadersPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val headers = mapOf("number" to "(number)")
            val dictionary = "PARAMETERS: { HEADER: { number: 999, boolean: true } }".let(Dictionary::fromYaml)
            val filledHeaders = httpHeaders.fillInTheBlanks(
                headers, Resolver(dictionary = dictionary).updateLookupPath(BreadCrumb.PARAMETERS.value)
            ).value

            assertThat(filledHeaders).isEqualTo(mapOf("number" to "999", "boolean" to "true"))
        }

        @Test
        fun `should not generate missing optional keys`() {
            val httpHeaders = HttpHeadersPattern(mapOf("number" to NumberPattern(), "boolean?" to BooleanPattern()))
            val headers = mapOf("number" to "999")
            val filledHeaders = httpHeaders.fillInTheBlanks(headers, Resolver()).value

            assertThat(filledHeaders).isEqualTo(mapOf("number" to "999"))
        }

        @Test
        fun `should handle any-value pattern token as a special case`() {
            val httpHeaders = HttpHeadersPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val headers = mapOf("number" to "(anyvalue)")
            val dictionary = "RESPONSE: { HEADER: { number: 999, boolean: true } }".let(Dictionary::fromYaml)
            val filledHeaders = httpHeaders.fillInTheBlanks(
                headers, Resolver(dictionary = dictionary).updateLookupPath(BreadCrumb.RESPONSE.value)
            ).value

            assertThat(filledHeaders).isEqualTo(mapOf("number" to "999", "boolean" to "true"))
        }

        @Test
        fun `should complain when pattern-token does not match the underlying pattern`() {
            val httpHeaders = HttpHeadersPattern(mapOf("number" to NumberPattern(), "boolean" to BooleanPattern()))
            val headers = mapOf("number" to "(string)")
            val exception = assertThrows<ContractException> {
                httpHeaders.fillInTheBlanks(headers, Resolver()).value
            }

            assertThat(exception.failure().reportString()).isEqualToNormalizingWhitespace("""
            >> number
            Expected number, actual was string
            """.trimIndent())
        }

        @Test
        fun `should generate missing optional keys when allPatternsMandatory is set`() {
            val httpHeaders = HttpHeadersPattern(mapOf("number" to NumberPattern(), "boolean?" to BooleanPattern()))
            val dictionary = "RESPONSE: { HEADER: { boolean: true } }".let(Dictionary::fromYaml)
            val headers = mapOf("number" to "999")
            val filledHeaders = httpHeaders.fillInTheBlanks(
                headers, Resolver(dictionary = dictionary).withAllPatternsAsMandatory().updateLookupPath(BreadCrumb.RESPONSE.value)
            ).value

            assertThat(filledHeaders).isEqualTo(mapOf("number" to "999", "boolean" to "true"))
        }

        @Test
        fun `should not generate missing mandatory keys when resolver is set to negative`() {
            val httpHeaders = HttpHeadersPattern(mapOf("number" to NumberPattern(), "boolean?" to BooleanPattern()))
            val headers = mapOf("boolean" to "true")
            val filledHeaders = httpHeaders.fillInTheBlanks(headers, Resolver(isNegative = true)).value

            assertThat(filledHeaders).isEqualTo(mapOf("boolean" to "true"))
        }

        @Test
        fun `should allow extra keys when extensible-schema or resolver is negative`() {
            val httpHeaders = HttpHeadersPattern(mapOf("number" to NumberPattern()))
            val headers = mapOf("number" to "(number)", "extraKey" to "(string)")
            val dictionary = """
            PARAMETERS: { HEADER: { number: 999 } }
            (string): ExtraValue
            """.trimIndent().let(Dictionary::fromYaml)
            val resolvers = listOf(
                Resolver(dictionary = dictionary, isNegative = true),
                Resolver(dictionary = dictionary).withUnexpectedKeyCheck(IgnoreUnexpectedKeys)
            ).map { it.updateLookupPath(BreadCrumb.PARAMETERS.value) }

            assertThat(resolvers).allSatisfy {
                val filledJsonObject = httpHeaders.fillInTheBlanks(headers, it).value
                assertThat(filledJsonObject).isEqualTo(
                    mapOf("number" to "999", "extraKey" to "ExtraValue")
                )
            }
        }

        @Test
        fun `should allow invalid pattern tokens when resolver is negative`() {
            val httpHeaders = HttpHeadersPattern(mapOf("test" to StringPattern()))
            val invalidPatterns = listOf(
                ListPattern(StringPattern()),
                BooleanPattern(),
                NullPattern,
            )


            assertThat(invalidPatterns).allSatisfy {
                val resolver = Resolver(newPatterns = mapOf("(Test)" to it), isNegative = true)
                val value = mapOf("test" to "(Test)")
                val result = httpHeaders.fillInTheBlanks(value, resolver)

                assertThat(result).isInstanceOf(HasValue::class.java); result as HasValue
                println(result.value)
            }
        }
    }
}
