package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.SpecmaticConfigV1V2Common
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
import io.specmatic.core.config.v3.specmatic.SuccessCriterion
import java.nio.file.Path

class SpecmaticMetadataMapper {
    fun mapFrom(legacyConfig: SpecmaticConfigV1V2Common, view: LegacyConfigView): Specmatic {
        return Specmatic(
            governance = buildGovernance(view),
            settings = RefOrValue.Value(buildSettings(legacyConfig, view)),
            license = legacyConfig.getLicensePath()?.let { License(path = it.toUnixPath()) },
        )
    }

    private fun buildGovernance(view: LegacyConfigView): Governance {
        return Governance(
            report = null,
            successCriterion = view.report?.types?.apiCoverage?.openAPI?.successCriteria?.let(SuccessCriterion.Companion::from),
        )
    }

    private fun buildSettings(legacyConfig: SpecmaticConfigV1V2Common, view: LegacyConfigView): ConcreteSettings {
        return ConcreteSettings(
            test = if (view.hasTestService()) null else buildTestSettings(view),
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
            strictMode = view.testConfig?.strictMode,
            lenientMode = view.testConfig?.lenientMode,
            parallelism = view.testConfig?.parallelism,
            maxTestCount = view.testConfig?.maxTestCount,
            junitReportDir = view.testConfig?.junitReportDir ?: view.reportDirPath?.toUnixPath(),
            timeoutInMilliseconds = view.testConfig?.timeoutInMilliseconds,
            validateResponseValues = view.testConfig?.validateResponseValues,
            maxTestRequestCombinations = view.testConfig?.maxTestRequestCombinations,
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
            generative = view.stubConfig?.getGenerative(),
            strictMode = view.stubConfig?.getStrictMode(),
            lenientMode = view.stubConfig?.getLenientMode(),
            hotReload = if (view.hasDependencyServices()) null else view.stubConfig?.getHotReload()?.toBoolean(),
            delayInMilliseconds = view.stubConfig?.getDelayInMilliseconds(),
            startTimeoutInMilliseconds = view.stubConfig?.getStartTimeoutInMilliseconds(),
            gracefulRestartTimeoutInMilliseconds = view.stubConfig?.getGracefulRestartTimeoutInMilliseconds(),
        )
    }

    private fun LegacyConfigView.hasTestService(): Boolean {
        return sources.any { source -> !source.test.isNullOrEmpty() }
    }

    private fun LegacyConfigView.hasDependencyServices(): Boolean {
        return sources.any { source -> !source.stub.isNullOrEmpty() }
    }

    private fun Switch.toBoolean() = when (this) {
        Switch.enabled -> true
        Switch.disabled -> false
    }

    private fun Path.toUnixPath(): String {
        return toString().replace('\\', '/')
    }
}
