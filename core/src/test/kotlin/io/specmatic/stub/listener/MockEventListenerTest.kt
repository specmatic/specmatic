package io.specmatic.stub.listener

import io.specmatic.core.TestResult
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.NullValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class MockEventListenerTest {

    companion object {
        private val openApiFile = File("src/test/resources/openapi/partial_example_tests/simple.yaml")
        val feature = parseContractFileToFeature(openApiFile)
    }

    @Test
    fun `should callback with appropriate data when request matches the contract`() {
        val listener = object : MockEventListener {
            override fun onRespond(data: MockEvent) {
                assertThat(data.name).isEqualToIgnoringWhitespace("Scenario: PATCH /creators/(creatorId:number)/pets/(petId:number) -> 201")
                assertThat(data.details).isEqualToIgnoringWhitespace("Request Matched Contract PATCH /creators/(creatorId:number)/pets/(petId:number) -> 201")
                assertThat(data.scenario).isEqualTo(feature.scenarios.first())
                assertThat(data.response).isNotNull
                assertThat(data.responseTime).isNotNull()
                assertThat(data.result).isEqualTo(TestResult.Success)
            }
        }

        HttpStub(feature, listeners = listOf(listener)).use { stub ->
            val validRequest = feature.scenarios.first().generateHttpRequest()
            stub.client.execute(validRequest)
        }
    }

    @Test
    fun `should mention matched example in name and details if match occurs`() {
        val listener = object : MockEventListener {
            override fun onRespond(data: MockEvent) {
                assertThat(data.name).contains("EX:example.json")
                assertThat(data.details).contains("Request Matched Example: examples/example.json")
                assertThat(data.result).isEqualTo(TestResult.Success)
            }
        }

        val (request, response) = feature.scenarios.first().let {
            it.generateHttpRequest() to it.generateHttpResponse(emptyMap()).copy(headers = emptyMap())
        }
        val exampleStub = ScenarioStub(request = request, response = response, filePath = "examples/example.json")
        HttpStub(feature, scenarioStubs = listOf(exampleStub), listeners = listOf(listener)).use { stub ->
            stub.client.execute(request)
        }
    }

    @Test
    fun `should provide nearest matching scenario details for bad request with no examples`() {
        val listener = object : MockEventListener {
            override fun onRespond(data: MockEvent) {
                assertThat(data.name).isEqualToIgnoringWhitespace("Scenario: PATCH /creators/(creatorId:number)/pets/(petId:number) -> 201")
                assertThat(data.details).contains("Contract expected json object but request contained an empty string or no body value")
                assertThat(data.scenario).isEqualTo(feature.scenarios.first())
                assertThat(data.result).isEqualTo(TestResult.Failed)
            }
        }

        HttpStub(feature, listeners = listOf(listener)).use { stub ->
            val request = feature.scenarios.first().generateHttpRequest()
            stub.client.execute(request.updateBody(NullValue))
        }
    }

    @Test
    fun `should return missing-in-spec when request doesn't match any scenario identifiers`() {
        val listener = object : MockEventListener {
            override fun onRespond(data: MockEvent) {
                assertThat(data.name).isEqualTo("Unknown Request")
                assertThat(data.details).isEqualTo("No matching REST stub or contract found for method PATCH and path /test")
                assertThat(data.scenario).isNull()
                assertThat(data.result).isEqualTo(TestResult.MissingInSpec)
            }
        }

        HttpStub(feature, listeners = listOf(listener)).use { stub ->
            val request = feature.scenarios.first().generateHttpRequest()
            stub.client.execute(request.updatePath("/test"))
        }
    }
}