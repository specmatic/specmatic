package io.specmatic.core.report

import io.specmatic.core.SpecmaticConfig
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord

fun ctrfSpecConfigsFrom(
    specmaticConfig: SpecmaticConfig,
    testResultRecords: List<CtrfTestResultRecord>,
    protocol: String = "http",
    specType: String = "openapi",
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




