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
            fixture = SpecFixture.DECLARED_429,
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
            fixture = SpecFixture.DECLARED_429,
        )

        assertThat(tests.map { it.status }.toSet()).isEqualTo(setOf(200, 429))
        assertThat(tests.none { it.isNegative }).isTrue()
    }

    @Test
    fun `STATUS 429 with resiliency all keeps only the declared 429 test`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='429'",
            resiliencyAll = true,
            fixture = SpecFixture.DECLARED_429,
        )

        assertThat(tests).isNotEmpty
        assertThat(tests).allMatch { !it.isNegative && it.status == 429 }
    }

    @Test
    fun `STATUS 429 with resiliency off keeps only the declared 429 test`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='429'",
            resiliencyAll = false,
            fixture = SpecFixture.DECLARED_429,
        )

        assertThat(tests).isNotEmpty
        assertThat(tests).allMatch { !it.isNegative && it.status == 429 }
    }

    @Test
    fun `STATUS 429 with only 200 examples yields no tests`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='429'",
            resiliencyAll = false,
            fixture = SpecFixture.TWO_HUNDRED_ONLY,
        )

        assertThat(tests).isEmpty()
    }

    @Test
    fun `STATUS 429 with 200 and default and resiliency off yields no tests`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='429'",
            resiliencyAll = false,
            fixture = SpecFixture.TWO_HUNDRED_AND_DEFAULT,
        )

        assertThat(tests).isEmpty()
    }

    @Test
    fun `STATUS 429 with 200 and default and resiliency all yields no tests`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='429'",
            resiliencyAll = true,
            fixture = SpecFixture.TWO_HUNDRED_AND_DEFAULT,
        )

        assertThat(tests).isEmpty()
    }

    @Test
    fun `STATUS 4xx with resiliency all keeps generated negatives and declared 4xx tests`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='4xx'",
            resiliencyAll = true,
            fixture = SpecFixture.DECLARED_429,
        )

        assertThat(tests).isNotEmpty
        assertThat(tests.any { it.isNegative }).isTrue()
        assertThat(tests.any { !it.isNegative && it.status == 429 }).isTrue()
        assertThat(tests.none { !it.isNegative && it.status == 200 }).isTrue()
    }

    @Test
    fun `resiliency all on 2xx-only operation still generates negatives with null bad request expectation`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders'",
            resiliencyAll = true,
            fixture = SpecFixture.TWO_HUNDRED_ONLY,
        )

        assertThat(tests.any { !it.isNegative && it.status == 200 }).isTrue()
        assertThat(tests.any { it.isNegative }).isTrue()
    }

    @Test
    fun `STATUS not equal 429 with 200 and default and resiliency all keeps 200 example and negatives`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS!='429'",
            resiliencyAll = true,
            fixture = SpecFixture.TWO_HUNDRED_AND_DEFAULT,
        )

        assertThat(tests.map { it.status to it.isNegative }.toSet()).isEqualTo(
            setOf(200 to false, 400 to true)
        )
    }

    @Test
    fun `STATUS 2xx with resiliency all keeps only the 200 example`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='2xx'",
            resiliencyAll = true,
            fixture = SpecFixture.DECLARED_429,
        )

        assertThat(tests.map { it.status to it.isNegative }.toSet()).isEqualTo(
            setOf(200 to false)
        )
    }

    @Test
    fun `STATUS 4xx with 200 and default and resiliency all yields no tests`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='4xx'",
            resiliencyAll = true,
            fixture = SpecFixture.TWO_HUNDRED_AND_DEFAULT,
        )

        assertThat(tests).isEmpty()
    }

    @Test
    fun `STATUS 4xx with resiliency off keeps only the declared 429 example`() {
        val tests = generateFilteredTests(
            filter = "METHOD='POST' && PATH='/orders' && STATUS='4xx'",
            resiliencyAll = false,
            fixture = SpecFixture.DECLARED_429,
        )

        assertThat(tests.map { it.status to it.isNegative }.toSet()).isEqualTo(
            setOf(429 to false)
        )
    }

    private enum class SpecFixture {
        DECLARED_429,
        TWO_HUNDRED_ONLY,
        TWO_HUNDRED_AND_DEFAULT,
    }

    private fun generateFilteredTests(
        filter: String,
        resiliencyAll: Boolean,
        fixture: SpecFixture,
    ): List<Scenario> {
        val feature = openApiFeature(fixture).let {
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

    private fun openApiFeature(fixture: SpecFixture): Feature {
        val spec = when (fixture) {
            SpecFixture.DECLARED_429 -> """
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
            SpecFixture.TWO_HUNDRED_ONLY -> """
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
            SpecFixture.TWO_HUNDRED_AND_DEFAULT -> """
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
                    default:
                      description: Error
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - message
                            properties:
                              message:
                                type: string
            """.trimIndent()
        }

        return OpenApiSpecification.fromYAML(spec, "").toFeature()
    }
}
