package io.specmatic.core.filters

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Feature
import io.specmatic.core.Scenario
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.utilities.Decision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StatusFilterResiliencyTest {

    @Test
    fun `PATH and METHOD filter with resiliency all keeps example statuses and generated negatives`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders'",
            resiliencyAll = true,
            include429Example = true,
        )

        assertThat(tests.any { !it.isNegative && it.status == 200 }).isTrue()
        assertThat(tests.any { !it.isNegative && it.status == 429 }).isTrue()
        assertThat(tests.any { it.isNegative }).isTrue()
    }

    @Test
    fun `PATH and METHOD filter with resiliency off keeps only example status tests`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders'",
            resiliencyAll = false,
            include429Example = true,
        )

        assertThat(tests.map { it.status }.toSet()).isEqualTo(setOf(200, 429))
        assertThat(tests.none { it.isNegative }).isTrue()
    }

    @Test
    fun `STATUS 429 with resiliency all keeps only the declared 429 test`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='429'",
            resiliencyAll = true,
            include429Example = true,
        )

        assertThat(tests).isNotEmpty
        assertThat(tests).allMatch { !it.isNegative && it.status == 429 }
    }

    @Test
    fun `STATUS 429 with resiliency off keeps only the declared 429 test`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='429'",
            resiliencyAll = false,
            include429Example = true,
        )

        assertThat(tests).isNotEmpty
        assertThat(tests).allMatch { !it.isNegative && it.status == 429 }
    }

    @Test
    fun `STATUS 429 with only 200 examples yields no tests`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='429'",
            resiliencyAll = false,
            include429Example = false,
        )

        assertThat(tests).isEmpty()
    }

    private fun generateFilteredTests(
        filter: String,
        resiliencyAll: Boolean,
        include429Example: Boolean,
    ): List<Scenario> {
        val feature = openApiFeature(include429Example).let {
            if (resiliencyAll) it.enableGenerativeTesting() else it
        }
        val metadataFilter = ScenarioMetadataFilter.from(filter)
        val scenarioDecisions = ScenarioMetadataFilter.filterUsingDecisions(
            items = feature.scenarios.asSequence().map { Decision.execute(it) },
            scenarioMetadataFilter = metadataFilter,
            getSkipContext = { it },
        )
        val executeScenarios = scenarioDecisions.mapNotNull { (it as? Decision.Execute)?.value }.toList()
        val generationFeature = feature.copy(scenarios = executeScenarios)

        return generationFeature
            .generateContractTestScenariosWithDecision(
                originalScenarios = feature.scenarios,
                scenarios = scenarioDecisions,
            )
            .mapNotNull { decision ->
                if (decision !is Decision.Execute) return@mapNotNull null
                val returnValue = decision.value
                if (returnValue !is HasValue) return@mapNotNull null
                returnValue.value
            }
            .filter { scenario ->
                metadataFilter.isSatisfiedBy(scenario.toScenarioMetadata())
            }
            .toList()
    }

    private fun openApiFeature(include429Example: Boolean): Feature {
        val spec = if (include429Example) {
            """
            openapi: "3.0.1"
            info:
              title: Orders API
              version: "1"
            paths:
              /orders:
                post:
                  summary: Create order
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - name
                          properties:
                            name:
                              type: string
                        examples:
                          CREATE_ORDER:
                            value:
                              name: widget
                          RATE_LIMITED:
                            value:
                              name: widget
                  responses:
                    "200":
                      description: Created
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: string
                          examples:
                            CREATE_ORDER:
                              value:
                                id: "1"
                    "429":
                      description: Too Many Requests
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - message
                            properties:
                              message:
                                type: string
                          examples:
                            RATE_LIMITED:
                              value:
                                message: slow down
            """.trimIndent()
        } else {
            """
            openapi: "3.0.1"
            info:
              title: Orders API
              version: "1"
            paths:
              /orders:
                post:
                  summary: Create order
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - name
                          properties:
                            name:
                              type: string
                        examples:
                          CREATE_ORDER:
                            value:
                              name: widget
                  responses:
                    "200":
                      description: Created
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: string
                          examples:
                            CREATE_ORDER:
                              value:
                                id: "1"
            """.trimIndent()
        }

        return OpenApiSpecification.fromYAML(spec, "").toFeature()
    }
}
