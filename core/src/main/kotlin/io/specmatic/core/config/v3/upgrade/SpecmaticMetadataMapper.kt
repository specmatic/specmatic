package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.Switch
import io.specmatic.core.config.OpenAPITestConfig as LegacyOpenAPITestConfig
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.ConcreteSettings
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.resolveOrNull
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

    private fun buildGovernance(legacyConfig: SpecmaticConfigV1V2Common, view: LegacyConfigView): Governance {
        val reportDir = legacyConfig.resolvedReportDirPath
        return Governance(
            report = Report(outputDirectory = reportDir?.toUnixPath()),
            successCriterion = view.report?.resolvedTypes?.resolvedApiCoverage?.resolvedOpenAPI?.resolvedSuccessCriteria?.let(SuccessCriterion.Companion::from),
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
            specExamplesDirectoryTemplate = view.globalSettings.resolvedSpecExamplesDirectoryTemplate,
            sharedExamplesDirectoryTemplate = view.globalSettings.resolvedSharedExamplesDirectoryTemplate,
            prettyPrint = legacyConfig.resolvedPrettyPrint,
            logging = legacyConfig.resolvedLogging,
            disableTelemetry = legacyConfig.resolvedDisableTelemetry,
            ignoreInlineExamples = legacyConfig.resolvedIgnoreInlineExamples,
            ignoreInlineExampleWarnings = legacyConfig.resolvedIgnoreInlineExampleWarnings,
        )
    }

    private fun featureFlagsFrom(legacyConfig: SpecmaticConfigV1V2Common): FeatureFlags {
        return FeatureFlags(
            escapeSoapAction = legacyConfig.resolvedEscapeSoapAction,
            schemaExampleDefault = legacyConfig.resolvedSchemaExampleDefault,
            fuzzyMatcherForPayloads = legacyConfig.resolvedFuzzy,
        )
    }

    private fun buildTestSettings(view: LegacyConfigView): TestSettings {
        val testConfigs = view.sources.flatMap { it.resolvedTest.orEmpty() }
        val schemaResiliencyTestsFromProvides = extractSchemaResiliencyTestsFromProvides(testConfigs) ?: extractSchemaResiliencyTestsFromConfigValue(testConfigs)

        return TestSettings(
            strictMode = view.testConfig?.resolvedStrictMode,
            lenientMode = view.testConfig?.resolvedLenientMode,
            parallelism = view.testConfig?.resolvedParallelism,
            maxTestCount = view.testConfig?.resolvedMaxTestCount,
            junitReportDir = view.testConfig?.resolvedJunitReportDir,
            timeoutInMilliseconds = view.testConfig?.resolvedTimeoutInMilliseconds,
            validateResponseValues = view.testConfig?.resolvedValidateResponseValues,
            maxTestRequestCombinations = view.testConfig?.resolvedMaxTestRequestCombinations,
            schemaResiliencyTests = view.testConfig?.resolvedResiliencyTests?.resolvedEnable ?: schemaResiliencyTestsFromProvides,
        )
    }

    private fun extractSchemaResiliencyTestsFromProvides(testConfigs: List<SpecExecutionConfig>): ResiliencyTestSuite? {
        return testConfigs.asReversed().firstNotNullOfOrNull { config ->
            (config as? SpecExecutionConfig.ObjectValue)?.resolvedResiliencyTests?.resolvedEnable
        }
    }

    private fun extractSchemaResiliencyTestsFromConfigValue(testConfigs: List<SpecExecutionConfig>): ResiliencyTestSuite? {
        return testConfigs.asConfigValues().firstNotNullOfOrNull { configValue ->
            runCatching { LegacyOpenAPITestConfig.from(configValue.resolvedConfig).resiliencyTests?.resolvedEnable }.getOrNull()
        }
    }

    private fun List<SpecExecutionConfig>.asConfigValues(): Sequence<SpecExecutionConfig.ConfigValue> {
        return asSequence().mapNotNull { it as? SpecExecutionConfig.ConfigValue }
    }

    private fun buildMockSettings(view: LegacyConfigView): MockSettings {
        return MockSettings(
            generative = view.stubConfig?.resolvedGenerative,
            strictMode = view.stubConfig?.resolvedStrictMode,
            lenientMode = view.stubConfig?.resolvedLenientMode,
            hotReload = view.stubConfig?.resolvedHotReload?.toBoolean(),
            delayInMilliseconds = view.stubConfig?.resolvedDelayInMilliseconds,
            startTimeoutInMilliseconds = view.stubConfig?.resolvedStartTimeoutInMilliseconds,
            gracefulRestartTimeoutInMilliseconds = view.stubConfig?.resolvedGracefulRestartTimeoutInMilliseconds,
        )
    }

    private fun Switch.toBoolean() = when (this) {
        Switch.enabled -> true
        Switch.disabled -> false
    }

    private fun Path.toUnixPath(): String {
        return toString().replace('\\', '/')
    }
}
