package io.specmatic.core

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.reporter.ctrf.model.CtrfBackwardCompatibilityRecord
import io.specmatic.reporter.ctrf.model.CtrfOperationQualifiers
import io.specmatic.reporter.internal.dto.operation.APIOperation
import io.specmatic.reporter.model.BackwardCompatibilityStatus
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.openAPIOperationFrom
import java.util.UUID

// Which side of an operation a backward-compatibility check covers. A 5-tuple yields one check per
// phase; the phase distinguishes the otherwise identically-named request and response tests.
enum class BackwardCompatibilityCheckPhase(val label: String) {
    REQUEST("request"),
    RESPONSE("response");

    val tag: String get() = "compatibility:$label"
}

data class OpenApiBackwardCompatibilityCheckRecord(
    val feature: Feature,
    val scenario: Scenario,
    val compatResult: Result,
    override val duration: Long = 0,
    override val id: UUID = UUID.randomUUID(),
    val changeStatus: ChangeStatus = ChangeStatus.CHANGED,
    val requestVariationSummary: String? = null,
    val phase: BackwardCompatibilityCheckPhase = BackwardCompatibilityCheckPhase.REQUEST,
) : CtrfBackwardCompatibilityRecord {
    override val specType: SpecType = scenario.specType
    override val repository: String? = scenario.sourceRepository
    override val branch: String? = scenario.sourceRepositoryBranch
    override val specification: String = scenario.specification ?: feature.path

    override val isWip: Boolean = scenario.ignoreFailure

    // Uses fullApiTestDescription (not the plain testDescription that contract-test/mock CTRF use) so
    // the content-type appears in the name: operations with multiple request/response media types on
    // the same method/path/status are otherwise indistinguishable, since their 5-tuples differ only by
    // content-type. The variation summary is applied via a local copy (the stored scenario's identity
    // must be preserved for response-compatibility dedup). generativePrefix is empty for BCC (all
    // scenarios are positive), so the description yields a leading-space " Scenario: ..."; trim drops
    // that slot. The "(request)"/"(response)" suffix distinguishes the two compatibility checks of a
    // 5-tuple, which otherwise share a name (most visibly for GETs with no request body).
    override val name: String =
        "${scenario.copy(requestChangeSummary = requestVariationSummary).fullApiTestDescription().trim()} (${phase.label})"
    override val message: String = compatResult.reportString()
    override val operations: Set<APIOperation> = toOpenApiOperation(scenario)
    override val tags: List<String> = buildList {
        if (isWip) add("wip")
        add("status:${scenario.status}")
        add("method:${scenario.method.lowercase()}")
        add("path:${convertPathParameterStyle(scenario.path)}")
        scenario.requestContentType?.let { contentType -> add("content-type:$contentType") }
        scenario.responseContentType?.let { contentType -> add("response-content-type:$contentType") }
        add(phase.tag)
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
