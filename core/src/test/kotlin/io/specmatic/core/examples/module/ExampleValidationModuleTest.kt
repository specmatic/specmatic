package io.specmatic.core.examples.module

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.Flags
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.examples.server.ExampleMismatchMessages
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ExampleValidationModuleTest {
    private val exampleValidationModule = ExampleValidationModule(specmaticConfig = SpecmaticConfig())

    @Test
    fun `should be able to match on pattern tokens instead of literal values`() {
        val scenario = Scenario(
            ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                method = "POST",
                httpPathPattern = buildHttpPathPattern("/add"),
                body = JSONObjectPattern(mapOf("first" to NumberPattern(), "second" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(mapOf("result" to NumberPattern()))
            ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        )
        val example = ScenarioStub(
            request = HttpRequest(
                method = "POST",
                path = "/add",
                body = JSONObjectValue(mapOf(
                    "first" to StringValue("(number)"),
                    "second" to NumberValue(10)
                ))
            ),
            response = HttpResponse(
                status = 200,
                body = JSONObjectValue(mapOf(
                    "result" to StringValue("(number)")
                ))
            )
        )

        val result = exampleValidationModule.validateExample(Feature(listOf(scenario), name= "", protocol = SpecmaticProtocol.HTTP), example)
        assertThat(result.toResultIfAny()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should complain when pattern token does not match the underlying pattern`() {
        val scenario = Scenario(
            ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                method = "POST",
                httpPathPattern = buildHttpPathPattern("/add"),
                body = JSONObjectPattern(mapOf("first" to NumberPattern(), "second" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(mapOf("result" to NumberPattern()))
            ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        )
        val example = ScenarioStub(
            request = HttpRequest(
                method = "POST",
                path = "/add",
                body = JSONObjectValue(mapOf(
                    "first" to StringValue("(string)"),
                    "second" to NumberValue(10)
                ))
            ),
            response = HttpResponse(
                status = 200,
                body = JSONObjectValue(mapOf(
                    "result" to StringValue("(uuid)")
                ))
            )
        )

        val result = exampleValidationModule.validateExample(Feature(listOf(scenario), name= "", protocol = SpecmaticProtocol.HTTP), example)
        assertThat(result.report()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: POST /add -> 200
        ${
            toViolationReportString(
                breadCrumb = "REQUEST.BODY.first",
                details = "Specification expected number but example contained string",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY.result",
                details = "Specification expected number but example contained uuid",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should match pattern tokens on schema examples`() {
        val pattern = JSONObjectPattern(mapOf("first" to NumberPattern(), "second" to NumberPattern()))
        val example = JSONObjectValue(mapOf("first" to StringValue("(number)"), "second" to NumberValue(10)))

        val scenario = Scenario(ScenarioInfo(patterns = mapOf("(Test)" to pattern),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI))
        val feature = Feature(listOf(scenario), name= "", protocol = SpecmaticProtocol.HTTP)

        val result = feature.matchResultSchemaFlagBased(null, "Test", example, DefaultMismatchMessages)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should result in a failure on pattern token mismatch on schema examples`() {
        val pattern = JSONObjectPattern(mapOf("first" to NumberPattern(), "second" to NumberPattern()))
        val example = JSONObjectValue(mapOf("first" to StringValue("(string)"), "second" to NumberValue(10)))

        val scenario = Scenario(ScenarioInfo(patterns = mapOf("(Test)" to pattern),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI))
        val feature = Feature(listOf(scenario), name= "", protocol = SpecmaticProtocol.HTTP)

        val result = feature.matchResultSchemaFlagBased(null, "Test", example, DefaultMismatchMessages)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "first",
                details = DefaultMismatchMessages.patternMismatch("number", "string"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should provide meaningful error message when 2xx example has path mutation`() {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(method = "GET", httpPathPattern = buildHttpPathPattern("/test/(id:number)/name/(name:string)")),
                httpResponsePattern = HttpResponsePattern(status = 200),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)
        val example = ScenarioStub(request = HttpRequest(method = "GET", path = "/test/abc/name/123"), response = HttpResponse.OK)
        val result = exampleValidationModule.validateExample(feature, example).toResultIfAny()

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: GET /test/(id:number)/name/(name:string) -> 200
        ${toViolationReportString(
            breadCrumb = "REQUEST.PARAMETERS.PATH.id",
            details = ExampleMismatchMessages.typeMismatch("number", "\"abc\"", "string"),
            StandardRuleViolation.TYPE_MISMATCH
        )}
        """.trimIndent())
    }

    @Test
    fun `should not complain when path mutation happens on 4xx example`() {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(method = "GET", httpPathPattern = buildHttpPathPattern("/test/(id:number)/name/(name:string)")),
                httpResponsePattern = HttpResponsePattern(status = 400),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)
        val example = ScenarioStub(request = HttpRequest(method = "GET", path = "/test/abc/name/123"), response = HttpResponse(status = 400))
        val result = exampleValidationModule.validateExample(feature, example)

        assertThat(result.toResultIfAny()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should be able to validate partial example`(@TempDir tempDir: File) {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    body = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 201,
                    body = JSONObjectPattern(mapOf("id" to NumberPattern(), "name" to StringPattern(), "age" to NumberPattern()))
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)

        val example = ScenarioStub(
            request = HttpRequest("POST", "/test", body = parsedJSONObject("""{"name": "John"}""")),
            response = HttpResponse(status = 201, body = JSONObjectValue(emptyMap()))
        )
        val exampleFile = example.toPartialExample(tempDir)
        val result = exampleValidationModule.validateExample(feature, exampleFile)

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should return failure when partial example has invalid value`(@TempDir tempDir: File) {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    body = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 201,
                    body = JSONObjectPattern(mapOf("id" to NumberPattern(), "name" to StringPattern(), "age" to NumberPattern()))
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)

        val example = ScenarioStub(
            request = HttpRequest("POST", "/test", body = parsedJSONObject("""{"name": 123}""")),
            response = HttpResponse(status = 201, body = parsedJSONObject("""{"id": "abc"}"""))
        )
        val exampleFile = example.toPartialExample(tempDir)
        val result = exampleValidationModule.validateExample(feature, exampleFile)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "partial.REQUEST.BODY.name",
                details = ExampleMismatchMessages.typeMismatch("string", "123", "number"),
                StandardRuleViolation.TYPE_MISMATCH 
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "partial.RESPONSE.BODY.id",
                details = ExampleMismatchMessages.typeMismatch("number", "\"abc\"", "string"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should be able to validate partial example with pattern tokens`(@TempDir tempDir: File) {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    body = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 201,
                    body = JSONObjectPattern(mapOf("id" to NumberPattern(), "name" to StringPattern(), "age" to NumberPattern()))
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)

        val example = ScenarioStub(
            request = HttpRequest("POST", "/test", body = parsedJSONObject("""{"name": "(number)", "age": "(number)"}""")),
            response = HttpResponse(status = 201, body = parsedJSONObject("""{"id": "(string)"}"""))
        )
        val exampleFile = example.toPartialExample(tempDir)
        val result = exampleValidationModule.validateExample(feature, exampleFile)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "partial.REQUEST.BODY.name",
                details = ExampleMismatchMessages.patternMismatch("string", "number"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "partial.RESPONSE.BODY.id",
                details = ExampleMismatchMessages.patternMismatch("number", "string"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should return failure when values are missing on non-partial example`(@TempDir tempDir: File) {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    method = "POST",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    body = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 201,
                    body = JSONObjectPattern(mapOf("id" to NumberPattern(), "name" to StringPattern(), "age" to NumberPattern()))
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)

        val example = ScenarioStub(
            request = HttpRequest("POST", "/test", body = parsedJSONObject("""{"name": "John"}""")),
            response = HttpResponse(status = 201, body = JSONObjectValue(emptyMap()))
        )
        val exampleFile = tempDir.resolve("example.json")
        exampleFile.writeText(example.toJSON().toStringLiteral())
        val result = exampleValidationModule.validateExample(feature, exampleFile)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "REQUEST.BODY.age",
                details = ExampleMismatchMessages.expectedKeyWasMissing("property", "age"),
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY.id",
                details = ExampleMismatchMessages.expectedKeyWasMissing("property", "id"),
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY.name",
                details = ExampleMismatchMessages.expectedKeyWasMissing("property", "name"),
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY.age",
                details = ExampleMismatchMessages.expectedKeyWasMissing("property", "age"),
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should complain when response does not adhere to attribute selection`(@TempDir tempDir: File) {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    method = "GET",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    httpQueryParamPattern = HttpQueryParamPattern(mapOf("columns" to QueryParameterScalarPattern(StringPattern())))
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 201,
                    body = ListPattern(JSONObjectPattern(mapOf("id" to NumberPattern(), "name" to StringPattern(), "age" to NumberPattern())))
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        ).copy(attributeSelectionPattern = AttributeSelectionPattern(defaultFields = listOf("id"), queryParamKey = "columns"))
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)

        val example = ScenarioStub(
            request = HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("columns" to "name"))),
            response = HttpResponse(status = 201, body = parsedJSONArray("""[ {"age": 10, "extra": "value"} ]"""))
        )
        val exampleFile = example.toExample(tempDir)
        val result = exampleValidationModule.validateExample(feature, exampleFile)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[0].id",
                details = AttributeSelectionWithExampleMismatchMessages.expectedKeyWasMissing("property", "id"),
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[0].name",
                details = AttributeSelectionWithExampleMismatchMessages.expectedKeyWasMissing("property", "name"),
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[0].age",
                details = AttributeSelectionWithExampleMismatchMessages.unexpectedKey("property", "age"),
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.BODY[0].extra",
                details = AttributeSelectionWithExampleMismatchMessages.unexpectedKey("property", "extra"), 
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should not complain when mandatory keys are missing because they're not attribute selected`(@TempDir tempDir: File) {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    method = "GET",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    httpQueryParamPattern = HttpQueryParamPattern(mapOf("columns" to QueryParameterScalarPattern(StringPattern())))
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 201,
                    body = ListPattern(JSONObjectPattern(mapOf("id" to NumberPattern(), "name" to StringPattern(), "age" to NumberPattern())))
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        ).copy(attributeSelectionPattern = AttributeSelectionPattern(defaultFields = listOf("id"), queryParamKey = "columns"))
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)

        val example = ScenarioStub(
            request = HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("columns" to "name"))),
            response = HttpResponse(status = 201, body = parsedJSONArray("""[ {"id": 10, "name": "JohnDoe"} ]"""))
        )
        val exampleFile = example.toExample(tempDir)
        val result = exampleValidationModule.validateExample(feature, exampleFile)

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `attribute selection should work with partial example`(@TempDir tempDir: File) {
        val scenario = Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    method = "GET",
                    httpPathPattern = buildHttpPathPattern("/test"),
                    httpQueryParamPattern = HttpQueryParamPattern(mapOf("columns" to QueryParameterScalarPattern(StringPattern())))
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 201,
                    body = ListPattern(JSONObjectPattern(mapOf("id" to NumberPattern(), "name" to StringPattern(), "age" to NumberPattern())))
                ),
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        ).copy(attributeSelectionPattern = AttributeSelectionPattern(defaultFields = listOf("id"), queryParamKey = "columns"))
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)

        val invalidExample = ScenarioStub(
            request = HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("columns" to "name"))),
            response = HttpResponse(status = 201, body = parsedJSONArray("""[ {"age": 10, "extra": "value"} ]"""))
        )
        val validExample = ScenarioStub(
            request = HttpRequest("GET", "/test", queryParams = QueryParameters(mapOf("columns" to "name"))),
            response = HttpResponse(status = 201, body = parsedJSONArray("""[ {"id": 10, "name": "JohnDoe"} ]"""))
        )

        val partialInvalidExample = invalidExample.toPartialExample(tempDir)
        val partialInvalidExampleResult = exampleValidationModule.validateExample(feature, partialInvalidExample)

        val partialValidExample = validExample.toPartialExample(tempDir)
        val partialValidExampleResult = exampleValidationModule.validateExample(feature, partialValidExample)

        assertThat(partialValidExampleResult).isInstanceOf(Result.Success::class.java)
        assertThat(partialInvalidExampleResult).isInstanceOf(Result.Failure::class.java)
        assertThat(partialInvalidExampleResult.reportString()).isEqualToNormalizingWhitespace("""
        
        ${
            toViolationReportString(
                breadCrumb = "partial.RESPONSE.BODY[0].id",
                details = AttributeSelectionWithExampleMismatchMessages.expectedKeyWasMissing("property", "id"),
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "partial.RESPONSE.BODY[0].name",
                details = AttributeSelectionWithExampleMismatchMessages.expectedKeyWasMissing("property", "name"),
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "partial.RESPONSE.BODY[0].age",
                details = AttributeSelectionWithExampleMismatchMessages.unexpectedKey("property", "age"),
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "partial.RESPONSE.BODY[0].extra",
                details = AttributeSelectionWithExampleMismatchMessages.unexpectedKey("property", "extra"),
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should complain if additional out-of-spec headers are included in the example`(@TempDir tempDir: File) {
        val scenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/test"),
                headersPattern = HttpHeadersPattern(mapOf("REQUEST-HEADER" to StringPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                headersPattern = HttpHeadersPattern(mapOf("RESPONSE-HEADER" to StringPattern()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)
        val example = ScenarioStub(
            request = HttpRequest("GET", "/test", headers = mapOf("REQUEST-HEADER" to "request-value", "EXTRA-HEADER" to "extra-value")),
            response = HttpResponse(status = 200, headers = mapOf("RESPONSE-HEADER" to "response-value", "EXTRA-HEADER" to "extra-value"))
        ).toExample(tempDir)
        val result = exampleValidationModule.validateExample(feature, example)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "REQUEST.PARAMETERS.HEADER.EXTRA-HEADER",
                details = ExampleMismatchMessages.unexpectedKey("header", "EXTRA-HEADER"),
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.HEADER.EXTRA-HEADER",
                details = ExampleMismatchMessages.unexpectedKey("header", "EXTRA-HEADER"),
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should validate additional headers if the example is partial`(@TempDir tempDir: File) {
        val scenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/test"),
                headersPattern = HttpHeadersPattern(mapOf("REQUEST-HEADER" to StringPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                headersPattern = HttpHeadersPattern(mapOf("RESPONSE-HEADER" to StringPattern()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)
        val example = ScenarioStub(
            request = HttpRequest("GET", "/test", headers = mapOf("REQUEST-HEADER" to "request-value", "EXTRA-HEADER" to "extra-value")),
            response = HttpResponse(status = 200, headers = mapOf("RESPONSE-HEADER" to "response-value", "EXTRA-HEADER" to "extra-value"))
        ).toPartialExample(tempDir)
        val result = exampleValidationModule.validateExample(feature, example)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "partial.REQUEST.PARAMETERS.HEADER.EXTRA-HEADER",
                details = ExampleMismatchMessages.unexpectedKey("header", "EXTRA-HEADER"),
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "partial.RESPONSE.HEADER.EXTRA-HEADER",
                details = ExampleMismatchMessages.unexpectedKey("header", "EXTRA-HEADER"),
                StandardRuleViolation.UNKNOWN_PROPERTY
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should allow additional headers if extensible schema is set`(@TempDir tempDir: File) {
        val scenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/test"),
                headersPattern = HttpHeadersPattern(mapOf("REQUEST-HEADER" to StringPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                headersPattern = HttpHeadersPattern(mapOf("RESPONSE-HEADER" to StringPattern()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))
        val example = ScenarioStub(
            request = HttpRequest("GET", "/test", headers = mapOf("REQUEST-HEADER" to "request-value", "EXTRA-HEADER" to "extra-value")),
            response = HttpResponse(status = 200, headers = mapOf("RESPONSE-HEADER" to "response-value", "EXTRA-HEADER" to "extra-value"))
        ).toExample(tempDir)

        Flags.using(Flags.EXTENSIBLE_SCHEMA to "true") {
            val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)
            val result = exampleValidationModule.validateExample(feature, example)
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `should validate request content-type for 4xx examples along with path structure and method`(@TempDir tempDir: File) {
        val scenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/test"),
                headersPattern = HttpHeadersPattern(contentType = "application/json-patch-query+json")
            ),
            httpResponsePattern = HttpResponsePattern(status = 400),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))
        val example = ScenarioStub(
            request = HttpRequest("GET", "/test", headers = mapOf("Content-Type" to "application/json")),
            response = HttpResponse(status = 400, headers = mapOf("Content-Type" to "application/json"))
        ).toExample(tempDir)
        val feature = Feature(listOf(scenario), name = "", protocol = SpecmaticProtocol.HTTP)
        val result = exampleValidationModule.validateExample(feature, example)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should be able to validate examples where the OAS has shadowed paths`() {
        val openApiFile = File("src/test/resources/openapi/has_shadow_paths/api.yaml")
        val feature = OpenApiSpecification.fromFile(openApiFile.canonicalPath).toFeature()
        val validExamplesDir = openApiFile.resolveSibling("valid_examples")
        val examples = validExamplesDir.listFiles()

        assertThat(examples).allSatisfy { example ->
            val result = exampleValidationModule.validateExample(feature, example)
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `should provide accurate errors when shadowed paths have invalid external examples`() {
        val openApiFile = File("src/test/resources/openapi/has_shadow_paths/api.yaml")
        val feature = OpenApiSpecification.fromFile(openApiFile.canonicalPath).toFeature()
        val invalidExamplesDir = openApiFile.resolveSibling("invalid_examples")
        val examples = invalidExamplesDir.listFiles()

        assertThat(examples).allSatisfy { example ->
            val requestPath = ExampleFromFile(example).requestPath
            val result = exampleValidationModule.validateExample(feature, example)

            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).satisfiesAnyOf(
                {
                    assertThat(requestPath).isIn("/test/latest", "/123/reports/456")
                    assertThat(it).isEqualToNormalizingWhitespace("""
                    ${toViolationReportString(
                        breadCrumb = "REQUEST.BODY.value",
                        details = ExampleMismatchMessages.typeMismatch("boolean", "123", "number"),
                        StandardRuleViolation.TYPE_MISMATCH
                    )}
                    ${toViolationReportString(
                        breadCrumb = "RESPONSE.BODY.value",
                        details = ExampleMismatchMessages.typeMismatch("boolean", "123", "number"),
                        StandardRuleViolation.TYPE_MISMATCH
                    )}
                    """.trimIndent())
                },
                {
                    assertThat(requestPath).isIn("/test/123", "/reports/123/latest")
                    assertThat(it).isEqualToNormalizingWhitespace("""
                    ${toViolationReportString(
                        breadCrumb = "REQUEST.BODY.value",
                        details = ExampleMismatchMessages.typeMismatch("number", "true", "boolean"),
                        StandardRuleViolation.TYPE_MISMATCH
                    )}
                    ${toViolationReportString(
                        breadCrumb = "RESPONSE.BODY.value",
                        details = ExampleMismatchMessages.typeMismatch("number", "true", "boolean"),
                        StandardRuleViolation.TYPE_MISMATCH
                    )}
                    """.trimIndent())
                }
            )
        }
    }

    private fun ScenarioStub.toPartialExample(tempDir: File): File {
        val example = JSONObjectValue(mapOf("partial" to this.toJSON()))
        val exampleFile = tempDir.resolve("example.json")
        exampleFile.writeText(example.toStringLiteral())
        return exampleFile
    }

    private fun ScenarioStub.toExample(tempDir: File): File {
        val example = this.toJSON()
        val exampleFile = tempDir.resolve("example.json")
        exampleFile.writeText(example.toStringLiteral())
        return exampleFile
    }
}