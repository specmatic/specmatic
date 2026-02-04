package io.specmatic.core.config


data class TestConfiguration(
    val resiliencyTests: ResiliencyTestsConfig? = null,
    val validateResponseValues: Boolean? = null,
    val allowExtensibleSchema: Boolean? = null,
    val timeoutInMilliseconds: Long? = null,
    val strictMode: Boolean? = null,
    val lenientMode: Boolean? = null,
    val parallelism: String? = null,
    val maxTestRequestCombinations: Int? = null,
    val maxTestCount: Int? = null,
    val testsDirectory: String? = null,
    val swaggerUrl: String? = null,
    val swaggerUIBaseURL: String? = null,
    val actuatorUrl: String? = null,
    val filter: String? = null,
    val baseUrl: String? = null,
    val filterName: String? = null,
    val filterNotName: String? = null,
    val overlayFilePath: String? = null,
    val junitReportDir: String? = null,
)
