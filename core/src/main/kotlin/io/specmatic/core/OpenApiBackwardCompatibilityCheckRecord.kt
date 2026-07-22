package io.specmatic.core

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.reporter.ctrf.model.CtrfBackwardCompatibilityRecord
import io.specmatic.reporter.ctrf.model.CtrfBreakingChange
import io.specmatic.reporter.ctrf.model.CtrfOperationQualifiers
import io.specmatic.reporter.ctrf.model.CtrfRuleSnapshot
import io.specmatic.reporter.ctrf.model.CtrfSeverity
import io.specmatic.reporter.ctrf.model.CtrfSourceLocation
import io.specmatic.reporter.internal.dto.operation.APIOperation
import io.specmatic.reporter.model.BackwardCompatibilityStatus
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.openAPIOperationFrom
import java.util.UUID

data class OpenApiBackwardCompatibilityCheckRecord(
    val feature: Feature,
    val scenario: Scenario,
    val compatResult: Result,
    override val duration: Long = 0,
    override val id: UUID = UUID.randomUUID(),
    val changeStatus: ChangeStatus = ChangeStatus.CHANGED,
    private val recordName: String? = null,
) : CtrfBackwardCompatibilityRecord {
    override val specType: SpecType = scenario.specType
    override val repository: String? = scenario.sourceRepository
    override val branch: String? = scenario.sourceRepositoryBranch
    override val specification: String = scenario.specification ?: feature.path

    override val isWip: Boolean = scenario.ignoreFailure

    // TODO: Need actual positive variation from generatedScenario
    override val name: String = recordName ?: scenario.fullApiDescription
    override val message: String = compatResult.reportString(addSourceLocation = true)
    override val breakingChanges: List<CtrfBreakingChange> = compatResult.toIssues().flatMap { issue ->
        val sourceLocations = issue.sourceLocations.map { CtrfSourceLocation(it.filePath, it.line, it.column) }
        val rules: List<CtrfRuleSnapshot?> = issue.ruleViolations.map {
            CtrfRuleSnapshot(it.id, it.title, it.documentationUrl, it.summary)
        }.ifEmpty { listOf(null) }
        rules.map { rule ->
            CtrfBreakingChange(
                breadcrumb = issue.breadCrumb,
                sourceLocations = sourceLocations,
                rule = rule,
                description = issue.details,
                severity = when (issue.severity) {
                    IssueSeverity.ERROR -> CtrfSeverity.ERROR
                    IssueSeverity.WARNING -> CtrfSeverity.WARNING
                },
            )
        }
    }
    override val operations: Set<APIOperation> = toOpenApiOperation(scenario)
    override val tags: List<String> = buildList {
        if (isWip) add("wip")
        add("status:${scenario.status}")
        add("method:${scenario.method.lowercase()}")
        add("path:${convertPathParameterStyle(scenario.path)}")
        scenario.requestContentType?.let { contentType -> add("content-type:$contentType") }
        scenario.responseContentType?.let { contentType -> add("response-content-type:$contentType") }
    }

    override val result: BackwardCompatibilityStatus = when (compatResult) {
        is Result.Success -> BackwardCompatibilityStatus.Compatible
        is Result.Failure -> BackwardCompatibilityStatus.Incompatible
    }

    override val operationQualifiers: List<CtrfOperationQualifiers> = buildList {
        if (isWip) add(CtrfOperationQualifiers.WIP)
        if (changeStatus == ChangeStatus.CHANGED) add(CtrfOperationQualifiers.CHANGED)
    }

    private fun toOpenApiOperation(scenario: Scenario): Set<APIOperation> = setOf(
        element = openAPIOperationFrom(scenario, convertPathParameterStyle(scenario.path))
    )
}
