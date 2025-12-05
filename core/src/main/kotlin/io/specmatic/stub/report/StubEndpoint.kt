package io.specmatic.stub.report

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.test.TestResultRecord

data class StubEndpoint(
    val path: String?, val method: String?, val responseCode: Int, val sourceProvider: String? = null,
    val sourceRepository: String? = null,
    val sourceRepositoryBranch: String? = null,
    val specification: String? = null,
    val serviceType: String? = null
) {
    fun isEqualTo(testResultRecord: TestResultRecord): Boolean {
        return convertPathParameterStyle(path.orEmpty()) == testResultRecord.path
                && method == testResultRecord.method
                && responseCode == testResultRecord.responseStatus
    }
}