package io.specmatic.test

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.readEnvVarOrProperty
import io.specmatic.test.reports.TestReportListener
import java.io.File

data class ContractTestSettings(
    val testBaseURL: String?,
    val contractPaths: String?,
    val filter: String,
    val configFile: String,
    val generative: Boolean?,
    val reportBaseDirectory: String?,
    val coverageHooks: List<TestReportListener>,
    val strictMode: Boolean?,
) {
    fun adjust(specmaticConfig: SpecmaticConfig?): SpecmaticConfig? =
        if (generative == true) {
            (specmaticConfig ?: SpecmaticConfig()).enableResiliencyTests()
        } else {
            specmaticConfig
        }

    fun getAdjustedConfig(): SpecmaticConfig? {
        if (configFile.isBlank()) return null

        if (File(configFile).exists().not()) {
            return null
        }

        return adjust(loadSpecmaticConfig(configFile))
    }

    internal constructor(contractTestSettings: ThreadLocal<ContractTestSettings?>) : this(
        testBaseURL = contractTestSettings.get()?.testBaseURL ?: System.getProperty(SpecmaticJUnitSupport.TEST_BASE_URL),
        contractPaths = contractTestSettings.get()?.contractPaths ?: System.getProperty(SpecmaticJUnitSupport.CONTRACT_PATHS),
        filter =
            contractTestSettings.get()?.filter ?: readEnvVarOrProperty(
                SpecmaticJUnitSupport.FILTER,
                SpecmaticJUnitSupport.FILTER,
            ).orEmpty(),
        configFile = contractTestSettings.get()?.configFile ?: getConfigFilePath(),
        generative = contractTestSettings.get()?.generative,
        reportBaseDirectory = contractTestSettings.get()?.reportBaseDirectory,
        coverageHooks = contractTestSettings.get()?.coverageHooks ?: emptyList(),
        strictMode = contractTestSettings.get()?.strictMode ?: Flags.getStringValue(Flags.TEST_STRICT_MODE)?.toBoolean(),
    )
}
