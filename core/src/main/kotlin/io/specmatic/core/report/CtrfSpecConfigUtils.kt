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
    val specConfigs = testResultRecords.map {
        it.specification.orEmpty() to it.testType
    }.map { (specification, testType) ->
        specmaticConfig.getCtrfSpecConfig(specification, testType, serviceType, specType)
    }
    return specConfigs
}




