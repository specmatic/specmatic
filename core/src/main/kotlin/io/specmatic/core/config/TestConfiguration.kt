package io.specmatic.core.config

import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.openapi.IRunOptionSpecification
import io.specmatic.core.config.v3.components.runOptions.openapi.OpenApiTestConfig
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.settings.TestSettings

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
    val urlPathPrefix: String? = null,
    val filterName: String? = null,
    val filterNotName: String? = null,
    val overlayFilePath: String? = null,
    val junitReportDir: String? = null,
) {
    constructor(testSettings: TestSettings? = null, runOptions: IRunOptions? = null, runOptionSpecification: IRunOptionSpecification? = null, specificationDefinition: SpecificationDefinition? = null): this(
        resiliencyTests = testSettings?.resiliencyTests?.let { ResiliencyTestsConfig(enable = it) },
        strictMode = testSettings?.strictMode,
        lenientMode = testSettings?.lenientMode,
        parallelism = testSettings?.parallelism,
        maxTestCount = testSettings?.maxTestCount,
        junitReportDir = testSettings?.junitReportDir,
        timeoutInMilliseconds = testSettings?.timeoutInMilliseconds,
        allowExtensibleSchema = testSettings?.allowExtensibleSchema,
        validateResponseValues = testSettings?.validateResponseValues,
        maxTestRequestCombinations = testSettings?.maxTestRequestCombinations,
        filter = runOptionSpecification?.getFilter(),
        overlayFilePath = runOptionSpecification?.getOverlayFilePath(),
        baseUrl = (runOptions as? OpenApiTestConfig)?.baseUrl,
        swaggerUrl = (runOptions as? OpenApiTestConfig)?.swaggerUrl,
        actuatorUrl = (runOptions as? OpenApiTestConfig)?.actuatorUrl,
        urlPathPrefix = specificationDefinition?.getUrlPathPrefix(),
    )
}
