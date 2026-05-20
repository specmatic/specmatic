package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.TemplatableValue
import io.specmatic.core.config.ConfigTemplateMetadata
import io.specmatic.core.config.ConfigTemplateUtils
import io.specmatic.core.config.Switch
import io.specmatic.core.config.OpenAPITestConfig as LegacyOpenAPITestConfig
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.ConcreteSettings
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.Specmatic
import io.specmatic.core.config.v3.components.settings.FeatureFlags
import io.specmatic.core.config.v3.components.settings.GeneralSettings
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.specmatic.Governance
import io.specmatic.core.config.v3.specmatic.License
import io.specmatic.core.config.v3.specmatic.Report
import io.specmatic.core.config.v3.specmatic.SuccessCriterion
import java.nio.file.Path

class SpecmaticMetadataMapper {
    fun mapFrom(legacyConfig: SpecmaticConfigV1V2Common, view: LegacyConfigView): Specmatic {
        return Specmatic(
            governance = buildGovernance(legacyConfig, view),
            settings = RefOrValue.Value(buildSettings(legacyConfig, view)),
            license = legacyConfig.getLicensePath()?.let { License(path = it.toUnixPath()) },
        )
    }

    fun transferMetadata(metadata: ConfigTemplateMetadata): ConfigTemplateMetadata {
        return metadata.transferTopLevelMetadata()
            .transferGeneralSettings()
            .transferTestSettings()
            .transferMockSettings()
            .transferLogging()
            .transferGovernance()
            .transferBackwardCompatibility()
    }

    private fun buildGovernance(legacyConfig: SpecmaticConfigV1V2Common, view: LegacyConfigView): Governance {
        val reportDir = SpecmaticConfigV1V2Common.getReportDirPathOrNull(legacyConfig)
        return Governance(
            report = Report(outputDirectory = reportDir?.toUnixPath()),
            successCriterion = view.report?.types?.apiCoverage?.openAPI?.successCriteria?.let(SuccessCriterion.Companion::from),
        )
    }

    private fun buildSettings(legacyConfig: SpecmaticConfigV1V2Common, view: LegacyConfigView): ConcreteSettings {
        return ConcreteSettings(
            test = buildTestSettings(view),
            mock = buildMockSettings(view),
            general = buildGeneralSettings(legacyConfig, view),
            backwardCompatibility = legacyConfig.getBackwardCompatibilityConfig(),
        )
    }

    private fun buildGeneralSettings(legacyConfig: SpecmaticConfigV1V2Common, view: LegacyConfigView): GeneralSettings {
        return GeneralSettings(
            featureFlags = featureFlagsFrom(legacyConfig),
            specExamplesDirectoryTemplate = view.globalSettings.specExamplesDirectoryTemplate,
            sharedExamplesDirectoryTemplate = view.globalSettings.sharedExamplesDirectoryTemplate,
            prettyPrint = SpecmaticConfigV1V2Common.getPrettyPrintOrNull(legacyConfig),
            logging = SpecmaticConfigV1V2Common.getLogConfigurationOrNull(legacyConfig),
            disableTelemetry = SpecmaticConfigV1V2Common.isTelemetryDisabledOrNull(legacyConfig),
            ignoreInlineExamples = SpecmaticConfigV1V2Common.getIgnoreInlineExamples(legacyConfig),
            ignoreInlineExampleWarnings = SpecmaticConfigV1V2Common.getIgnoreInlineExampleWarningsOrNull(legacyConfig),
        )
    }

    private fun featureFlagsFrom(legacyConfig: SpecmaticConfigV1V2Common): FeatureFlags {
        return FeatureFlags(
            escapeSoapAction = SpecmaticConfigV1V2Common.getEscapeSoapActionOrNull(legacyConfig),
            schemaExampleDefault = SpecmaticConfigV1V2Common.getSchemaExampleDefaultOrNull(legacyConfig),
            fuzzyMatcherForPayloads = SpecmaticConfigV1V2Common.getFuzzyMatchingEnabledOrNull(legacyConfig),
        )
    }

    private fun buildTestSettings(view: LegacyConfigView): TestSettings {
        val testConfigs = view.sources.flatMap { it.test.orEmpty() }
        val schemaResiliencyTestsFromProvides = extractSchemaResiliencyTestsFromProvides(testConfigs) ?: extractSchemaResiliencyTestsFromConfigValue(testConfigs)

        return TestSettings(
            strictMode = TemplatableValue.of(view.testConfig?.strictMode),
            lenientMode = TemplatableValue.of(view.testConfig?.lenientMode),
            parallelism = TemplatableValue.of(view.testConfig?.parallelism),
            maxTestCount = TemplatableValue.of(view.testConfig?.maxTestCount),
            junitReportDir = TemplatableValue.of(view.testConfig?.junitReportDir),
            timeoutInMilliseconds = TemplatableValue.of(view.testConfig?.timeoutInMilliseconds),
            validateResponseValues = TemplatableValue.of(view.testConfig?.validateResponseValues),
            maxTestRequestCombinations = TemplatableValue.of(view.testConfig?.maxTestRequestCombinations),
            schemaResiliencyTests = view.testConfig?.resiliencyTests?.enable ?: schemaResiliencyTestsFromProvides,
        )
    }

