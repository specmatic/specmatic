package io.specmatic.stub.listener

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.StubConfiguration
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.model.TestResult
import io.specmatic.stub.HttpStub
import io.specmatic.stub.SpecmaticConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class MockEventListenerTest {
    companion object {
        private val openApiFile = File("src/test/resources/openapi/partial_example_tests/simple.yaml")
        private val inlineExamplesOpenApiFile = File("src/test/resources/openapi/has_multiple_inline_examples.yaml")
        private val generative4xxOpenApiFile = File("src/test/resources/openapi/mock_event_listener_generative_4xx.yaml")
        val feature = parseContractFileToFeature(openApiFile)
        val featureWithInlineExamples = parseContractFileToFeature(inlineExamplesOpenApiFile)
        val featureWithGenerative4xxResponse = parseContractFileToFeature(generative4xxOpenApiFile)
    }

    private fun captureMockEvents(testBlock: (MockEventListener) -> Unit): List<MockEvent> {
        val events = mutableListOf<MockEvent>()
        val listener = object : MockEventListener {
            override fun onRespond(data: MockEvent) {
                events.add(data)
            }
        }

        testBlock(listener)
        return events
    }

    @Test
    fun `should callback with appropriate data when request matches the contract`() {
        val captureMockEvents = captureMockEvents { listener ->
            HttpStub(feature, listeners = listOf(listener)).use { stub ->
                val validRequest = feature.scenarios.first().generateHttpRequest()
                stub.client.execute(validRequest)
            }
        }

        assertThat(captureMockEvents).hasSize(1)
        val event = captureMockEvents.single()
        assertThat(event.name).isEqualToIgnoringWhitespace("Scenario: PATCH /creators/(creatorId:number)/pets/(petId:number) -> 201")
        assertThat(event.details).isEqualToIgnoringWhitespace("Request Matched Contract PATCH /creators/(creatorId:number)/pets/(petId:number) -> 201")
        assertThat(event.scenario).isEqualTo(feature.scenarios.first())
        assertThat(event.response).isNotNull
        assertThat(event.responseTime).isNotNull()
        assertThat(event.stubResult).isEqualTo(TestResult.Success)
    }

    @Test
    fun `should mention matched example in name and details if match occurs`() {
        val (request, response) = feature.scenarios.first().let {
            it.generateHttpRequest() to it.generateHttpResponse(emptyMap()).copy(headers = emptyMap())
        }

        val exampleStub = ScenarioStub(request = request, response = response, filePath = "examples/example.json")
        val captureMockEvents = captureMockEvents { listener ->
            HttpStub(feature, scenarioStubs = listOf(exampleStub), listeners = listOf(listener)).use { stub ->
                stub.client.execute(request)
            }
        }

        assertThat(captureMockEvents).hasSize(1)
        val event = captureMockEvents.single()
        assertThat(event.name).contains("external example 'example.json'")
        assertThat(event.details).contains("Request Matched External Example: examples/example.json")
        assertThat(event.stubResult).isEqualTo(TestResult.Success)
    }

    @Test
    fun `should include inline example name in mock event when request matches inline example`() {
        val events = captureMockEvents { listener ->
            HttpStub(featureWithInlineExamples, listeners = listOf(listener)).use { stub ->
                stub.client.execute(
                    HttpRequest(
                        method = "GET",
                        path = "/findAvailableProducts",
                        headers = mapOf("pageSize" to "20"),
                        queryParametersMap = mapOf("type" to "other")
                    )
                )
            }
        }

        assertThat(events).hasSize(1)
        assertThat(events.single().details).isEqualTo("Request Matched Inline Example: FIND_TIMEOUT")
        assertThat(events.single().name).contains("inline example 'FIND_TIMEOUT'")
        assertThat(events.single().name).contains("FIND_TIMEOUT")
        assertThat(events.single().stubResult).isEqualTo(TestResult.Success)
        assertThat(events.single().result).isEqualTo(Result.Success())
        assertThat(events.single().response?.status).isEqualTo(503)
    }

    @Test
    fun `should choose the correct inline example name when multiple inline examples exist`() {
        val events = captureMockEvents { listener ->
            HttpStub(featureWithInlineExamples, listeners = listOf(listener)).use { stub ->
                stub.client.execute(
                    HttpRequest(
                        method = "GET",
                        path = "/findAvailableProducts",
                        headers = mapOf("pageSize" to "10"),
                        queryParametersMap = mapOf("type" to "gadget")
                    )
                )
            }
        }

        assertThat(events).hasSize(1)
        assertThat(events.single().details).isEqualTo("Request Matched Inline Example: FIND_SUCCESS")
        assertThat(events.single().name).contains("inline example 'FIND_SUCCESS'")
        assertThat(events.single().name).contains("FIND_SUCCESS")
        assertThat(events.single().name).doesNotContain("FIND_TIMEOUT")
        assertThat(events.single().response?.status).isEqualTo(200)
    }

    @Test
    fun `should provide nearest matching scenario details for bad request with no examples`() {
        val events = captureMockEvents { listener ->
            HttpStub(feature, listeners = listOf(listener)).use { stub ->
                val request = feature.scenarios.first().generateHttpRequest()
                stub.client.execute(request.updateBody(NullValue))
            }
        }

        assertThat(events).hasSize(1)
        val event = events.single()
        assertThat(event.name).isEqualToIgnoringWhitespace("Scenario: PATCH /creators/(creatorId:number)/pets/(petId:number) -> 201")
        assertThat(event.details).contains("Specification expected json object but request contained an empty string or no body value")
        assertThat(event.scenario).isEqualTo(feature.scenarios.first())
        assertThat(event.stubResult).isEqualTo(TestResult.Failed)
    }

    @Test
    fun `should send generated 400 response data to listener when generative stub is enabled`() {
        val feature = featureWithGenerative4xxResponse
        val config = SpecmaticConfigV1V2Common(stub = StubConfiguration(generative = true))
        val events = captureMockEvents { listener ->
            HttpStub(features = listOf(feature), listeners = listOf(listener), specmaticConfigSource = SpecmaticConfigSource.fromConfig(config)).use { stub ->
                stub.client.execute(HttpRequest(method = "POST", path = "/hello", body = parsedJSONObject("""{"data": 10}""")))
            }
        }

        assertThat(events).hasSize(1)
        val event = events.single()
        assertThat(event.response?.status).isEqualTo(400)
        assertThat(event.scenario?.status).isEqualTo(400)
        assertThat(event.stubResult).isEqualTo(TestResult.Success)
        assertThat(event.result).isEqualTo(Result.Success())
    }

    @Test
    fun `should report failure to listener for same invalid request when generative stub is disabled`() {
        val feature = featureWithGenerative4xxResponse
        val config = SpecmaticConfigV1V2Common(stub = StubConfiguration(generative = false))
        val events = captureMockEvents { listener ->
            HttpStub(features = listOf(feature), listeners = listOf(listener), specmaticConfigSource = SpecmaticConfigSource.fromConfig(config)).use { stub ->
                stub.client.execute(HttpRequest(method = "POST", path = "/hello", body = parsedJSONObject("""{"data": 10}""")))
            }
        }

        assertThat(events).hasSize(1)
        val event = events.single()
        assertThat(event.response?.status).isEqualTo(400)
        assertThat(event.scenario?.status).isNotEqualTo(event.response?.status)
        assertThat(event.stubResult).isEqualTo(TestResult.Failed)
        assertThat(event.result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `strict generative mock event should keep 400 response format but remain failure`() {
        val first400Scenario = featureWithGenerative4xxResponse.scenarios.first { it.status == 400 }
        val event = MockEvent(
            HttpLogMessage(
                request = HttpRequest(method = "POST", path = "/hello", body = parsedJSONObject("""{"data": 10}""")),
                response = HttpResponse(status = 400, body = parsedJSONObject("""{"message": "REQUEST.BODY.data mismatch"}""")),
                scenario = first400Scenario,
                result = Result.Failure("strict mode mismatch")
            )
        )

        assertThat(event.response?.status).isEqualTo(400)
        assertThat(event.response?.body).isInstanceOf(JSONObjectValue::class.java)
        assertThat(event.scenario?.status).isEqualTo(400)
        assertThat(event.result).isInstanceOf(Result.Failure::class.java)
        assertThat(event.stubResult).isEqualTo(TestResult.Failed)
    }

    @Test
    fun `should return missing-in-spec when request doesn't match any scenario identifiers`() {
        val events = captureMockEvents { listener ->
            HttpStub(feature, listeners = listOf(listener)).use { stub ->
                val request = feature.scenarios.first().generateHttpRequest()
                stub.client.execute(request.updatePath("/test"))
            }
        }

        assertThat(events).hasSize(1)
        val event = events.single()
        assertThat(event.name).isEqualTo("Unknown Request")
        assertThat(event.details).isEqualTo("No matching REST stub or contract found for method PATCH and path /test")
        assertThat(event.scenario).isNull()
        assertThat(event.stubResult).isEqualTo(TestResult.MissingInSpec)
    }

    @Test
    fun `should not emit mock events for internal control endpoints`() {
        val events = captureMockEvents { listener ->
            HttpStub(feature, listeners = listOf(listener)).use { stub ->
                stub.client.execute(HttpRequest("GET", "/_specmatic/log"))
                stub.client.execute(HttpRequest("POST", "/_specmatic/expectations"))
                stub.client.execute(HttpRequest("DELETE", "/_specmatic/http-stub/123"))
                stub.client.execute(HttpRequest("POST", "/_specmatic/verify", queryParametersMap = mapOf("exampleId" to "abc123")))
                stub.client.execute(HttpRequest("GET", "/swagger/v1/swagger.yaml"))
                stub.client.execute(HttpRequest("HEAD", "/"))
                stub.client.execute(HttpRequest("GET", "/actuator/health"))
            }
        }
        assertThat(events).isEmpty()
    }

    @Test
    fun `should continue emitting mock events for non-internal endpoints`() {
        val events = captureMockEvents { listener ->
            HttpStub(feature, listeners = listOf(listener)).use { stub ->
                val validRequest = feature.scenarios.first().generateHttpRequest()
                stub.client.execute(validRequest)
            }
        }

        assertThat(events).hasSize(1)
        assertThat(events.single().scenario).isEqualTo(feature.scenarios.first())
    }
}
