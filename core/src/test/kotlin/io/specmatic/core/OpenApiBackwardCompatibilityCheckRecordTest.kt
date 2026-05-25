package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.reporter.ctrf.model.CtrfOperationQualifiers
import io.specmatic.core.ChangeStatus
import io.specmatic.reporter.model.BackwardCompatibilityResult
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.openAPIOperationFrom
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiBackwardCompatibilityCheckRecordTest {
    @Test
    fun `should derive record vals from openapi scenario and feature `() {
        val feature = openApiFeature()
        val scenario = feature.scenarios.single().copy(
            sourceRepositoryBranch = "main",
            specification = "specs/orders.yaml",
            sourceRepository = "https://github.com/example/orders.git",
        )

        val record = OpenApiBackwardCompatibilityCheckRecord(
            feature = feature.copy(scenarios = listOf(scenario)),
            compatResult = Result.Success(),
            scenario = scenario,
        )

        assertThat(record.id).isNotNull()
        assertThat(record.message).isEmpty()
        assertThat(record.operationQualifiers).containsExactly(CtrfOperationQualifiers.CHANGED)
        assertThat(record.duration).isEqualTo(0)
        assertThat(record.branch).isEqualTo("main")
        assertThat(record.specType).isEqualTo(SpecType.OPENAPI)
        assertThat(record.tags).doesNotContain("path:${scenario.path}")
        assertThat(record.name).isEqualTo(scenario.fullApiDescription)
        assertThat(record.changeStatus).isEqualTo(ChangeStatus.CHANGED)
        assertThat(record.specification).isEqualTo("specs/orders.yaml")
        assertThat(record.result).isEqualTo(BackwardCompatibilityResult.Compatible)
        assertThat(record.repository).isEqualTo("https://github.com/example/orders.git")
        assertThat(record.tags).containsExactly(
            "status:200",
            "method:get",
            "path:${convertPathParameterStyle(scenario.path)}",
            "content-type:application/json",
            "response-content-type:application/json",
        )

        assertThat(record.operations).containsExactly(
            openAPIOperationFrom(scenario, convertPathParameterStyle(scenario.path))
        )
    }

    @Test
    fun `should fallback to feature path when scenario does not have specification defined`() {
        val feature = openApiFeature()
        val scenario = feature.scenarios.single().copy(specification = null)
        val record = OpenApiBackwardCompatibilityCheckRecord(
            scenario = scenario,
            compatResult = Result.Success(),
            feature = feature.copy(scenarios = listOf(scenario)),
        )

        assertThat(record.specification).isEqualTo(feature.path)
    }

    @Test
    fun `should mark ignored failure scenarios as wip`() {
        val feature = openApiFeature()
        val scenario = feature.scenarios.single().copy(ignoreFailure = true)

        val failure = Result.Failure("breaking change")
        val record = OpenApiBackwardCompatibilityCheckRecord(
            scenario = scenario,
            compatResult = failure,
            feature = feature.copy(scenarios = listOf(scenario)),
        )

        assertThat(record.message).contains("breaking change")
        assertThat(record.result).isEqualTo(BackwardCompatibilityResult.Incompatible)
        assertThat(record.operationQualifiers).containsExactly(CtrfOperationQualifiers.WIP, CtrfOperationQualifiers.CHANGED)
    }

    private fun openApiFeature(): Feature {
        return OpenApiSpecification.fromYAML("""
        openapi: 3.0.1
        info:
          title: Orders API
          version: 1.0.0
        paths:
          /orders/{orderId}:
            get:
              parameters:
                - in: path
                  name: orderId
                  required: true
                  schema:
                    type: integer
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required: [traceId]
                      properties:
                        traceId:
                          type: string
              responses:
                '200':
                  description: order found
                  content:
                    application/json:
                      schema:
                        type: object
                        required: [id]
                        properties:
                          id:
                            type: integer
        """.trimIndent(), "orders.yaml",).toFeature()
    }
}
