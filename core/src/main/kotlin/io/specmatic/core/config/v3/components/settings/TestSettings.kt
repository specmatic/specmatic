package io.specmatic.core.config.v3.components.settings

import io.specmatic.core.config.ResiliencyTestSuite

data class TestSettings(
    val resiliencyTests: ResiliencyTestSuite? = null,
    val timeoutInMilliseconds: Long? = null,
    val strictMode: Boolean? = null,
    val parallelism: Int? = null,
    val maxTestRequestCombinations: Int? = null,
    val junitReportDir: String? = null
)
