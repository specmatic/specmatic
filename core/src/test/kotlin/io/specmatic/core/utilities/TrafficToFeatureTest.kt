package io.specmatic.core.utilities

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.NamedStub
import io.specmatic.core.NoBodyValue
import io.specmatic.core.value.EmptyString
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TrafficToFeatureTest {
    @Test
    fun `normalizes generated traffic examples without changing the retained stub`() {
        val stub = ScenarioStub(
            request = HttpRequest(
                method = "POST",
                path = "/items",
                headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
                body = NoBodyValue,
            ),
            response = HttpResponse(
                status = 204,
                headers = mapOf("Content-Type" to "text/plain; charset=utf-8"),
            ),
        )

        val feature = featureFromTraffic("Traffic", listOf(NamedStub("empty body", stub)))
        val row = feature.scenarios.single().examples.single().rows.single()
        val generatedRequest = feature.generateContractTestScenarios()
            .single().second.value.generateHttpRequest()

        assertThat(row.requestExample).isEqualTo(
            stub.request.copy(headers = mapOf("Content-Type" to "application/json"), body = EmptyString)
        )
        assertThat(row.responseExample).isEqualTo(
            stub.response.copy(headers = mapOf("Content-Type" to "text/plain"))
        )
        assertThat(row.scenarioStub).isEqualTo(stub)
        assertThat(generatedRequest).isEqualTo(row.requestExample)
    }
}
