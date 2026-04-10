package io.specmatic.core

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BadRequestOrDefaultTest {
    @Nested
    inner class Matches {
        @Test
        fun `should fail when no matching or fallback response exists`() {
            val badRequestOrDefault = BadRequestOrDefault()
            val result = badRequestOrDefault.matches(httpResponse(status = 400, contentType = "application/json"), Resolver())

            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat((result as Result.Failure).message)
                .isEqualTo("No matching or fallback response found for status 400.")
        }

        @Test
        fun `should return success without partial when matching same status scenario is found`() {
            val matchingScenario = scenario(status = 400, contentType = "application/json")
            val badRequestOrDefault = BadRequestOrDefault(badRequestResponses = mapOf(400 to listOf(matchingScenario)))
            val result = badRequestOrDefault.matches(httpResponse(status = 400, contentType = "application/json"), Resolver())

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).partialSuccessMessage).isNull()
        }

        @Test
        fun `should return partial success when default response matches`() {
            val defaultScenario = scenario(status = DEFAULT_RESPONSE_CODE, contentType = "application/json")
            val badRequestOrDefault = BadRequestOrDefault(defaultResponses = listOf(defaultScenario))
            val result = badRequestOrDefault.matches(httpResponse(status = 499, contentType = "application/json"), Resolver())

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).partialSuccessMessage)
                .isEqualTo("The response matched the default response, but the contract should declare a 499 response.")
        }

        @Test
        fun `should return failure when default scenario is selected but response validation fails`() {
            val defaultScenario = scenario(status = 404, contentType = "application/json")
            val badRequestOrDefault = BadRequestOrDefault(defaultResponses = listOf(defaultScenario))
            val result = badRequestOrDefault.matches(httpResponse(status = 499, contentType = "application/json"), Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `should fail with content type mismatch when first same status fallback is selected`() {
            val badRequestOrDefault = BadRequestOrDefault(
                badRequestResponses = mapOf(
                    400 to listOf(
                        scenario(status = 400, contentType = "text/plain", name = "first-same-status"),
                        scenario(status = 400, contentType = "application/xml", name = "second-same-status")
                    )
                ),
            )

            val result = badRequestOrDefault.matches(httpResponse(status = 400, contentType = "application/json"), Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat((result as Result.Failure).hasReason(FailureReason.ContentTypeMismatch)).isTrue()
        }

        @Test
        fun `should fail with status mismatch when other status content type match is selected`() {
            val badRequestOrDefault = BadRequestOrDefault(
                badRequestResponses = mapOf(
                    401 to listOf(scenario(status = 401, contentType = "application/json", name = "other-status-content-match"))
                ),
            )

            val result = badRequestOrDefault.matches(httpResponse(status = 400, contentType = "application/json"), Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat((result as Result.Failure).hasReason(FailureReason.StatusMismatch)).isTrue()
        }

        @Test
        fun `should fail with content type mismatch when first default fallback is selected`() {
            val badRequestOrDefault = BadRequestOrDefault(
                badRequestResponses = mapOf(401 to listOf(scenario(status = 401, contentType = "application/pdf", name = "other-status"))),
                defaultResponses = listOf(
                    scenario(status = DEFAULT_RESPONSE_CODE, contentType = "application/xml", name = "first-default"),
                    scenario(status = DEFAULT_RESPONSE_CODE, contentType = "text/plain", name = "second-default")
                )
            )

            val result = badRequestOrDefault.matches(httpResponse(status = 400, contentType = "application/json"), Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat((result as Result.Failure).hasReason(FailureReason.ContentTypeMismatch)).isTrue()
        }

        @Test
        fun `should fail with status mismatch when first other status fallback is selected`() {
            val badRequestOrDefault = BadRequestOrDefault(
                badRequestResponses = mapOf(
                    401 to listOf(scenario(status = 401, contentType = "application/xml", name = "first-other-status")),
                    402 to listOf(scenario(status = 402, contentType = "text/plain", name = "second-other-status"))
                ),
            )

            val result = badRequestOrDefault.matches(httpResponse(status = 400, contentType = "application/json"), Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat((result as Result.Failure).hasReason(FailureReason.StatusMismatch)).isTrue()
        }
    }

    @Nested
    inner class Supports {
        @Test
        fun `should return true when bad request responses contain the given status`() {
            val badRequestOrDefault = BadRequestOrDefault(badRequestResponses = mapOf(400 to listOf(scenario(status = 400, contentType = "application/json"))))
            assertThat(badRequestOrDefault.supports(400)).isTrue()
        }

        @Test
        fun `should return true when default responses exist even if status is absent`() {
            val badRequestOrDefault = BadRequestOrDefault(defaultResponses = listOf(scenario(status = DEFAULT_RESPONSE_CODE, contentType = "application/json")))
            assertThat(badRequestOrDefault.supports(418)).isTrue()
        }

        @Test
        fun `should return false when status is absent and no default responses exist`() {
            val badRequestOrDefault = BadRequestOrDefault()
            assertThat(badRequestOrDefault.supports(400)).isFalse()
        }
    }

    private fun scenario(status: Int, contentType: String, name: String = "scenario-$status") =
        Scenario(
            name = name,
            specType = SpecType.OPENAPI,
            protocol = SpecmaticProtocol.HTTP,
            httpRequestPattern = HttpRequestPattern(),
            httpResponsePattern = HttpResponsePattern(status = status, headersPattern = HttpHeadersPattern(contentType = contentType)),
        )

    private fun httpResponse(status: Int, contentType: String): HttpResponse {
        return HttpResponse(status = status, headers = mapOf("Content-Type" to contentType))
    }
}
