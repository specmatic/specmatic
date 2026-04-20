package io.specmatic.stub.report

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.report.SpecEndpoint
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.TestResultRecord

data class StubEndpoint(
    val path: String?,
    val method: String?,
    val responseCode: Int,
    val requestContentType: String? = null,
    val responseContentType: String? = null,
    val sourceProvider: String? = null,
    val sourceRepository: String? = null,
    val sourceRepositoryBranch: String? = null,
    override val specification: String? = null,
    val protocol: SpecmaticProtocol,
    val specType: SpecType
) : SpecEndpoint {
    override fun toOpenApiOperation(): OpenAPIOperation {
        return OpenAPIOperation(
            path = convertPathParameterStyle(path.orEmpty()),
            method = method.orEmpty(),
            contentType = requestContentType,
            responseCode = responseCode,
            protocol = protocol,
            responseContentType = responseContentType,
        )
    }

    override fun toCtrfSpecConfig(): CtrfSpecConfig {
        return CtrfSpecConfig(
            protocol = protocol.key,
            specType = specType.value,
            specification = specification.orEmpty(),
            sourceProvider = sourceProvider,
            repository = sourceRepository,
            branch = sourceRepositoryBranch ?: "main",
        )
    }

    fun isEqualTo(testResultRecord: TestResultRecord): Boolean {
        return convertPathParameterStyle(path.orEmpty()) == testResultRecord.path
                && method == testResultRecord.method
                && responseCode == testResultRecord.responseStatus
    }
}
