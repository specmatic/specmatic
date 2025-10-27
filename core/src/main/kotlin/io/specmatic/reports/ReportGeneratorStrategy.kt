package io.specmatic.reports

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.SourceProvider
import io.specmatic.stub.report.StubEndpoint
import io.specmatic.test.TestResultRecord

interface ReportGeneratorStrategy<TInput> {
    fun toMetadata(data: TInput): ReportMetadata

    fun toOperationGroup(data: TInput): OperationGroup
}

class OpenApiStubUsageStrategy : ReportGeneratorStrategy<StubEndpoint> {
    override fun toMetadata(data: StubEndpoint) =
        ReportMetadata(
            sourceProvider = data.sourceProvider?.let(SourceProvider::fromString),
            sourceRepository = data.sourceRepository,
            sourceRepositoryBranch = data.sourceRepositoryBranch,
            specification = data.specification,
            serviceType = ServiceType.HTTP,
        )

    override fun toOperationGroup(data: StubEndpoint) =
        OpenApiOperationGroup(
            path = convertPathParameterStyle(data.path.orEmpty()),
            method = data.method.orEmpty(),
            responseCode = data.responseCode,
            count = 0,
        )
}

class OpenApiTestCoverageStrategy : ReportGeneratorStrategy<TestResultRecord> {
    override fun toMetadata(data: TestResultRecord) =
        ReportMetadata(
            sourceProvider = data.sourceProvider?.let(SourceProvider::fromString),
            sourceRepository = data.sourceRepository,
            sourceRepositoryBranch = data.sourceRepositoryBranch,
            specification = data.specification,
            serviceType = ServiceType.HTTP,
        )

    override fun toOperationGroup(data: TestResultRecord): OperationGroup =
        OpenApiOperationGroup(
            path = convertPathParameterStyle(data.path),
            method = data.method,
            responseCode = data.responseStatus,
            count = 0,
        )
}
