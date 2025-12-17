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
    return testResultRecords.map {
        it.specification.orEmpty() to it.testType
    }.map { (absoluteSpecPath, testType) ->
        specmaticConfig.getCtrfSpecConfig(absoluteSpecPath, testType, serviceType, specType)
    }.filter { it.specification.isNotBlank() }
}




