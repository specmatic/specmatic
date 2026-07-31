package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.Row
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class MultiPartNegativeGenerationTest {
    @Test
    fun `multipart string part does not produce an effective negative test`() {
        val feature = featureWithRequestBody(
            """
            multipart/form-data:
              schema:
                type: object
                required: [data]
                properties:
                  data:
                    type: string
            """.trimIndent(),
        )
        val positiveScenario = feature.scenarios.single()
        val rawNegativeCandidates = positiveScenario.httpRequestPattern
            .negativeBasedOn(Row(), positiveScenario.resolver.copy(isNegative = true))
            .filterIsInstance<HasValue<*>>()
            .map { it.value as HttpRequestPattern }
            .toList()

        assertThat(rawNegativeCandidates).isNotEmpty
        assertThat(rawNegativeCandidates).allSatisfy { candidate ->
            val request = candidate.generate(positiveScenario.resolver)
            assertThat(positiveScenario.matches(request, positiveScenario.resolver).isSuccess()).isTrue()
        }
        assertThat(feature.negativeTestScenarios().toList()).isEmpty()
    }

    @Test
    fun `plain text string request body also does not produce an effective negative test`() {
        val feature = featureWithRequestBody(
            """
            text/plain:
              schema:
                type: string
            """.trimIndent(),
        )

        assertThat(feature.negativeTestScenarios().toList()).isEmpty()
    }

    private fun featureWithRequestBody(content: String): Feature {
        val specification =
            """
            openapi: 3.0.1
            info:
              title: Negative generation
              version: "1"
            paths:
              /data:
                post:
                  requestBody:
                    required: true
                    content:
                      REQUEST_CONTENT
                  responses:
                    "200":
                      description: OK
            """.trimIndent()
                .replace("          REQUEST_CONTENT", content.prependIndent("          "))

        return OpenApiSpecification.fromYAML(
            specification,
            "",
            specmaticConfig = SpecmaticConfigV1V2Common(
                test = TestConfiguration(
                    resiliencyTests = ResiliencyTestsConfig(ResiliencyTestSuite.all),
                ),
            ),
        ).toFeature()
    }
}
