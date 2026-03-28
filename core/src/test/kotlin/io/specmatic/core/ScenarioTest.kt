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
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.NamedExampleMismatchMessages
import io.specmatic.toViolationReportString
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
import io.specmatic.test.TestExecutionReason
import io.specmatic.test.TestSkipReason

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
            emptyMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
            emptyMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
            emptyMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
            emptyMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
            emptyMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
            emptyMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
    fun `validateAndFilterExamples should retain valid rows and remove invalid rows`() {
        val scenario = Scenario(
            name = "SIMPLE POST",
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST", body = JSONObjectPattern(mapOf("id" to NumberPattern()))),
            httpResponsePattern = HttpResponsePattern(status = 200, body = JSONObjectPattern(mapOf("id" to NumberPattern()))),
            examples = listOf(
                Examples(
                    rows = listOf(
                        Row(
                            name = "valid-example",
                            requestExample = HttpRequest(path = "/", method = "POST", body = JSONObjectValue(mapOf("id" to NumberValue(10)))),
                            responseExample = HttpResponse(status = 200, body = JSONObjectValue(mapOf("id" to NumberValue(20))))
                        ),
                        Row(
                            name = "invalid-example",
                            requestExample = HttpRequest(path = "/", method = "POST", body = JSONObjectValue(mapOf("id" to StringValue("not-a-number")))),
                            responseExample = HttpResponse(status = 200, body = JSONObjectValue(mapOf("id" to NumberValue(20))))
                        )
                    )
                )
            )
        )

        val (filteredScenario, result) = scenario.validateAndFilterExamples(DefaultStrategies)
        assertThat(filteredScenario.examples).hasSize(1)
        assertThat(filteredScenario.examples.single().rows).hasSize(1)
        assertThat(filteredScenario.examples.single().rows.single().name).isEqualTo("valid-example")
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString())
            .contains("Error loading example named invalid-example for POST / -> 200")
            .contains("REQUEST.BODY")
    }

    @Test
    fun `validateAndFilterExamples should drop example groups with no valid rows`() {
        val scenario = Scenario(
            name = "SIMPLE POST",
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST", body = JSONObjectPattern(mapOf("id" to NumberPattern()))),
            httpResponsePattern = HttpResponsePattern(status = 200, body = JSONObjectPattern(mapOf("id" to NumberPattern()))),
            examples = listOf(
                Examples(
                    rows = listOf(
                        Row(
                            name = "invalid-example",
                            requestExample = HttpRequest(path = "/", method = "POST", body = JSONObjectValue(mapOf("id" to StringValue("not-a-number")))),
                            responseExample = HttpResponse(status = 200, body = JSONObjectValue(mapOf("id" to NumberValue(20))))
                        )
                    )
                )
            )
        )

        val (filteredScenario, result) = scenario.validateAndFilterExamples(DefaultStrategies)
        assertThat(filteredScenario.examples).isEmpty()
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains("Error loading example named invalid-example for POST / -> 200")
    }

    @Test
    fun `validateAndFilterExamples should retain partial example rows without failing`() {
        val scenario = Scenario(
            name = "SIMPLE POST",
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST", headersPattern = HttpHeadersPattern(mapOf("USER-ID" to UUIDPattern))),
            httpResponsePattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(mapOf("TICKET-ID" to UUIDPattern))),
            examples = listOf(
                Examples(
                    rows = listOf(
                        Row(
                            name = "partial-example",
                            isPartial = true,
                            requestExample = HttpRequest(path = "/", method = "POST", headers = mapOf("USER-ID" to "123e4567-e89b-12d3-a456-426655440000", "X-EXTRA-HEADERS" to "ExtraValue")),
                            responseExample = HttpResponse(status = 200, headers = mapOf("TICKET-ID" to "123e4567-e89b-12d3-a456-426655440000", "X-EXTRA-HEADERS" to "ExtraValue"))
                        )
                    )
                )
            )
        )

        val (filteredScenario, result) = scenario.validateAndFilterExamples(DefaultStrategies)
        assertThat(filteredScenario.examples).hasSize(1)
        assertThat(filteredScenario.examples.single().rows).hasSize(1)
        assertThat(filteredScenario.examples.single().rows.single().name).isEqualTo("partial-example")
        assertThat(result).isInstanceOf(Result.Success::class.java)
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
            exampleName = "example",
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val exception = assertThrows<ContractException> { scenario.validExamplesOrException(DefaultStrategies) }
        assertThat(exception.report()).isEqualToNormalizingWhitespace("""
        Error loading example named  for POST / -> 200
        ${
            toViolationReportString(
                breadCrumb = "REQUEST.PARAMETERS.HEADER.Authorization",
                details = "Authorization header must be prefixed with \"Basic\"",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        """.trimIndent())
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
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val exception = assertThrows<ContractException> { scenario.validExamplesOrException(DefaultStrategies) }
        assertThat(exception.report()).isEqualToNormalizingWhitespace("""
        Error loading example named example.json for POST / -> 200
        ${
            toViolationReportString(
                breadCrumb = "REQUEST.PARAMETERS.HEADER.X-EXTRA-HEADERS",
                details = NamedExampleMismatchMessages("example.json").unexpectedKey("header", "X-EXTRA-HEADERS"),
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.HEADER.X-EXTRA-HEADERS",
                details = NamedExampleMismatchMessages("example.json").unexpectedKey("header", "X-EXTRA-HEADERS"),
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        """.trimIndent())
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
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val exception = assertThrows<ContractException> { scenario.validExamplesOrException(DefaultStrategies) }
        assertThat(exception.report()).isEqualToNormalizingWhitespace("""
        Error loading example named partial-example.json for POST / -> 200
        ${
            toViolationReportString(
                breadCrumb = "REQUEST.PARAMETERS.HEADER.X-EXTRA-HEADERS",
                details = NamedExampleMismatchMessages("partial-example.json").unexpectedKey("header", "X-EXTRA-HEADERS"),
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.HEADER.X-EXTRA-HEADERS",
                details = NamedExampleMismatchMessages("partial-example.json").unexpectedKey("header", "X-EXTRA-HEADERS"),
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        """.trimIndent())
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
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
            disambiguate = { "[1] " },
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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

            val result = scenario.matchesResponse(httpRequest, httpResponse, DefaultMismatchMessages, IgnoreUnexpectedKeys)
            println(result.reportString())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
            In scenario ""
            API: GET /test -> 200
            ${
                toViolationReportString(
                    breadCrumb = "RESPONSE.BODY.extraKey",
                    details = AttributeSelectionWithResponseMismatchMessages.unexpectedKey("property", "extraKey"),
                    StandardRuleViolation.UNKNOWN_PROPERTY
                )
            }
            """.trimIndent())
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
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )

            val httpRequest = HttpRequest(
                path = "/test",
                method = "GET"
            )

            val httpResponse = HttpResponse(
                status = 200,
                body = """{"id": 10, "name": "name", "extraKey": "extraValue"}"""
            )

            val extensibleResult = scenario.matchesResponse(httpRequest, httpResponse, DefaultMismatchMessages, IgnoreUnexpectedKeys)
            println(extensibleResult.reportString())
            assertThat(extensibleResult).isInstanceOf(Result.Success::class.java)

            val nonExtensibleResult = scenario.matchesResponse(httpRequest, httpResponse, DefaultMismatchMessages, ValidateUnexpectedKeys)
            println(nonExtensibleResult.reportString())
            assertThat(nonExtensibleResult).isInstanceOf(Result.Failure::class.java)
            assertThat(nonExtensibleResult.reportString()).isEqualToNormalizingWhitespace("""
            In scenario ""
            API: GET /test -> 200
            ${
                toViolationReportString(
                    breadCrumb = "RESPONSE.BODY.extraKey",
                    details = DefaultMismatchMessages.unexpectedKey("property", "extraKey"),
                    StandardRuleViolation.UNKNOWN_PROPERTY
                )
            }
            """.trimIndent())
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
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
                patterns = patterns,
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
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

    @Nested
    inner class NewBasedOnWithDecisionTests {
        @Test
        fun `newBasedOnWithDecision should execute using matching suggestion and keep original context`() {
            val original = exampleScenarioForNewBasedOnWithDecision(name = "original")
            val suggested = exampleScenarioForNewBasedOnWithDecision(name = "original").copy(
                examples = listOf(Examples(listOf("id"), listOf(Row(mapOf("id" to "456"))))),
                references = emptyMap()
            )

            val decision = original.newBasedOnWithDecision(suggestions = listOf(suggested), strictMode = false, resiliencyTestSuite = ResiliencyTestSuite.all)
            assertThat(decision).isEqualTo(
                Decision.Execute(
                    context = original,
                    reasoning = Reasoning(mainReason = TestExecutionReason.HAS_EXAMPLE),
                    value = original.copy(examples = suggested.examples, references = suggested.references),
                )
            )
        }

        @Test
        fun `newBasedOnWithDecision should execute using self when no matching suggestion exists`() {
            val original = exampleScenarioForNewBasedOnWithDecision(name = "original")
            val unrelatedSuggestion = exampleScenarioForNewBasedOnWithDecision(name = "other")
            val decision = original.newBasedOnWithDecision(
                strictMode = false,
                suggestions = listOf(unrelatedSuggestion),
                resiliencyTestSuite = ResiliencyTestSuite.all
            )

            assertThat(decision).isEqualTo(
                Decision.Execute(
                    context = original,
                    reasoning = Reasoning(mainReason = TestExecutionReason.HAS_EXAMPLE),
                    value = original.copy(examples = original.examples, references = original.references),
                )
            )
        }

        @Test
        fun `newBasedOnWithDecision should execute with no example reasoning when scenario has no examples`() {
            val original = scenarioForNewBasedOnWithDecision(status = 200)
            val decision = original.newBasedOnWithDecision(strictMode = false, resiliencyTestSuite = ResiliencyTestSuite.all)
            assertThat(decision).isEqualTo(
                Decision.Execute(
                    value = original,
                    context = original,
                    reasoning = Reasoning(mainReason = TestExecutionReason.NO_EXAMPLE)
                )
            )
        }

        @Test
        fun `newBasedOnWithDecision should skip bad request scenarios without examples when resiliency suite is not all`() {
            val original = scenarioForNewBasedOnWithDecision(status = 400)
            val decision = original.newBasedOnWithDecision(strictMode = false, resiliencyTestSuite = ResiliencyTestSuite.positiveOnly)
            assertThat(decision).isEqualTo(
                Decision.Skip(
                    context = original,
                    reasoning = Reasoning(mainReason = TestSkipReason.GENERATIVE_DISABLED, otherReasons = listOf(TestSkipReason.EXAMPLES_REQUIRED))
                )
            )
        }

        @Test
        fun `newBasedOnWithDecision should return null for bad request scenarios without examples when resiliency suite is all`() {
            val original = scenarioForNewBasedOnWithDecision(status = 400)
            val decision = original.newBasedOnWithDecision(strictMode = false, resiliencyTestSuite = ResiliencyTestSuite.all)
            assertThat(decision).isEqualTo(null)
        }

        @Test
        fun `newBasedOnWithDecision should skip non 2xx and non 400 scenarios without examples with no examples reasoning`() {
            val original = scenarioForNewBasedOnWithDecision(status = 500)
            val decision = original.newBasedOnWithDecision(strictMode = false, resiliencyTestSuite = ResiliencyTestSuite.all)
            assertThat(decision).isEqualTo(
                Decision.Skip(
                    context = original,
                    reasoning = Reasoning(mainReason = TestSkipReason.EXAMPLES_REQUIRED)
                )
            )
        }

        @Test
        fun `newBasedOnWithDecision should execute bad request scenarios with examples`() {
            val original = exampleScenarioForNewBasedOnWithDecision(status = 400)
            val decision = original.newBasedOnWithDecision(strictMode = false, resiliencyTestSuite = ResiliencyTestSuite.positiveOnly)
            assertThat(decision).isEqualTo(
                Decision.Execute(
                    value = original,
                    context = original,
                    reasoning = Reasoning(mainReason = TestExecutionReason.HAS_EXAMPLE)
                )
            )
        }

        @Test
        fun `newBasedOnWithDecision should execute gherkin scenarios without examples even when status is non 2xx`() {
            val original = scenarioForNewBasedOnWithDecision(status = 500, isGherkinScenario = true)
            val decision = original.newBasedOnWithDecision(strictMode = false, resiliencyTestSuite = ResiliencyTestSuite.all)
            assertThat(decision).isEqualTo(
                Decision.Execute(
                    value = original,
                    context = original,
                    reasoning = Reasoning(mainReason = TestExecutionReason.NO_EXAMPLE)
                )
            )
        }

        @Test
        fun `newBasedOnWithDecision should skip 2xx scenarios without examples in strict mode`() {
            val original = scenarioForNewBasedOnWithDecision(status = 200)
            val decision = original.newBasedOnWithDecision(strictMode = true, resiliencyTestSuite = ResiliencyTestSuite.all)
            assertThat(decision).isEqualTo(
                Decision.Skip(
                    context = original,
                    reasoning = Reasoning(mainReason = TestSkipReason.EXAMPLES_REQUIRED_STRICT_MODE)
                )
            )
        }

        private fun scenarioForNewBasedOnWithDecision(name: String = "scenario-name", method: String = "GET", status: Int = 200, examples: List<Examples> = emptyList(), isGherkinScenario: Boolean = false): Scenario {
            return Scenario(
                name = name,
                httpRequestPattern = HttpRequestPattern(method = method),
                httpResponsePattern = HttpResponsePattern(status = status),
                examples = examples,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI,
                isGherkinScenario = isGherkinScenario
            )
        }

        private fun exampleScenarioForNewBasedOnWithDecision(name: String = "scenario-name", status: Int = 200) = scenarioForNewBasedOnWithDecision(
            name = name,
            status = status,
            examples = listOf(Examples(listOf("id"), listOf(Row(mapOf("id" to "123")))))
        )
    }

    @Nested
    inner class FullApiDescriptionTests {
        @Test
        fun `fullApiDescription should return base description when no content types are declared`() {
            val scenario = scenarioForFullApiDescription()
            assertThat(scenario.fullApiDescription).isEqualTo("POST /products -> 201")
        }

        @Test
        fun `fullApiDescription should include request content type only`() {
            val scenario = scenarioForFullApiDescription(requestContentType = "application/json")
            assertThat(scenario.fullApiDescription).isEqualTo("POST /products -> 201 (accepts application/json)")
        }

        @Test
        fun `fullApiDescription should include response content type only`() {
            val scenario = scenarioForFullApiDescription(responseContentType = "application/xml")
            assertThat(scenario.fullApiDescription).isEqualTo("POST /products -> 201 (returns application/xml)")
        }

        @Test
        fun `fullApiDescription should include request and response content types`() {
            val scenario = scenarioForFullApiDescription(requestContentType = "application/json", responseContentType = "application/xml")
            assertThat(scenario.fullApiDescription).isEqualTo("POST /products -> 201 (accepts application/json, returns application/xml)")
        }

        @Test
        fun `fullApiDescription should prefer custom api description`() {
            val scenario = scenarioForFullApiDescription(requestContentType = "application/json", responseContentType = "application/xml", customAPIDescription = "Create product")
            assertThat(scenario.fullApiDescription).startsWith("Create product")
        }

        private fun scenarioForFullApiDescription(requestContentType: String? = null, responseContentType: String? = null, customAPIDescription: String? = null, ): Scenario {
            return Scenario(
                name = "content-types",
                specType = SpecType.OPENAPI,
                protocol = SpecmaticProtocol.HTTP,
                customAPIDescription = customAPIDescription,
                httpRequestPattern = HttpRequestPattern(method = "POST", httpPathPattern = buildHttpPathPattern("/products"), headersPattern = HttpHeadersPattern(contentType = requestContentType)),
                httpResponsePattern = HttpResponsePattern(status = 201, headersPattern = HttpHeadersPattern(contentType = responseContentType)),
            )
        }
    }

    @Nested
    inner class NegativeBasedOnWithDecisionTests {
        @Test
        fun `negativeBasedOnWithDecision should return null for non 2xx scenarios`() {
            val original = scenarioForNegativeBasedOnWithDecision(status = 400)
            val decision = original.negativeBasedOnWithDecision(badRequestOrDefault = null, strictMode = false)
            assertThat(decision).isNull()
        }

        @Test
        fun `negativeBasedOnWithDecision should return modified skip 400 for 2xx scenarios without examples in strict mode`() {
            val original = scenarioForNegativeBasedOnWithDecision(status = 200)
            val decision = original.negativeBasedOnWithDecision(badRequestOrDefault = null, strictMode = true)
            assertThat(decision).isInstanceOf(Decision.Skip::class.java); decision as Decision.Skip
            assertThat(decision.reasoning.mainReason).isEqualTo(TestSkipReason.noExamples2xxAnd400(true))
            assertThat(decision.reasoning.otherReasons).isEmpty()
        }

        @Test
        fun `negativeBasedOnWithDecision should execute negative generation for 2xx scenarios`() {
            val original = scenarioForNegativeBasedOnWithDecision(status = 200, examples = listOf(Examples(listOf("id"), listOf(Row(mapOf("id" to "123"))))))
            val decision = original.negativeBasedOnWithDecision(badRequestOrDefault = null, strictMode = false)
            assertThat(decision).isEqualTo(
                Decision.Execute(
                    value = original.negativeBasedOn(null),
                    context = original,
                    reasoning = Reasoning(mainReason = TestExecutionReason.NEGATIVE_GENERATION_ENABLED)
                )
            )
        }

        @Test
        fun `negativeBasedOnWithDecision should return skip for 2xx scenarios that have no examples`() {
            val original = scenarioForNegativeBasedOnWithDecision(status = 200)
            val badRequestOrDefault = BadRequestOrDefault(mapOf(400 to scenarioForNegativeBasedOnWithDecision(status = 400)), null)
            val decision = original.negativeBasedOnWithDecision(badRequestOrDefault = badRequestOrDefault, strictMode = true)
            assertThat(decision).isEqualTo(
                Decision.Skip(
                    context = scenarioForNegativeBasedOnWithDecision(status = 400),
                    reasoning = Reasoning(mainReason = TestSkipReason.EXAMPLES_REQUIRED_STRICT_MODE)
                )
            )
        }

        @Test
        fun `negativeBasedOnWithDecision should use the explicit bad request status in the skip context`() {
            val original = scenarioForNegativeBasedOnWithDecision(status = 200)
            val badRequestOrDefault = BadRequestOrDefault(mapOf(401 to scenarioForNegativeBasedOnWithDecision(status = 401)), null)
            val decision = original.negativeBasedOnWithDecision(badRequestOrDefault = badRequestOrDefault, strictMode = true)

            assertThat(decision).isInstanceOf(Decision.Skip::class.java); decision as Decision.Skip
            assertThat(decision.context.httpResponsePattern.status).isEqualTo(401)
            assertThat(decision.context.statusInDescription).isEqualTo("401")
            assertThat(decision.reasoning.mainReason).isEqualTo(TestSkipReason.EXAMPLES_REQUIRED_STRICT_MODE)
        }

        @Test
        fun `negativeBasedOnWithDecision should use the default bad request status in the skip context`() {
            val original = scenarioForNegativeBasedOnWithDecision(status = 200)
            val badRequestOrDefault = BadRequestOrDefault(emptyMap(), scenarioForNegativeBasedOnWithDecision(status = DEFAULT_RESPONSE_CODE))
            val decision = original.negativeBasedOnWithDecision(badRequestOrDefault = badRequestOrDefault, strictMode = true)

            assertThat(decision).isInstanceOf(Decision.Skip::class.java); decision as Decision.Skip
            assertThat(decision.context.httpResponsePattern.status).isEqualTo(DEFAULT_RESPONSE_CODE)
            assertThat(decision.context.statusInDescription).isEqualTo(DEFAULT_RESPONSE_CODE.toString())
            assertThat(decision.reasoning.mainReason).isEqualTo(TestSkipReason.EXAMPLES_REQUIRED_STRICT_MODE)
        }

        @Test
        fun `negativeBasedOnWithDecision should execute negative generation even without examples when strict mode is false`() {
            val original = scenarioForNegativeBasedOnWithDecision(status = 200)
            val decision = original.negativeBasedOnWithDecision(badRequestOrDefault = null, strictMode = false)
            assertThat(decision).isEqualTo(
                Decision.Execute(
                    value = original.negativeBasedOn(null),
                    context = original,
                    reasoning = Reasoning(mainReason = TestExecutionReason.NEGATIVE_GENERATION_ENABLED)
                )
            )
        }

        private fun scenarioForNegativeBasedOnWithDecision(name: String = "scenario-name", method: String = "GET", status: Int = 200, examples: List<Examples> = emptyList(), isGherkinScenario: Boolean = false): Scenario {
            return Scenario(
                name = name,
                httpRequestPattern = HttpRequestPattern(method = method),
                httpResponsePattern = HttpResponsePattern(status = status),
                examples = examples,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI,
                isGherkinScenario = isGherkinScenario
            )
        }
    }

    @Test
    fun `response with stub error should return a failure header`() {
        val scenario = Scenario(
            name = "test scenario",
            httpRequestPattern = HttpRequestPattern(method = "GET", httpPathPattern = HttpPathPattern.from("/")),
            httpResponsePattern = HttpResponsePattern(status = 200, body = JSONObjectPattern(mapOf("error" to StringPattern()))),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        )

        val response = scenario.responseWithStubError("error")
        val jsonResponseBody = response.body as JSONObjectValue
        assertThat(jsonResponseBody.getString("error")).isEqualTo("error")
        assertThat(response.headers["X-Specmatic-Result"]).isEqualTo("failure")
    }
}
