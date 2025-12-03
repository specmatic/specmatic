package io.specmatic.core.report

import io.specmatic.core.Source
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.v3.SpecExecutionConfig
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfTestResultRecord
import io.specmatic.test.TestResultRecord.Companion.CONTRACT_TEST_TEST_TYPE
import java.io.File

fun ctrfSpecConfigsFrom(
    specmaticConfig: SpecmaticConfig,
    testResultRecords: List<CtrfTestResultRecord>,
    serviceType: String = "HTTP",
    specType: String = "OPENAPI",
): List<CtrfSpecConfig> {
    val specConfigs = testResultRecords.map {
        it.specification.orEmpty() to it.testType
    }.map { (specification, testType) ->
        val source = associatedSource(specification, specmaticConfig, testType)
        CtrfSpecConfig(
            serviceType = serviceType,
            specType = specType,
            specification = specification,
            sourceProvider = source.provider.name,
            repository = source.repository.orEmpty(),
            branch = source.branch ?: "main",
        )
    }
    return specConfigs
}

private fun associatedSource(specification: String, specmaticConfig: SpecmaticConfig, testType: String): Source {
    val specName = File(specification).name
    val source = when (testType) {
        CONTRACT_TEST_TEST_TYPE -> testSourceFromConfig(specName, specmaticConfig)
        else -> stubSourceFromConfig(specName, specmaticConfig)
    }
    return source ?: Source()
}

private fun testSourceFromConfig(specificationName: String, specmaticConfig: SpecmaticConfig): Source? {
    val sources = SpecmaticConfig.getSources(specmaticConfig)
    return sources.firstOrNull { source ->
        source.test.orEmpty().any { test ->
            test.contains(specificationName)
        }
    }
}


private fun stubSourceFromConfig(specificationName: String, specmaticConfig: SpecmaticConfig): Source? {
    val sources = SpecmaticConfig.getSources(specmaticConfig)
    return sources.firstOrNull { source ->
        source.stub.orEmpty().any { stub ->
            when (stub) {
                is SpecExecutionConfig.StringValue -> stub.value.contains(specificationName)
                is SpecExecutionConfig.ObjectValue -> stub.specs.any { it.contains(specificationName) }
            }
        }
    }
}




