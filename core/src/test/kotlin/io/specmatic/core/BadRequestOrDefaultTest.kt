package io.specmatic.core

import io.ktor.http.HttpStatusCode
import io.specmatic.core.BadRequestOrDefault.Companion.updateScenarioWithBadRequestPattern
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType

class BadRequestOrDefaultTest {
    @Test
    fun `matches should use explicit bad request response when available`() {
        val badRequestOrDefault = BadRequestOrDefault(
            badRequestResponses = mapOf(401 to scenarioWithStatus(401)),
            defaultResponse = scenarioWithStatus(DEFAULT_RESPONSE_CODE)
        )

        assertThat(badRequestOrDefault.supports(401)).isTrue()
        assertThat(badRequestOrDefault.matches(HttpResponse(401), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `matches should fall back to default response when no explicit bad request exists`() {
        val badRequestOrDefault = BadRequestOrDefault(badRequestResponses = emptyMap(), defaultResponse = scenarioWithStatus(DEFAULT_RESPONSE_CODE))
        assertThat(badRequestOrDefault.supports(402)).isTrue()
        assertThat(badRequestOrDefault.matches(HttpResponse(402), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `supports should be false when there is no bad request response or default response`() {
        val badRequestOrDefault = BadRequestOrDefault(emptyMap(), null)
        assertThat(badRequestOrDefault.supports(HttpStatusCode.BadRequest.value)).isFalse()
    }

    @Test
    fun `updateScenarioWithBadRequestPattern should prefer explicit bad request status over default`() {
        val badRequestOrDefault = BadRequestOrDefault(
            badRequestResponses = mapOf(401 to scenarioWithStatus(401), 400 to scenarioWithStatus(400)),
            defaultResponse = scenarioWithStatus(DEFAULT_RESPONSE_CODE)
        )

        val scenario = Scenario(
            name = "success",
            specType = SpecType.OPENAPI,
            protocol = SpecmaticProtocol.HTTP,
            httpRequestPattern = HttpRequestPattern(method = "GET"),
            httpResponsePattern = HttpResponsePattern(status = 200),
        )

        val updated = badRequestOrDefault.updateScenarioWithBadRequestPattern(scenario)
        assertThat(updated.httpResponsePattern.status).isEqualTo(400)
        assertThat(updated.statusInDescription).isEqualTo("400")
    }

    @Test
    fun `updateScenarioWithBadRequestPattern should use default response status when no explicit bad request exists`() {
        val badRequestOrDefault = BadRequestOrDefault(badRequestResponses = emptyMap(), defaultResponse = scenarioWithStatus(DEFAULT_RESPONSE_CODE))
        val scenario = Scenario(
            name = "success",
            specType = SpecType.OPENAPI,
            protocol = SpecmaticProtocol.HTTP,
            httpRequestPattern = HttpRequestPattern(method = "GET"),
            httpResponsePattern = HttpResponsePattern(status = 200),
        )

        val updated = badRequestOrDefault.updateScenarioWithBadRequestPattern(scenario)
        assertThat(updated.httpResponsePattern.status).isEqualTo(DEFAULT_RESPONSE_CODE)
        assertThat(updated.statusInDescription).isEqualTo(DEFAULT_RESPONSE_CODE.toString())
    }

    private fun scenarioWithStatus(status: Int): Scenario {
        return Scenario(
            name = "scenario-$status",
            httpRequestPattern = HttpRequestPattern(method = "GET"),
            httpResponsePattern = HttpResponsePattern(status = status),
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )
    }
}
