package io.specmatic.core.report

import io.specmatic.core.SpecmaticConfig
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord

const val SPEC_NOT_FOUND = "SPEC_NOT_FOUND"

fun ctrfSpecConfigsFrom(
    specmaticConfig: SpecmaticConfig,
    testResultRecords: List<CtrfTestResultRecord>,
    serviceType: String = "HTTP",
    specType: String = "OPENAPI",
): List<CtrfSpecConfig> {
    val specConfigs = testResultRecords.map {
        val absoluteSpecPath = it.specification
        if(absoluteSpecPath == null)
            CtrfSpecConfig(serviceType, specType, SPEC_NOT_FOUND)
        else
            specmaticConfig.getCtrfSpecConfig(absoluteSpecPath, it.testType, serviceType, specType)
    }
    return specConfigs.distinct()
}




