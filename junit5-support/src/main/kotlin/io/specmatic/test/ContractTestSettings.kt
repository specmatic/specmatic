package io.specmatic.test

import io.specmatic.core.Configuration
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.loadSpecmaticConfigOrDefault
import io.specmatic.core.loadSpecmaticConfigOrNull
import io.specmatic.core.orDefault

import io.specmatic.reporter.model.SpecType
import io.specmatic.test.reports.TestReportListener
import java.io.File

data class DeprecatedArguments(
    val host: String? = null,
    val port: String? = null,
    val envName: String? = null,
    val protocol: String? = null,
    val filterName: String? = null,
    val filterNotName: String? = null,
    val overlayFilePath: File? = null,
    val suggestionsPath: String? = null,
    val inlineSuggestions: String? = null,
    val variablesFileName: String? = null,
    val exampleDirectories: List<String>? = null,
    val useCurrentBranchForCentralRepo: Boolean? = null
)

data class ContractTestSettings(
    val testBaseURL: String? = null,
    val contractPaths: String? = null,
    val filter: String? = null,
    val configFile: String? = null,
    val generative: Boolean? = null,
    val reportBaseDirectory: String? = null,
    val coverageHooks: List<TestReportListener> = emptyList(),
    val strictMode: Boolean? = null,
    val lenientMode: Boolean = false,
    val previousTestRuns: List<TestResultRecord> = emptyList(),
    val timeoutInMilliSeconds: Long? = null,
    val otherArguments: DeprecatedArguments? = null,
) {
    val host: String? = otherArguments?.host
    val port: String? = otherArguments?.port
    val envName: String? = otherArguments?.envName
    val protocol: String? = otherArguments?.protocol
    val filterName: String? = otherArguments?.filterName
    val filterNotName: String? = otherArguments?.filterNotName
    val overlayFilePath: File? = otherArguments?.overlayFilePath
    val suggestionsPath: String? = otherArguments?.suggestionsPath
    val inlineSuggestions: String? = otherArguments?.inlineSuggestions
    val variablesFileName: String? = otherArguments?.variablesFileName

    fun getSpecmaticConfig(): SpecmaticConfig {
        val specmaticConfig = loadSpecmaticConfigOrNull(configFile, explicitlySpecifiedByUser = configFile != null).orDefault()
        return adjust(specmaticConfig)
    }

    private fun adjust(specmaticConfig: SpecmaticConfig): SpecmaticConfig {
        return specmaticConfig
            .let(::adjustFilter)
            .let(::adjustExamples)
            .let(::adjustTestModes)
            .let(::adjustResilience)
            .let(::adjustTestTimeout)
            .let(::adjustUseCurrentBranch)
    }

    private fun adjustFilter(specmaticConfig: SpecmaticConfig): SpecmaticConfig {
        if (filter == null) return specmaticConfig
        return specmaticConfig.withTestFilter(filter)
    }

    private fun adjustResilience(specmaticConfig: SpecmaticConfig): SpecmaticConfig {
        return if (generative == true) {
            specmaticConfig.enableResiliencyTests()
        } else {
            specmaticConfig
        }
    }

    private fun adjustTestModes(specmaticConfig: SpecmaticConfig): SpecmaticConfig {
        return specmaticConfig.withTestModes(strictMode, lenientMode)
    }

    private fun adjustExamples(specmaticConfig: SpecmaticConfig): SpecmaticConfig {
        if (otherArguments?.exampleDirectories == null) return specmaticConfig
        return specmaticConfig.plusExamples(otherArguments.exampleDirectories)
    }

    private fun adjustUseCurrentBranch(specmaticConfig: SpecmaticConfig): SpecmaticConfig {
        if (otherArguments?.useCurrentBranchForCentralRepo == null) return specmaticConfig
        return specmaticConfig.withMatchBranch(otherArguments.useCurrentBranchForCentralRepo)
    }

    private fun adjustTestTimeout(specmaticConfig: SpecmaticConfig): SpecmaticConfig {
        if (timeoutInMilliSeconds == null) return specmaticConfig
        return specmaticConfig.withTestTimeout(timeoutInMilliSeconds)
    }

    constructor(contractTestSettings: ThreadLocal<ContractTestSettings?>) : this (
        contractTestSettings,
        contractTestSettings.get()?.configFile?.let {
            loadSpecmaticConfigOrNull(it, explicitlySpecifiedByUser = true)
        } ?: loadSpecmaticConfigOrDefault(Configuration.configFilePath),
    )

    constructor(contractTestSettings: ThreadLocal<ContractTestSettings?>, specmaticConfig: SpecmaticConfig) : this(
        generative = contractTestSettings.get()?.generative,
        reportBaseDirectory = contractTestSettings.get()?.reportBaseDirectory,
        coverageHooks = contractTestSettings.get()?.coverageHooks ?: emptyList(),
        previousTestRuns = contractTestSettings.get()?.previousTestRuns.orEmpty(),
        configFile = contractTestSettings.get()?.configFile ?: getConfigFilePath(),
        strictMode = contractTestSettings.get()?.strictMode ?: SpecmaticConfig().getTestStrictMode(),
        lenientMode = contractTestSettings.get()?.lenientMode ?: SpecmaticConfig().getTestLenientMode() ?: false,
        testBaseURL = contractTestSettings.get()?.testBaseURL ?: specmaticConfig.getTestBaseUrl(SpecType.OPENAPI),
        contractPaths = contractTestSettings.get()?.contractPaths,
        timeoutInMilliSeconds = contractTestSettings.get()?.timeoutInMilliSeconds,
        filter = contractTestSettings.get()?.filter ?: specmaticConfig.getTestFilter(),
        otherArguments = DeprecatedArguments(
            host = contractTestSettings.get()?.otherArguments?.host,
            port = contractTestSettings.get()?.otherArguments?.port,
            envName = contractTestSettings.get()?.otherArguments?.envName,
            protocol = contractTestSettings.get()?.otherArguments?.protocol,
            useCurrentBranchForCentralRepo = contractTestSettings.get()?.otherArguments?.useCurrentBranchForCentralRepo,
            suggestionsPath = contractTestSettings.get()?.otherArguments?.suggestionsPath,
            inlineSuggestions = contractTestSettings.get()?.otherArguments?.inlineSuggestions,
            variablesFileName = contractTestSettings.get()?.otherArguments?.variablesFileName,
            exampleDirectories = contractTestSettings.get()?.otherArguments?.exampleDirectories ?: specmaticConfig.getExamples(),
            filterName = contractTestSettings.get()?.otherArguments?.filterName ?: specmaticConfig.getTestFilterName(),
            filterNotName = contractTestSettings.get()?.otherArguments?.filterNotName ?: specmaticConfig.getTestFilterNotName(),
            overlayFilePath = contractTestSettings.get()?.otherArguments?.overlayFilePath,
        ),
    )
}
