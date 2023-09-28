package `in`.specmatic.stub.report

import kotlinx.serialization.Serializable

@Serializable
data class StubUsageReportOperation (
    val path: String?,
    val method: String?,
    val responseCode: Int,
    val count: Int
)