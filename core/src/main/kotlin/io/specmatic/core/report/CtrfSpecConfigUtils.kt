package io.specmatic.core.report

import io.specmatic.core.SpecmaticConfig
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord

const val SPEC_TYPE_OPENAPI = "openapi"
const val HTTP_PROTOCOL = "http"

fun ctrfSpecConfigsFrom(
    specmaticConfig: SpecmaticConfig,
    testResultRecords: List<CtrfTestResultRecord>,
    protocol: String = HTTP_PROTOCOL,
    specType: String = SPEC_TYPE_OPENAPI,
): List<CtrfSpecConfig> {
    val specConfigs = testResultRecords.mapNotNull {
        val absoluteSpecPath = it.specification
        when {
            absoluteSpecPath.isNullOrBlank() -> null
            else -> specmaticConfig.getCtrfSpecConfig(absoluteSpecPath, it.testType, protocol, specType)
        }
    }
    return specConfigs.distinct()
}




