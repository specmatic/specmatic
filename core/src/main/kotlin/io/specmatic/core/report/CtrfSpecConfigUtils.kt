package io.specmatic.core.report

import io.specmatic.core.SpecmaticConfig
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord

fun ctrfSpecConfigsFrom(
    specmaticConfig: SpecmaticConfig,
    testResultRecords: List<CtrfTestResultRecord>,
    serviceType: String = "HTTP",
    specType: String = "OPENAPI",
): List<CtrfSpecConfig> {
    val specConfigs = testResultRecords.mapNotNull {
        when (val absoluteSpecPath = it.specification) {
            null -> null
            else -> specmaticConfig.getCtrfSpecConfig(absoluteSpecPath, it.testType, serviceType, specType)
        }
    }
    return specConfigs.distinct()
}




