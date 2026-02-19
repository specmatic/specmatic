package io.specmatic.test

import io.specmatic.core.Configuration
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.nonNullElse
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
    private val specmaticConfig = loadSpecmaticConfigOrNull(configFile, explicitlySpecifiedByUser = configFile != null).orDefault()

    fun getSpecmaticConfig(): SpecmaticConfig {
        return adjust(specmaticConfig)
    }

    fun getReportFilter(): String? {
        if (specmaticConfig.getTestFilter() == filter) return filter
        return specmaticConfig.getTestFilter().nonNullElse(filter) { filter1, filter2 ->
            listOf(filter1, filter2).filter(String::isNotBlank).joinToString(separator = " && ") { "( $it )" }
        }
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
        contractTestSettings.get(), specmaticConfig
    )

    constructor(contractTestSettings: ContractTestSettings?, specmaticConfig: SpecmaticConfig) : this(
        generative = contractTestSettings?.generative,
        reportBaseDirectory = contractTestSettings?.reportBaseDirectory,
        coverageHooks = contractTestSettings?.coverageHooks ?: emptyList(),
        previousTestRuns = contractTestSettings?.previousTestRuns.orEmpty(),
        configFile = contractTestSettings?.configFile ?: getConfigFilePath(),
        strictMode = contractTestSettings?.strictMode ?: SpecmaticConfig().getTestStrictMode(),
        lenientMode = contractTestSettings?.lenientMode ?: SpecmaticConfig().getTestLenientMode() ?: false,
        testBaseURL = contractTestSettings?.testBaseURL ?: specmaticConfig.getTestBaseUrl(SpecType.OPENAPI),
        contractPaths = contractTestSettings?.contractPaths,
        timeoutInMilliSeconds = contractTestSettings?.timeoutInMilliSeconds,
        filter = contractTestSettings?.filter ?: specmaticConfig.getTestFilter(),
        otherArguments = DeprecatedArguments(
            host = contractTestSettings?.otherArguments?.host,
            port = contractTestSettings?.otherArguments?.port,
            envName = contractTestSettings?.otherArguments?.envName,
            protocol = contractTestSettings?.otherArguments?.protocol,
            useCurrentBranchForCentralRepo = contractTestSettings?.otherArguments?.useCurrentBranchForCentralRepo,
            suggestionsPath = contractTestSettings?.otherArguments?.suggestionsPath,
            inlineSuggestions = contractTestSettings?.otherArguments?.inlineSuggestions,
            variablesFileName = contractTestSettings?.otherArguments?.variablesFileName,
            exampleDirectories = contractTestSettings?.otherArguments?.exampleDirectories ?: specmaticConfig.getExamples(),
            filterName = contractTestSettings?.otherArguments?.filterName ?: specmaticConfig.getTestFilterName(),
            filterNotName = contractTestSettings?.otherArguments?.filterNotName ?: specmaticConfig.getTestFilterNotName(),
            overlayFilePath = contractTestSettings?.otherArguments?.overlayFilePath,
        ),
    )
}
