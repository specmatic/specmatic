package io.specmatic.stub.report

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.reporter.generated.dto.stub.usage.HTTPStubUsageOperation
import io.specmatic.reporter.generated.dto.stub.usage.SpecmaticStubUsageReport
import io.specmatic.reporter.generated.dto.stub.usage.StubUsageEntry
import io.specmatic.reporter.internal.dto.stub.usage.StubUsageOperation

class StubUsageReport(
    private val configFilePath: String,
    private val allEndpoints: List<StubEndpoint> = mutableListOf(),
    private val stubLogs: List<StubEndpoint> = mutableListOf()
) {
    fun generate(): SpecmaticStubUsageReport {
        val stubUsageJsonRows = allEndpoints.groupBy {
            StubUsageReportGroupKey(
                it.sourceProvider,
                it.sourceRepository,
                it.sourceRepositoryBranch,
                it.specification,
                it.serviceType
            )
        }.map { (key, recordsOfGroup) ->
            val operations = recordsOfGroup.groupBy {
                Triple(it.path, it.method, it.responseCode)
            }.map { (operationGroup, _) ->
                HTTPStubUsageOperation().withPath(
                    operationGroup.first?.let { convertPathParameterStyle(it) }.orEmpty()
                ).withMethod(operationGroup.second.orEmpty())
                    .withResponseCode(operationGroup.third)
                    .withCount(stubLogs.count {
                        it.path == operationGroup.first
                                && it.method == operationGroup.second
                                && it.responseCode == operationGroup.third
                                && it.sourceProvider == key.sourceProvider
                                && it.sourceRepository == key.sourceRepository
                                && it.sourceRepositoryBranch == key.sourceRepositoryBranch
                                && it.specification == key.specification
                                && it.serviceType == key.serviceType
                    })
            }
            StubUsageEntry()
                .withType(key.sourceProvider)
                .withRepository(key.sourceRepository)
                .withSpecification(key.specification)
                .withBranch(key.sourceRepositoryBranch)
                .withServiceType(key.serviceType)
                .withSpecType("OPENAPI")
                .withOperations(operations)
        }
        return SpecmaticStubUsageReport()
            .withSpecmaticConfigPath(configFilePath)
            .withStubUsage(stubUsageJsonRows)
    }
}

data class StubUsageReportGroupKey(
    val sourceProvider: String?,
    val sourceRepository: String?,
    val sourceRepositoryBranch: String?,
    val specification: String?,
    val serviceType: String?
)