    private fun extractSchemaResiliencyTestsFromProvides(testConfigs: List<SpecExecutionConfig>): ResiliencyTestSuite? {
        return testConfigs.asReversed().firstNotNullOfOrNull { config ->
            (config as? SpecExecutionConfig.ObjectValue)?.resiliencyTests?.enable
        }
    }

    private fun extractSchemaResiliencyTestsFromConfigValue(testConfigs: List<SpecExecutionConfig>): ResiliencyTestSuite? {
        return testConfigs.asConfigValues().firstNotNullOfOrNull { configValue ->
            runCatching { LegacyOpenAPITestConfig.from(configValue.config).resiliencyTests?.enable }.getOrNull()
        }
    }

    private fun List<SpecExecutionConfig>.asConfigValues(): Sequence<SpecExecutionConfig.ConfigValue> {
        return asSequence().mapNotNull { it as? SpecExecutionConfig.ConfigValue }
    }

    private fun buildMockSettings(view: LegacyConfigView): MockSettings {
        return MockSettings(
            generative = view.stubConfig?.getTemplatedGenerative(),
            strictMode = view.stubConfig?.getTemplatedStrictMode(),
            lenientMode = view.stubConfig?.getTemplatedLenientMode(),
            hotReload = view.stubConfig?.getTemplatedHotReload()?.map({ it.toBoolean() }, ::rewriteSwitchTemplateDefault),
            delayInMilliseconds = view.stubConfig?.getTemplatedDelayInMilliseconds(),
            startTimeoutInMilliseconds = view.stubConfig?.getTemplatedStartTimeoutInMilliseconds(),
            gracefulRestartTimeoutInMilliseconds = view.stubConfig?.getTemplatedGracefulRestartTimeoutInMilliseconds(),
        )
    }

    private fun Switch.toBoolean() = when (this) {
        Switch.enabled -> true
        Switch.disabled -> false
    }

    private fun Path.toUnixPath(): String {
        return toString().replace('\\', '/')
    }

    private fun ConfigTemplateMetadata.transferTopLevelMetadata(): ConfigTemplateMetadata {
        return transferMany(
            TemplateTransfer(listOf("disableTelemetry"), listOf("specmatic", "settings", "general", "disableTelemetry")),
            TemplateTransfer(listOf("disable_telemetry"), listOf("specmatic", "settings", "general", "disableTelemetry")),
            TemplateTransfer(listOf("prettyPrint"), listOf("specmatic", "settings", "general", "prettyPrint")),
            TemplateTransfer(listOf("ignoreInlineExamples"), listOf("specmatic", "settings", "general", "ignoreInlineExamples")),
            TemplateTransfer(listOf("ignoreInlineExampleWarnings"), listOf("specmatic", "settings", "general", "ignoreInlineExampleWarnings")),
            TemplateTransfer(listOf("schemaExampleDefault"), listOf("specmatic", "settings", "general", "featureFlags", "schemaExampleDefault")),
            TemplateTransfer(listOf("fuzzy"), listOf("specmatic", "settings", "general", "featureFlags", "fuzzyMatcherForPayloads")),
            TemplateTransfer(listOf("escapeSoapAction"), listOf("specmatic", "settings", "general", "featureFlags", "escapeSoapAction")),
            TemplateTransfer(listOf("licensePath"), listOf("specmatic", "license", "path")),
            TemplateTransfer(listOf("license_path"), listOf("specmatic", "license", "path")),
            TemplateTransfer(listOf("reportDirPath"), listOf("specmatic", "governance", "report", "outputDirectory")),
            TemplateTransfer(listOf("report_dir_path"), listOf("specmatic", "governance", "report", "outputDirectory")),
        )
    }

    private fun ConfigTemplateMetadata.transferGeneralSettings(): ConfigTemplateMetadata {
        return transferTemplate(
            listOf("globalSettings", "specExamplesDirectoryTemplate"),
            listOf("specmatic", "settings", "general", "specExamplesDirectoryTemplate")
        ).transferTemplatesUnder(
            listOf("globalSettings", "sharedExamplesDirectoryTemplate"),
            listOf("specmatic", "settings", "general", "sharedExamplesDirectoryTemplate")
        )
    }

