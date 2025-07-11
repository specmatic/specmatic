package io.specmatic.core

import com.ezylang.evalex.Expression
import com.ezylang.evalex.config.ExpressionConfiguration
import io.mockk.every
import io.mockk.mockk
import io.specmatic.conversions.*
import io.specmatic.core.filters.HttpFilterContext
import io.specmatic.core.filters.ScenarioFilterVariablePopulator
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.Flags
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.function.Consumer
import java.util.stream.Stream

class ScenarioTest {

    companion object {
        @JvmStatic
        fun securitySchemaProvider(): Stream<OpenAPISecurityScheme> {
            return Stream.of(
                APIKeyInHeaderSecurityScheme(name = "API-KEY", apiKey = "1234"),
                APIKeyInQueryParamSecurityScheme(name = "API-KEY", apiKey = "1234"),
                BasicAuthSecurityScheme("am9objoxMjM0"),
                BearerSecurityScheme("1234"),
                CompositeSecurityScheme(listOf(
                    BearerSecurityScheme("1234"),
                    APIKeyInQueryParamSecurityScheme(name = "API-KEY", apiKey = "1234"),
                ))
            )
        }
    }

    @Test
    fun `should validate and reject an invalid response in an example row`() {
        val responseExample = HttpResponse(200, """{"id": "abc123"}""")
        val scenario = Scenario(
            "",
            HttpRequestPattern(
                method = "POST",
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            emptyMap(),
            listOf(
                Examples(
                    listOf("(REQUEST-BODY)"),
                    listOf(
                        Row(
                            mapOf("(REQUEST-BODY)" to """{"id": 10}""")
                        ).copy(
                            responseExample = responseExample
                        )
                    )
                )
            ),
            emptyMap(),
            emptyMap()
        )

        assertThatThrownBy {
            scenario.validExamplesOrException(DefaultStrategies)
        }.satisfies(Consumer {
            assertThat(it)
                .hasMessageContaining("abc123")
                .hasMessageContaining("RESPONSE.BODY.id")
        })
    }

    @Test
    fun `should validate and reject an invalid request in an example row`() {
        val scenario = Scenario(
            "",
            HttpRequestPattern(
                method = "POST",
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            emptyMap(),
            listOf(
                Examples(
                    listOf("(REQUEST-BODY)"),
                    listOf(
                        Row(
                            mapOf("(REQUEST-BODY)" to """{"id": "abc123" }""")
                        ).copy(responseExample = HttpResponse(200, """{"id": 10}"""))
                    )
                )
            ),
            emptyMap(),
            emptyMap()
        )

        assertThatThrownBy {
            scenario.validExamplesOrException(DefaultStrategies)
        }.satisfies(Consumer {
            assertThat(it)
                .hasMessageContaining("abc123")
                .hasMessageContaining("REQUEST.BODY.id")
        })
    }

    @Test
    fun `should validate and accept a response in an example row with pattern specification as value`() {
        val scenario = Scenario(
            "",
            HttpRequestPattern(
                method = "POST",
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            emptyMap(),
            listOf(
                Examples(
                    listOf("(REQUEST-BODY)"),
                    listOf(
                        Row(
                            mapOf("(REQUEST-BODY)" to """{"id": 10}""")
                        ).copy(responseExample = HttpResponse(200, """{"id": "(number)"}"""))
                    )
                )
            ),
            emptyMap(),
            emptyMap()
        )

        assertThatCode {
            scenario.validExamplesOrException(DefaultStrategies)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `should validate and accept a request in an example row  with pattern specification as value`() {
        val scenario = Scenario(
            "",
            HttpRequestPattern(
                method = "POST",
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            emptyMap(),
            listOf(
                Examples(
                    listOf("(REQUEST-BODY)"),
                    listOf(
                        Row(
                            mapOf("(REQUEST-BODY)" to """{"id": "(number)" }""")
                        )
                    )
                )
            ),
            emptyMap(),
            emptyMap()
        )

        assertThatCode {
            scenario.validExamplesOrException(DefaultStrategies)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `should validate and reject a response in an example row with a non-matching pattern specification as value`() {
        val responseExample = HttpResponse(200, """{"id": "(string)"}""")
        val scenario = Scenario(
            "",
            HttpRequestPattern(
                method = "POST",
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            emptyMap(),
            listOf(
                Examples(
                    listOf("(REQUEST-BODY)"),
                    listOf(
                        Row(
                            mapOf("(REQUEST-BODY)" to """{"id": 10}""")
                        ).copy(
                            responseExample = responseExample
                        )
                    )
                )
            ),
            emptyMap(),
            emptyMap()
        )

        assertThatCode {
            scenario.validExamplesOrException(DefaultStrategies)
        }.satisfies(Consumer {
            assertThat(it)
                .hasMessageContaining("string")
                .hasMessageContaining("RESPONSE.BODY.id")
        })
    }

    @Test
    fun `should validate and accept a request in an example row  with a non-matching pattern specification as value`() {
        val scenario = Scenario(
            "",
            HttpRequestPattern(
                method = "POST",
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "id" to NumberPattern()
                    )
                )
            ),
            emptyMap(),
            listOf(
                Examples(
                    listOf("(REQUEST-BODY)"),
                    listOf(
                        Row(
                            mapOf("(REQUEST-BODY)" to """{"id": "(string)" }""")
                        )
                    )
                )
            ),
            emptyMap(),
            emptyMap()
        )

        assertThatThrownBy {
            scenario.validExamplesOrException(DefaultStrategies)
        }.satisfies(Consumer {
            assertThat(it)
                .hasMessageContaining("string")
                .hasMessageContaining("REQUEST.BODY.id")
        })
    }

    @Test
    fun `should return scenarioMetadata from scenario`() {
        val httpRequestPattern = mockk<HttpRequestPattern> {
            every {
                getHeaderKeys()
            } returns setOf("Authorization", "X-Request-ID")

            every {
                getQueryParamKeys()
            } returns setOf("productId", "orderId")

            every { method } returns "POST"
            every { httpPathPattern } returns HttpPathPattern(emptyList(), "/createProduct")
        }

        val scenario = Scenario(
            "",
            httpRequestPattern,
            HttpResponsePattern(status = 200),
            exampleName = "example"
        )
        val scenarioMetadata = scenario.toScenarioMetadata()

        assertThat(scenarioMetadata).isInstanceOf(ScenarioFilterVariablePopulator::class.java)
        val expression = Expression("true", ExpressionConfiguration.builder().binaryAllowed(true).build())
        scenarioMetadata.populateExpressionData(expression)
        assertThat(expression.dataAccessor.getData("context").value).isEqualTo(HttpFilterContext(scenario))
    }

    @ParameterizedTest
    @MethodSource("securitySchemaProvider")
    fun `should load examples with security schemas missing`(securitySchema: OpenAPISecurityScheme) {
        val scenario = Scenario(
            name = "SIMPLE POST",
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"), method = "POST",
                securitySchemes = listOf(securitySchema)
            ),
            httpResponsePattern = HttpResponsePattern(status = 200),
            examples = listOf(
                Examples(
                    emptyList(),
                    listOf(Row(requestExample = HttpRequest(path = "/", method = "POST")))
                )
            )
        )

        assertDoesNotThrow { scenario.validExamplesOrException(DefaultStrategies) }
    }

    @Test
    fun `should throw an exception when security schema defined in the example is invalid`() {
        val scenario = Scenario(
            name = "SIMPLE POST",
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"), method = "POST",
                securitySchemes = listOf(BasicAuthSecurityScheme())
            ),
            httpResponsePattern = HttpResponsePattern(status = 200),
            examples = listOf(
                Examples(
                    emptyList(),
                    listOf(
                        Row(
                            requestExample = HttpRequest(
                                path = "/", method = "POST",
                                headers = mapOf(AUTHORIZATION to "Invalid")
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ContractException> { scenario.validExamplesOrException(DefaultStrategies) }
        assertThat(exception.report()).isEqualToNormalizingWhitespace(
            """
        Error loading example named  for POST / -> 200
        >> REQUEST.PARAMETERS.HEADER.Authorization
        Authorization header must be prefixed with "Basic"
        """.trimIndent()
        )
    }

    @Test
    fun `should throw exception when request-response contains out-of-spec headers`() {
        val scenario = Scenario(
            name = "SIMPLE POST",
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"), method = "POST",
                headersPattern = HttpHeadersPattern(mapOf("USER-ID" to UUIDPattern))
            ),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                headersPattern = HttpHeadersPattern(mapOf("TICKET-ID" to UUIDPattern))
            ),
            examples = listOf(
                Examples(
                    emptyList(),
                    listOf(
                        Row(
                            requestExample = HttpRequest(
                                path = "/", method = "POST",
                                headers = mapOf(
                                    "X-EXTRA-HEADERS" to "ExtraValue",
                                    "USER-ID" to "123e4567-e89b-12d3-a456-426655440000"
                                )
                            ),
                            responseExample = HttpResponse(
                                status = 200,
                                headers = mapOf(
                                    "TICKET-ID" to "123e4567-e89b-12d3-a456-426655440000",
                                    "X-EXTRA-HEADERS" to "ExtraValue"
                                )
                            ),
                            name = "example.json"
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ContractException> { scenario.validExamplesOrException(DefaultStrategies) }
        assertThat(exception.report()).isEqualToNormalizingWhitespace(
            """
        Error loading example named example.json for POST / -> 200
        >> REQUEST.PARAMETERS.HEADER.X-EXTRA-HEADERS
        The header X-EXTRA-HEADERS was found in the example example.json but was not in the specification.
        >> RESPONSE.HEADER.X-EXTRA-HEADERS
        The header X-EXTRA-HEADERS was found in the example example.json but was not in the specification.
        """.trimIndent()
        )
    }

    @Test
    fun `should throw exception when request-response contains out-of-spec headers for partials`() {
        val scenario = Scenario(
            name = "SIMPLE POST",
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"), method = "POST",
                headersPattern = HttpHeadersPattern(mapOf("USER-ID" to UUIDPattern))
            ),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                headersPattern = HttpHeadersPattern(mapOf("TICKET-ID" to UUIDPattern))
            ),
            examples = listOf(
                Examples(
                    emptyList(),
                    listOf(
                        Row(
                            requestExample = HttpRequest(
                                path = "/", method = "POST",
                                headers = mapOf(
                                    "X-EXTRA-HEADERS" to "ExtraValue",
                                    "USER-ID" to "123e4567-e89b-12d3-a456-426655440000"
                                )
                            ),
                            responseExample = HttpResponse(
                                status = 200,
                                headers = mapOf(
                                    "TICKET-ID" to "123e4567-e89b-12d3-a456-426655440000",
                                    "X-EXTRA-HEADERS" to "ExtraValue"
                                )
                            ),
                            name = "partial-example.json",
                            isPartial = true
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ContractException> { scenario.validExamplesOrException(DefaultStrategies) }
        assertThat(exception.report()).isEqualToNormalizingWhitespace(
            """
        Error loading example named partial-example.json for POST / -> 200
        >> REQUEST.PARAMETERS.HEADER.X-EXTRA-HEADERS
        The header X-EXTRA-HEADERS was found in the example partial-example.json but was not in the specification.
        >> RESPONSE.HEADER.X-EXTRA-HEADERS
        The header X-EXTRA-HEADERS was found in the example partial-example.json but was not in the specification.
        """.trimIndent()
        )
    }

    @Test
    fun `should allow out-of-spec headers when extensible-schema is enabled`() {
        val scenario = Scenario(
            name = "SIMPLE POST",
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"), method = "POST",
                headersPattern = HttpHeadersPattern(mapOf("USER-ID" to UUIDPattern))
            ),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                headersPattern = HttpHeadersPattern(mapOf("TICKET-ID" to UUIDPattern))
            ),
            examples = listOf(
                Examples(
                    emptyList(),
                    listOf(
                        Row(
                            requestExample = HttpRequest(
                                path = "/", method = "POST",
                                headers = mapOf(
                                    "X-EXTRA-HEADERS" to "ExtraValue",
                                    "USER-ID" to "123e4567-e89b-12d3-a456-426655440000"
                                )
                            ),
                            responseExample = HttpResponse(
                                status = 200,
                                headers = mapOf(
                                    "TICKET-ID" to "123e4567-e89b-12d3-a456-426655440000",
                                    "X-EXTRA-HEADERS" to "ExtraValue"
                                )
                            ),
                            name = "example.json"
                        )
                    )
                )
            )
        )

        Flags.using(Flags.EXTENSIBLE_SCHEMA to "true") {
            val flagBased = strategiesFromFlags(SpecmaticConfig())
            assertDoesNotThrow { scenario.validExamplesOrException(flagBased) }
        }
    }

    @Test
    fun `disambiguate should return the provided value with a space`() {
        val scenario = Scenario(
            name = "test scenario",
            httpRequestPattern = HttpRequestPattern(method = "GET", httpPathPattern = HttpPathPattern.from("/")),
            httpResponsePattern = HttpResponsePattern(status = 200),
            disambiguate = { "[1] " }
        )
        assertThat(scenario.testDescription().trim()).isEqualTo("Scenario: GET / [1] -> 200")
    }

    @Nested
    inner class AttributeSelectionTest {
        @Test
        fun `should validate unexpected keys when the request is attribute based`() {
            val httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/test"),
                method = "GET",
                httpQueryParamPattern = HttpQueryParamPattern(
                    queryPatterns = mapOf("fields?" to ListPattern(StringPattern()))
                )
            )

            val httpResponsePattern = HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(
                    pattern = mapOf("id" to NumberPattern(), "name" to StringPattern())
                )
            )

            val scenario = Scenario(
                "",
                httpRequestPattern,
                httpResponsePattern,
                attributeSelectionPattern = AttributeSelectionPattern(
                    queryParamKey = "fields",
                    defaultFields = emptyList()
                )
            )

            val httpRequest = HttpRequest(
                path = "/test",
                method = "GET",
                queryParams = QueryParameters(mapOf("fields" to "id"))
            )

            val httpResponse = HttpResponse(
                status = 200,
                body = """{"id": 10, "extraKey": "extraValue"}"""
            )

            val flagBasedWithExtensibleSchema = DefaultStrategies.copy(unexpectedKeyCheck = IgnoreUnexpectedKeys)
            val result =
                scenario.matches(httpRequest, httpResponse, DefaultMismatchMessages, flagBasedWithExtensibleSchema)

            println(result.reportString())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).isEqualToNormalizingWhitespace(
                """
            In scenario ""
            API: GET /test -> 200

            >> RESPONSE.BODY.extraKey 
            Key named "extraKey" was unexpected
            """.trimIndent()
            )
        }

        @Test
        fun `should fallback to flag based when the request is not attribute based`() {
            val httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/test"),
                method = "GET",
                httpQueryParamPattern = HttpQueryParamPattern(
                    queryPatterns = mapOf("fields?" to ListPattern(StringPattern()))
                )
            )

            val httpResponsePattern = HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(
                    pattern = mapOf("id" to NumberPattern(), "name" to StringPattern())
                )
            )

            val scenario = Scenario(
                "",
                httpRequestPattern,
                httpResponsePattern,
                attributeSelectionPattern = AttributeSelectionPattern(
                    queryParamKey = "fields",
                    defaultFields = emptyList()
                )
            )

            val httpRequest = HttpRequest(
                path = "/test",
                method = "GET"
            )

            val httpResponse = HttpResponse(
                status = 200,
                body = """{"id": 10, "name": "name", "extraKey": "extraValue"}"""
            )

            val flagBasedWithExtensibleSchema = DefaultStrategies.copy(unexpectedKeyCheck = IgnoreUnexpectedKeys)
            val extensibleResult =
                scenario.matches(httpRequest, httpResponse, DefaultMismatchMessages, flagBasedWithExtensibleSchema)

            println(extensibleResult.reportString())
            assertThat(extensibleResult).isInstanceOf(Result.Success::class.java)

            val flagBasedWithoutExtensibleSchema = DefaultStrategies
            val nonExtensibleResult =
                scenario.matches(httpRequest, httpResponse, DefaultMismatchMessages, flagBasedWithoutExtensibleSchema)

            println(nonExtensibleResult.reportString())
            assertThat(nonExtensibleResult).isInstanceOf(Result.Failure::class.java)
            assertThat(nonExtensibleResult.reportString()).isEqualToNormalizingWhitespace(
                """
            In scenario ""
            API: GET /test -> 200

            >> RESPONSE.BODY.extraKey 
            Key named "extraKey" was unexpected
            """.trimIndent()
            )
        }
    }

    @Nested
    inner class CalculatePathTests {
        @Test
        fun `calculatePath should handle JSONObjectPattern body`() {
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(
                    body = JSONObjectPattern(
                        mapOf("data" to AnyPattern(listOf(StringPattern()), extensions = emptyMap())),
                        typeAlias = "(TestRequest)"
                    )
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = JSONObjectValue(mapOf("data" to StringValue("test")))
            )

            val paths = scenario.calculatePath(httpRequest)

            assertThat(paths).containsExactly("{TestRequest}.data{string}")
        }

        @Test
        fun `calculatePath should handle AnyPattern body with scalar wrapping`() {
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(
                    body = AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = StringValue("test")
            )

            val paths = scenario.calculatePath(httpRequest)

            assertThat(paths).containsExactly("{string}")
        }

        @Test
        fun `calculatePath should handle AnyPattern body with non-scalar types`() {
            val objectPattern = JSONObjectPattern(
                mapOf("field" to StringPattern()),
                typeAlias = "(CustomObject)"
            )
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(
                    body = AnyPattern(listOf(objectPattern), extensions = emptyMap())
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = JSONObjectValue(mapOf("field" to StringValue("test")))
            )

            val paths = scenario.calculatePath(httpRequest)

            assertThat(paths).containsExactly("{CustomObject}")
        }

        @Test
        fun `calculatePath should handle ListPattern body`() {
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(
                    body = ListPattern(AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap()))
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = JSONArrayValue(listOf(StringValue("test"), NumberValue(42)))
            )

            val paths = scenario.calculatePath(httpRequest)

            assertThat(paths).containsExactlyInAnyOrder("{[0]}{string}", "{[1]}{number}")
        }

        @Test
        fun `calculatePath should handle JSONArrayPattern body`() {
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(
                    body = JSONArrayPattern(listOf(AnyPattern(listOf(StringPattern()), extensions = emptyMap())))
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = JSONArrayValue(listOf(StringValue("test1"), StringValue("test2")))
            )

            val paths = scenario.calculatePath(httpRequest)

            assertThat(paths).containsExactlyInAnyOrder("{[0]}{string}", "{[1]}{string}")
        }

        @Test
        fun `calculatePath should return empty set for unsupported body pattern types`() {
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    body = StringPattern()
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = StringValue("test")
            )

            val paths = scenario.calculatePath(httpRequest)

            assertThat(paths).isEmpty()
        }

        @Test
        fun `calculatePath should handle nested complex patterns`() {
            val nestedPattern = JSONObjectPattern(
                mapOf(
                    "items" to ListPattern(
                        JSONObjectPattern(
                            mapOf(
                                "value" to AnyPattern(
                                    listOf(StringPattern(), NumberPattern()),
                                    extensions = emptyMap()
                                )
                            ),
                            typeAlias = "(Item)"
                        )
                    )
                ),
                typeAlias = "(Container)"
            )
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(body = nestedPattern),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = JSONObjectValue(mapOf(
                    "items" to JSONArrayValue(listOf(
                        JSONObjectValue(mapOf("value" to StringValue("test"))),
                        JSONObjectValue(mapOf("value" to NumberValue(42)))
                    ))
                ))
            )

            val paths = scenario.calculatePath(httpRequest)

            assertThat(paths).containsExactlyInAnyOrder(
                "{Container}.items[0]{Item}.value{string}",
                "{Container}.items[1]{Item}.value{number}"
            )
        }

        @Test
        fun `calculatePath should handle DeferredPattern in resolver`() {
            val patterns = mapOf(
                "(CustomType)" to JSONObjectPattern(
                    mapOf("data" to AnyPattern(listOf(StringPattern()), extensions = emptyMap())),
                    typeAlias = "(CustomType)"
                )
            )
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(
                    body = DeferredPattern("(CustomType)")
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                ),
                patterns = patterns
            )
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = JSONObjectValue(mapOf("data" to StringValue("test")))
            )

            val paths = scenario.calculatePath(httpRequest)

            assertThat(paths).containsExactly("{CustomType}.data{string}")
        }

        @Test
        fun `calculatePath should handle empty object without AnyPatterns`() {
            val scenario = Scenario(
                name = "test",
                httpRequestPattern = HttpRequestPattern(
                    body = JSONObjectPattern(mapOf("field" to StringPattern()))
                ),
                httpResponsePattern = HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(),
                    status = 200,
                    body = StringPattern()
                )
            )
            val httpRequest = HttpRequest(
                method = "POST",
                path = "/test",
                body = JSONObjectValue(mapOf("field" to StringValue("test")))
            )

            val paths = scenario.calculatePath(httpRequest)

            assertThat(paths).isEmpty()
        }
    }
}
