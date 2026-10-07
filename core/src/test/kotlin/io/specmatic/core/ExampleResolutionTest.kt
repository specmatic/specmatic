package io.specmatic.core

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.matchers.MatcherResolutionMode
import io.specmatic.core.matchers.MatcherEngine
import io.specmatic.core.matchers.TemplateResolver
import io.specmatic.core.examples.source.PreLoadedExampleObjects
import io.specmatic.core.pattern.*
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.ThreadSafeListOfStubs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class ExampleResolutionTest {
    private val matcherEngine = MatcherEngineTestSupport()
    private val feature = OpenApiSpecification.fromYAML(openApi, "external-example.yaml").toFeature()
    private val data = JSONObjectValue(
        mapOf(
            "data" to JSONObjectValue(
                mapOf(
                    "requestCode" to NumberValue(42),
                    "responseId" to StringValue("response-from-data"),
                ),
            ),
        ),
    )

    @BeforeEach
    fun setUpMatcherEngine() {
        mockkObject(MatcherEngine.Companion)
        every { MatcherEngine.load() } returns matcherEngine
    }

    @AfterEach
    fun tearDownMatcherEngine() {
        unmockkObject(MatcherEngine.Companion)
    }

    @Nested
    inner class ExternalExampleValidation {
        @Test
        fun `validates request and response references with the external data in load-time mode`() {
            val stub = externalStub(data)
            val result = feature.matchResultFlagBased(stub, DefaultMismatchMessages).toResultIfAny()
            val row = Row(requestExample = stub.request, responseExample = stub.response, scenarioStub = stub)

            feature.copy(
                scenarios = listOf(
                    element = feature.scenarios.single()
                        .copy(examples = listOf(Examples(rows = listOf(row))))
                ),
            ).validateExamplesOrException()

            assertThat(stub.data).isEqualTo(data)
            assertThat(result.isSuccess()).isTrue()
            assertThat(matcherEngine.resolutionCalls).isNotEmpty
            assertThat(stub.request.body).isEqualTo(matcherRequestBody)
            assertThat(matcherEngine.resolutionCalls.all { it.data == data && it.resolutionMode == MatcherResolutionMode.LOAD_TIME }).isTrue()
        }

        @Test
        fun `loads an external row when a missing reference prevents value matching`() {
            val stub = externalStub(JSONObjectValue())
            val source = PreLoadedExampleObjects(listOf(stub), SpecmaticConfig())

            val (featureWithExample, unusedExamples) = feature.loadExternalisedExamplesAndListUnloadableExamples(source)
            assertThat(unusedExamples).isEmpty()

            val rows = featureWithExample.scenarios.single().examples.flatMap { it.rows }
            assertThat(rows).hasSize(1)
            assertThat(stub.request.body).isEqualTo(matcherRequestBody)
            assertThat(rows.single().scenarioStub?.data).isEqualTo(JSONObjectValue())
        }
    }

    @Nested
    inner class HeaderNameCasing {
        @Test
        fun `matches headers case insensitively without changing json property names`() {
            val matcherValue = StringValue($$"$match(exact: $(data.responseId))")
            val templateResolver = checkNotNull(TemplateResolver.create(MatcherResolutionMode.LOAD_TIME))
            val resolvedHeaders = HttpHeadersPattern(mapOf("X-Trace-Id" to StringPattern()))
                .resolveTemplates(
                    data = data,
                    resolver = Resolver(),
                    headers = mapOf("x-trace-id" to matcherValue.string),
                    engine = templateResolver,
                ).value

            val jsonPattern = JSONObjectPattern(mapOf("X-Trace-Id" to StringPattern()))
            val jsonValue = JSONObjectValue(mapOf("x-trace-id" to matcherValue))
            templateResolver.resolve(
                data = data,
                value = jsonValue,
                pattern = jsonPattern,
                resolver = Resolver(),
            )

            assertThat(resolvedHeaders).isEqualTo(mapOf("x-trace-id" to "response-from-data"))
            assertThat(matcherEngine.resolutionCalls).isEqualTo(
                listOf(
                    MatcherResolutionCall(
                        data = data,
                        resolutionMode = MatcherResolutionMode.LOAD_TIME,
                        value = JSONObjectValue(mapOf("x-trace-id" to matcherValue)),
                        pattern = JSONObjectPattern(mapOf("x-trace-id" to StringPattern())),
                    ),
                    MatcherResolutionCall(
                        data = data,
                        value = jsonValue,
                        pattern = jsonPattern,
                        resolutionMode = MatcherResolutionMode.LOAD_TIME,
                    ),
                ),
            )
        }
    }

    @Nested
    inner class MockAssociation {
        @Test
        fun `associates a full external stub without replacing its authored values or data`() {
            val stub = externalStub(data)
            val httpStubData = feature.matchingStub(stub)

            assertThat(httpStubData.data).isEqualTo(data)
            assertThat(httpStubData.scenarioStub).isSameAs(stub)
            assertThat(stub.request.body).isEqualTo(matcherRequestBody)
            assertThat(stub.response.body).isEqualTo(matcherResponseBody)
            assertThat(httpStubData.response.body).isEqualTo(matcherResponseBody)
            assertThat(
                httpStubData.requestType.matches(
                    incomingHttpRequest = HttpRequest(
                        path = "/items",
                        method = "POST",
                        body = JSONObjectValue(mapOf("code" to NumberValue(42))),
                    ),
                    resolver = httpStubData.resolver,
                ).isSuccess(),
            ).isTrue()
        }

        @Test
        fun `serves a full external stub with request and response references resolved from data`() {
            val stub = externalStub(data)
            val httpStubData = feature.matchingStub(stub)
            val (servedStub, _) = ThreadSafeListOfStubs(mutableListOf(httpStubData), emptyMap())
                .matchingStaticStub(resolvedRequest())

            assertThat(servedStub).isNotNull
            assertThat(servedStub?.scenarioStub).isSameAs(stub)
            assertThat(servedStub?.response?.body).isEqualTo(resolvedResponseBody)
        }

        @Test
        fun `associates a partial external stub using resolved temporary request and response values`() {
            val partial = ScenarioStub(request = externalRequest(), response = externalResponse(), strictMode = false)
            val stub = ScenarioStub(partial = partial, data = data, strictMode = false)
            val httpStubData = feature.matchingStub(stub)

            assertThat(httpStubData.data).isEqualTo(data)
            assertThat(httpStubData.partial).isSameAs(partial)
            assertThat(httpStubData.scenarioStub).isSameAs(stub)
            assertThat(stub.partial?.request?.body).isEqualTo(matcherRequestBody)
            assertThat(stub.partial?.response?.body).isEqualTo(matcherResponseBody)
        }

        @Test
        fun `serves a partial external stub after resolving its request reference from data`() {
            val partial = ScenarioStub(request = externalRequest(), response = externalResponse(), strictMode = false)
            val stub = ScenarioStub(partial = partial, data = data, strictMode = false)
            val httpStubData = feature.matchingStub(stub)
            val (servedStub, matchResults) = ThreadSafeListOfStubs(mutableListOf(httpStubData), emptyMap())
                .matchingStaticStub(resolvedRequest())

            assertThat(servedStub).isNotNull
            assertThat(servedStub?.partial).isSameAs(partial)
            assertThat(servedStub?.scenarioStub).isSameAs(stub)
            assertThat(matchResults.single().first.isSuccess()).isTrue()
            assertThat(servedStub?.response?.body).isEqualTo(resolvedResponseBody)
        }
    }

    private fun resolvedRequest() = HttpRequest(
        method = "POST",
        path = "/items",
        body = JSONObjectValue(mapOf("code" to NumberValue(42))),
    )

    private fun externalStub(data: JSONObjectValue) = ScenarioStub(
        data = data,
        strictMode = false,
        request = externalRequest(),
        response = externalResponse(),
    )

    private fun externalRequest() = HttpRequest(
        method = "POST",
        path = "/items",
        body = matcherRequestBody,
    )

    private fun externalResponse() = HttpResponse(
        status = 200,
        body = matcherResponseBody,
    )

    companion object {
        private val matcherRequestBody = JSONObjectValue(mapOf("code" to StringValue($$"$match(exact: $(data.requestCode))")))
        private val matcherResponseBody = JSONObjectValue(mapOf("id" to StringValue($$"$match(exact: $(data.responseId))")))
        private val resolvedResponseBody = JSONObjectValue(mapOf("id" to StringValue("response-from-data")))
        private val openApi = """
        openapi: 3.0.0
        info:
          title: Example
          version: 1.0.0
        paths:
          /items:
            post:
              requestBody:
                required: true
                content:
                  application/json: {schema: {type: object, properties: {code: {type: integer}}}}
              responses:
                '200':
                  description: successful response
                  content:
                    application/json: {schema: {type: object, properties: {id: {type: string}}}}
        """.trimIndent()
    }
}
