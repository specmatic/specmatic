package io.specmatic.stub.report

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.reporter.internal.dto.stub.usage.HTTPStubUsageOperation
import io.specmatic.reporter.internal.dto.stub.usage.SpecmaticStubUsageReport
import io.specmatic.reporter.internal.dto.stub.usage.StubUsageEntry

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
            StubUsageEntry(
                _type = key.sourceProvider,
                _repository = key.sourceRepository,
                _branch = key.sourceRepositoryBranch,
                specification = key.specification.orEmpty(),
                _serviceType = key.serviceType,
                _operations = recordsOfGroup.groupBy {
                    Triple(it.path, it.method, it.responseCode)
                }.map { (operationGroup, _) ->
                    HTTPStubUsageOperation(
                        path = operationGroup.first?.let { convertPathParameterStyle(it) }.orEmpty(),
                        method = operationGroup.second.orEmpty(),
                        responseCode = operationGroup.third,
                        count = stubLogs.count {
                            it.path == operationGroup.first
                                    && it.method == operationGroup.second
                                    && it.responseCode == operationGroup.third
                                    && it.sourceProvider == key.sourceProvider
                                    && it.sourceRepository == key.sourceRepository
                                    && it.sourceRepositoryBranch == key.sourceRepositoryBranch
                                    && it.specification == key.specification
                                    && it.serviceType == key.serviceType
                        }
                    )
                }
            )
        }
        return SpecmaticStubUsageReport(configFilePath, stubUsageJsonRows)
    }
}

data class StubUsageReportGroupKey(
    val sourceProvider: String?,
    val sourceRepository: String?,
    val sourceRepositoryBranch: String?,
    val specification: String?,
    val serviceType: String?
)