    private fun ConfigTemplateMetadata.transferTestSettings(): ConfigTemplateMetadata {
        return transferMany(
            TemplateTransfer(listOf("test", "validateResponseValues"), listOf("specmatic", "settings", "test", "validateResponseValues")),
            TemplateTransfer(listOf("test", "timeoutInMilliseconds"), listOf("specmatic", "settings", "test", "timeoutInMilliseconds")),
            TemplateTransfer(listOf("test", "strictMode"), listOf("specmatic", "settings", "test", "strictMode")),
            TemplateTransfer(listOf("test", "lenientMode"), listOf("specmatic", "settings", "test", "lenientMode")),
            TemplateTransfer(listOf("test", "parallelism"), listOf("specmatic", "settings", "test", "parallelism")),
            TemplateTransfer(listOf("test", "maxTestRequestCombinations"), listOf("specmatic", "settings", "test", "maxTestRequestCombinations")),
            TemplateTransfer(listOf("test", "maxTestCount"), listOf("specmatic", "settings", "test", "maxTestCount")),
            TemplateTransfer(listOf("test", "junitReportDir"), listOf("specmatic", "settings", "test", "junitReportDir")),
            TemplateTransfer(listOf("test", "resiliencyTests", "enable"), listOf("specmatic", "settings", "test", "schemaResiliencyTests")),
        )
    }

    private fun ConfigTemplateMetadata.transferMockSettings(): ConfigTemplateMetadata {
        return transferMany(
            TemplateTransfer(listOf("stub", "generative"), listOf("specmatic", "settings", "mock", "generative")),
            TemplateTransfer(listOf("stub", "delayInMilliseconds"), listOf("specmatic", "settings", "mock", "delayInMilliseconds")),
            TemplateTransfer(listOf("stub", "strictMode"), listOf("specmatic", "settings", "mock", "strictMode")),
            TemplateTransfer(listOf("stub", "lenientMode"), listOf("specmatic", "settings", "mock", "lenientMode")),
            TemplateTransfer(
                sourcePath = listOf("stub", "hotReload"),
                targetPath = listOf("specmatic", "settings", "mock", "hotReload"),
                expressionTransform = ::rewriteSwitchTemplateDefault,
                valueTransform = ::switchValueToBooleanText,
            ),
            TemplateTransfer(listOf("stub", "startTimeoutInMilliseconds"), listOf("specmatic", "settings", "mock", "startTimeoutInMilliseconds")),
            TemplateTransfer(listOf("stub", "gracefulRestartTimeoutInMilliseconds"), listOf("specmatic", "settings", "mock", "gracefulRestartTimeoutInMilliseconds")),
        )
    }

    private fun ConfigTemplateMetadata.transferLogging(): ConfigTemplateMetadata {
        return transferTemplatesUnder(
            sourcePrefix = listOf("logging"),
            targetPrefix = listOf("specmatic", "settings", "general", "logging"),
            suffixTransform = { suffix ->
                when (suffix.lastOrNull()) {
                    "logPrefix" -> suffix.dropLast(1) + "logFilePrefix"
                    else -> suffix
                }
            }
        )
    }

    private fun ConfigTemplateMetadata.transferGovernance(): ConfigTemplateMetadata {
        val sourcePrefix = listOf("report", "types", "APICoverage", "OpenAPI", "successCriteria")
        val targetPrefix = listOf("specmatic", "governance", "successCriteria")
        return transferTemplate(sourcePrefix + "minThresholdPercentage", targetPrefix + "minCoveragePercentage")
            .transferTemplate(sourcePrefix + "maxMissedEndpointsInSpec", targetPrefix + "maxMissedOperationsInSpec")
            .transferTemplate(sourcePrefix + "enforce", targetPrefix + "enforce")
    }

    private fun ConfigTemplateMetadata.transferBackwardCompatibility(): ConfigTemplateMetadata {
        return transferTemplatesUnder(
            listOf("backwardCompatibility"),
            listOf("specmatic", "settings", "backwardCompatibility")
        )
    }

    private fun ConfigTemplateMetadata.transferMany(vararg transfers: TemplateTransfer): ConfigTemplateMetadata {
        return transfers.fold(this) { metadata, transfer ->
            metadata.transferTemplate(
                sourcePath = transfer.sourcePath,
                targetPath = transfer.targetPath,
                expressionTransform = transfer.expressionTransform,
                valueTransform = transfer.valueTransform,
            )
        }
    }

    private data class TemplateTransfer(
        val sourcePath: List<String>,
        val targetPath: List<String>,
        val expressionTransform: (String) -> String = { it },
        val valueTransform: (String) -> String = { it },
    )

    private fun rewriteSwitchTemplateDefault(rawText: String): String {
        return ConfigTemplateUtils.findVariableTokens(rawText).asReversed().fold(rawText) { text, token ->
            val booleanDefault = switchValueToBooleanText(token.default)
            if (booleanDefault == token.default) return@fold text
            text.replaceRange(
                startIndex = token.startIndex,
                endIndex = token.endIndex + 1,
                replacement = ConfigTemplateUtils.createTemplate(token.names, booleanDefault),
            )
        }
    }

    private fun switchValueToBooleanText(value: String): String {
        return when (value) {
            "enabled" -> "true"
            "disabled" -> "false"
            else -> value
        }
    }
}
