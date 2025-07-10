package io.specmatic.stub.report

data class MockInteractionData(
    val name: String,
    val baseUrl: String,
    val duration: Long,
    val valid: Boolean,
    val request: String,
    val requestTime: Long,
    val response: String,
    val responseTime: Long,
    val specFileName: String,
    val details: String = "",
    val stubType: String,
    val method: String,
    val path: String,
    val responseCode: Int,
    val responseSource: String = "",
    val stubToken: String? = null,
    val delayInMilliseconds: Long = 0,
    val sourceProvider: String = "",
    val sourceRepository: String = "",
    val sourceRepositoryBranch: String = "",
    val serviceType: String = ""
    // Note: wip (Work In Progress) field is not included here as it's specific to test context
    // and not part of stub/mock interaction data
)