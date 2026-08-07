package io.specmatic.core.examples.module

import io.specmatic.core.DEFAULT_RESPONSE_CODE
import io.specmatic.core.ExampleType
import io.specmatic.core.Feature
import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.ScenarioInfo
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.buildHttpPathPattern
import io.specmatic.core.examples.source.PreLoadedExampleObjects
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.StringValue
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ResponseStatusSelectionTest {
    private val request = HttpRequest(method = "GET", path = "/products")

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `example validation does not fall through to default when the concrete status is declared`(defaultFirst: Boolean) {
        val feature = featureWithExplicitAndDefault(defaultFirst)
        val example = ScenarioStub(
            request = request,
            response = HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "text/plain"),
                body = StringValue("accepted only by default")
            )
        )

        val result = ExampleValidationModule(specmaticConfig = SpecmaticConfig())
            .validateExample(feature, example)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `example validation rejects explicit status content type mismatch when default has the same content type`(defaultFirst: Boolean) {
        val feature = featureWithExplicitAndDefault(defaultFirst, defaultContentType = "application/json")
        val example = ScenarioStub(
            request = request,
            response = HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "text/plain"),
                body = StringValue("does not match either response representation")
            )
        )

        val result = ExampleValidationModule(specmaticConfig = SpecmaticConfig())
            .validateExample(feature, example)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `mock expectation does not fall through to default when the concrete status is declared`(defaultFirst: Boolean) {
        val feature = featureWithExplicitAndDefault(defaultFirst)
        val expectation = HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "text/plain"),
            body = StringValue("accepted only by default")
        )

        assertThatThrownBy { feature.matchingStub(request, expectation) }
            .isInstanceOf(NoMatchingScenario::class.java)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `mock expectation does not fall through to default after explicit response body mismatch`(defaultFirst: Boolean) {
        val feature = featureWithExplicitAndDefault(defaultFirst, defaultContentType = "application/json")
        val expectation = HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = StringValue("accepted only by default")
        )

        assertThatThrownBy { feature.matchingStub(request, expectation) }
            .isInstanceOf(NoMatchingScenario::class.java)
    }

    @Test
    fun `partial mock expectation does not fall through to default when the concrete status is declared`() {
        val feature = featureWithExplicitAndDefault(defaultFirst = true)
        val partialExpectation = ScenarioStub(
            partial = ScenarioStub(
                request = request,
                response = HttpResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to "text/plain"),
                    body = StringValue("accepted only by default")
                )
            )
        )

        assertThatThrownBy { feature.matchingStub(partialExpectation) }
            .isInstanceOf(NoMatchingScenario::class.java)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `inline example is validated only against its declared response status`(defaultFirst: Boolean) {
        val feature = OpenApiSpecification.fromYAML(openApiWithInvalidInlineExample(defaultFirst), "").toFeature()

        val validationResults = ExampleValidationModule(specmaticConfig = SpecmaticConfig())
            .validateInlineExamples(feature, feature.inlineNamedStubs)

        assertThat(validationResults).hasSize(1)
        assertThat(validationResults.values.single()).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `mock expectation uses default for an undeclared concrete status and preserves that status`() {
        val feature = Feature(
            scenarios = listOf(defaultScenario()),
            name = "default response",
            protocol = SpecmaticProtocol.HTTP
        )
        val expectation = HttpResponse(
            status = 500,
            headers = mapOf("Content-Type" to "text/plain"),
            body = StringValue("default response")
        )

        val stub = feature.matchingStub(request, expectation)

        assertThat(stub.scenario?.status).isEqualTo(DEFAULT_RESPONSE_CODE)
        assertThat(stub.response.status).isEqualTo(500)
    }

    @Test
    fun `external example with undeclared status is associated only with default response`() {
        val explicit = explicitScenario(status = 200)
        val default = defaultScenario()
        val loadedFeature = Feature(
            scenarios = listOf(explicit, default),
            name = "external examples",
            protocol = SpecmaticProtocol.HTTP
        ).loadExternalisedExamplesAndListUnloadableExamples(externalExample(status = 500)).first

        assertThat(loadedFeature.scenarios.single { it.status == 200 }.externalExampleRows()).isEmpty()
        assertThat(loadedFeature.scenarios.single { it.status == DEFAULT_RESPONSE_CODE }.externalExampleRows()).hasSize(1)
        assertThat(loadedFeature.scenarios.single { it.status == DEFAULT_RESPONSE_CODE }.externalExampleRows().single().responseExample?.status).isEqualTo(500)
    }

    @Test
    fun `external example with declared status is associated only with explicit response`() {
        val explicit = explicitScenario(status = 500, contentType = "text/plain", body = StringPattern())
        val default = defaultScenario()
        val loadedFeature = Feature(
            scenarios = listOf(default, explicit),
            name = "external examples",
            protocol = SpecmaticProtocol.HTTP
        ).loadExternalisedExamplesAndListUnloadableExamples(externalExample(status = 500)).first

        assertThat(loadedFeature.scenarios.single { it.status == 500 }.externalExampleRows()).hasSize(1)
        assertThat(loadedFeature.scenarios.single { it.status == DEFAULT_RESPONSE_CODE }.externalExampleRows()).isEmpty()
    }

    private fun featureWithExplicitAndDefault(
        defaultFirst: Boolean,
        defaultContentType: String = "text/plain"
    ): Feature {
        val explicit = explicitScenario(
            status = 200,
            contentType = "application/json",
            body = JSONObjectPattern(mapOf("name" to StringPattern()))
        )
        val default = defaultScenario(defaultContentType)
        return Feature(
            scenarios = if (defaultFirst) listOf(default, explicit) else listOf(explicit, default),
            name = "response selection",
            protocol = SpecmaticProtocol.HTTP
        )
    }

    private fun explicitScenario(
        status: Int,
        contentType: String = "application/json",
        body: Pattern = JSONObjectPattern(mapOf("name" to StringPattern()))
    ): Scenario = responseScenario(status, contentType, body)

    private fun defaultScenario(contentType: String = "text/plain"): Scenario = responseScenario(
        status = DEFAULT_RESPONSE_CODE,
        contentType = contentType,
        body = StringPattern()
    )

    private fun responseScenario(
        status: Int,
        contentType: String,
        body: Pattern
    ): Scenario = Scenario(
        ScenarioInfo(
            scenarioName = "GET products -> $status",
            httpRequestPattern = HttpRequestPattern(method = "GET", httpPathPattern = buildHttpPathPattern("/products")),
            httpResponsePattern = HttpResponsePattern(
                status = status,
                headersPattern = HttpHeadersPattern(contentType = contentType),
                body = body
            ),
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )
    )

    private fun externalExample(status: Int): PreLoadedExampleObjects {
        val examples = PreLoadedExampleObjects.transform(
            specmaticConfig = SpecmaticConfig(),
            examples = listOf(
                ScenarioStub(
                    request = request,
                    response = HttpResponse(
                        status = status,
                        headers = mapOf("Content-Type" to "text/plain"),
                        body = StringValue("default response")
                    ),
                    exampleType = ExampleType.EXTERNAL
                )
            )
        )
        return PreLoadedExampleObjects(examples = examples)
    }

    private fun openApiWithInvalidInlineExample(defaultFirst: Boolean): String {
        val explicitResponse = """
            '200':
              description: explicit response
              content:
                application/json:
                  schema:
                    type: object
                    required: [name]
                    properties:
                      name:
                        type: string
                  examples:
                    invalid-explicit-example:
                      value: accepted only by default
        """.trimIndent()
        val defaultResponse = """
            default:
              description: default response
              content:
                application/json:
                  schema:
                    type: string
        """.trimIndent()
        val responses = if (defaultFirst) "$defaultResponse\n$explicitResponse" else "$explicitResponse\n$defaultResponse"

        val header = """
            openapi: 3.0.0
            info:
              title: Response selection
              version: 1.0.0
            paths:
              /products:
                get:
                  responses:
        """.trimIndent()
        return "$header\n${responses.prependIndent("        ")}"
    }

    private fun Scenario.externalExampleRows() = examples
        .flatMap { it.rows }
        .filter { it.exampleType == ExampleType.EXTERNAL }
}
