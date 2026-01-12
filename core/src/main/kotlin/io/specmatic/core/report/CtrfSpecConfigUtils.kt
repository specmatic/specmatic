package io.specmatic.core.report

import io.specmatic.core.SpecmaticConfig
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord

fun ctrfSpecConfigsFrom(
    specmaticConfig: SpecmaticConfig,
    testResultRecords: List<CtrfTestResultRecord>
): List<CtrfSpecConfig> {
    val protocols = testResultRecords.flatMap { it.protocols() }
    val specConfigs = protocols.flatMap { protocol ->
        testResultRecords.mapNotNull {
            val absoluteSpecPath = it.specification
            when {
                absoluteSpecPath.isNullOrBlank() -> null
                else -> specmaticConfig.getCtrfSpecConfig(absoluteSpecPath, it.testType, protocol.key, it.specType.value)
            }
        }
    }
    return specConfigs.distinct()
}




