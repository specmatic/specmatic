package io.specmatic.stub.report

data class StubEndpoint(
    val name: String?,
    val path: String?,
    val method: String?,
    val responseCode: Int,
    val sourceProvider: String? = null,
    val sourceRepository: String? = null,
    val sourceRepositoryBranch: String? = null,
    val specification: String? = null,
    val serviceType: String? = null,
)
