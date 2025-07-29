package io.specmatic.test

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.loadSpecmaticConfigOrDefault
import io.specmatic.core.utilities.readEnvVarOrProperty

data class ContractTestSettings(
    val testBaseURL: String?,
    val contractPaths: String?,
    val filter: String,
    val configFile: String,
    val specmaticConfig: SpecmaticConfig?,
) {
    internal constructor(contractTestSettings: ThreadLocal<ContractTestSettings?>) : this(
        testBaseURL = contractTestSettings.get()?.testBaseURL ?: System.getProperty(SpecmaticJUnitSupport.TEST_BASE_URL),
        contractPaths = contractTestSettings.get()?.contractPaths ?: System.getProperty(SpecmaticJUnitSupport.CONTRACT_PATHS),
        filter = contractTestSettings.get()?.filter ?: readEnvVarOrProperty(
            SpecmaticJUnitSupport.FILTER,
            SpecmaticJUnitSupport.FILTER
        ).orEmpty(),
        configFile = contractTestSettings.get()?.configFile ?: getConfigFilePath(),
        specmaticConfig =
            contractTestSettings.get()?.specmaticConfig
                ?: contractTestSettings.get()?.configFile?.let {
                    loadSpecmaticConfigOrDefault(it)
                } ?: loadSpecmaticConfigOrDefault(getConfigFilePath()),
    )
}