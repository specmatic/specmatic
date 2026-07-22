package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.reporter.ctrf.model.CtrfOperationQualifiers
import io.specmatic.reporter.ctrf.model.CtrfSeverity
import io.specmatic.reporter.ctrf.model.CtrfSourceLocation
import io.specmatic.core.ChangeStatus
import io.specmatic.reporter.model.BackwardCompatibilityStatus
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
        assertThat(record.result).isEqualTo(BackwardCompatibilityStatus.Compatible)
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
    fun `should use externalised example name when provided`() {
        val feature = openApiFeature()
        val scenario = feature.scenarios.single()

        val record = OpenApiBackwardCompatibilityCheckRecord(
            feature = feature,
            scenario = scenario,
            compatResult = Result.Success(),
            recordName = "get-orders_200.json"
        )

        assertThat(record.name).isEqualTo("get-orders_200.json")
    }

    @Test
    fun `should expose structured breakages with breadcrumb, rule and source-location chain`() {
        val feature = openApiFeature()
        val scenario = feature.scenarios.single()

        // The actual source of the break lives in common.yaml (tail); api.yaml is the $ref use-site (via head).
        val location = SourceLocation(
            filePath = "/specs/common.yaml", line = 38, column = 9, pointer = "",
            via = listOf(SourceLocation(filePath = "/specs/api.yaml", line = 27, column = 13, pointer = ""))
        )

        val failure = Result.Failure(
            message = "This is type number in the new specification, but type string in the old specification",
            ruleViolation = StandardRuleViolation.TYPE_MISMATCH
        ).breadCrumb("applicationNumber", location).breadCrumb("BODY").breadCrumb("REQUEST")

        val record = OpenApiBackwardCompatibilityCheckRecord(
            scenario = scenario,
            compatResult = failure,
            feature = feature.copy(scenarios = listOf(scenario)),
        )

        assertThat(record.breakingChanges).hasSize(1)
        val breakage = record.breakingChanges.single()
        assertThat(breakage.breadcrumb).isEqualTo("REQUEST.BODY.applicationNumber")
        assertThat(breakage.rule?.id).isEqualTo("R1001")
        assertThat(breakage.description).contains("type number in the new specification")
        assertThat(breakage.severity).isEqualTo(CtrfSeverity.ERROR)
        assertThat(breakage.sourceLocations).containsExactly(
            CtrfSourceLocation("/specs/api.yaml", 27, 13),
            CtrfSourceLocation("/specs/common.yaml", 38, 9),
        )
    }

    @Test
    fun `should expose one breakage per rule violation on an issue`() {
        val feature = openApiFeature()
        val scenario = feature.scenarios.single()

        val failure = Result.Failure(
            message = "type mismatch on applicationNumber",
            ruleViolation = StandardRuleViolation.TYPE_MISMATCH
        ).withRuleViolation(StandardRuleViolation.VALUE_MISMATCH)
            .breadCrumb("applicationNumber").breadCrumb("BODY").breadCrumb("REQUEST")

        val record = OpenApiBackwardCompatibilityCheckRecord(
            scenario = scenario,
            compatResult = failure,
            feature = feature.copy(scenarios = listOf(scenario)),
        )

        assertThat(record.breakingChanges).hasSize(2)
        assertThat(record.breakingChanges.map { it.rule?.id })
            .containsExactlyInAnyOrder(
                StandardRuleViolation.TYPE_MISMATCH.id,
                StandardRuleViolation.VALUE_MISMATCH.id,
            )
        assertThat(record.breakingChanges.map { it.breadcrumb }.distinct())
            .containsExactly("REQUEST.BODY.applicationNumber")
    }

    @Test
    fun `should expose a breakage even when the issue has no rule violation`() {
        val feature = openApiFeature()
        val scenario = feature.scenarios.single()

        val failure = Result.Failure(message = "breaking change").breadCrumb("applicationNumber")

        val record = OpenApiBackwardCompatibilityCheckRecord(
            scenario = scenario,
            compatResult = failure,
            feature = feature.copy(scenarios = listOf(scenario)),
        )

        assertThat(record.breakingChanges).hasSize(1)
        assertThat(record.breakingChanges.single().rule).isNull()
    }

    @Test
    fun `should expose no breakages for a compatible scenario`() {
        val feature = openApiFeature()
        val scenario = feature.scenarios.single()

        val record = OpenApiBackwardCompatibilityCheckRecord(
            scenario = scenario,
            compatResult = Result.Success(),
            feature = feature.copy(scenarios = listOf(scenario)),
        )

        assertThat(record.breakingChanges).isEmpty()
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
        assertThat(record.result).isEqualTo(BackwardCompatibilityStatus.Incompatible)
        assertThat(record.operationQualifiers).containsExactly(CtrfOperationQualifiers.WIP, CtrfOperationQualifiers.CHANGED)
        assertThat(record.isWip).isTrue()
        assertThat(record.name).isEqualTo(scenario.fullApiDescription)
        assertThat(record.tags).contains("wip")
    }

    @Test
    fun `should not mark non-wip scenarios as wip`() {
        val feature = openApiFeature()
        val scenario = feature.scenarios.single()

        val record = OpenApiBackwardCompatibilityCheckRecord(
            scenario = scenario,
            compatResult = Result.Success(),
            feature = feature.copy(scenarios = listOf(scenario)),
        )

        assertThat(record.isWip).isFalse()
        assertThat(record.name).isEqualTo(scenario.fullApiDescription)
        assertThat(record.tags).doesNotContain("wip")
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
