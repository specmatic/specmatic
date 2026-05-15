package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.Switch
import io.specmatic.core.config.OpenAPITestConfig as LegacyOpenAPITestConfig
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
import io.specmatic.core.config.v3.ConcreteSettings
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.Specmatic
import io.specmatic.core.config.wrap
import io.specmatic.core.config.resolveFully
import io.specmatic.core.config.v3.components.settings.FeatureFlags
import io.specmatic.core.config.v3.components.settings.GeneralSettings
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.specmatic.Governance
import io.specmatic.core.config.v3.specmatic.License
import io.specmatic.core.config.v3.specmatic.Report
import io.specmatic.core.config.v3.specmatic.SuccessCriterion
import io.specmatic.core.config.wrap
import java.nio.file.Path

class SpecmaticMetadataMapper {
    fun mapFrom(legacyConfig: SpecmaticConfigV1V2Common, view: LegacyConfigView): Specmatic {
        return Specmatic(
            governance = wrap(buildGovernance(legacyConfig, view)),
            settings = wrap(RefOrValue.Value(buildSettings(legacyConfig, view))),
            license = legacyConfig.getLicensePath()?.let { wrap(License(path = wrap(it.toUnixPath()))) },
        )
    }

    private fun buildGovernance(legacyConfig: SpecmaticConfigV1V2Common, view: LegacyConfigView): Governance {
        val reportDir = SpecmaticConfigV1V2Common.getReportDirPathOrNull(legacyConfig)
        return Governance(
            report = wrap(Report(outputDirectory = reportDir?.toUnixPath()?.let(::wrap))),
            successCriterion = view.report?.types?.resolve()?.apiCoverage?.resolve()?.openAPI?.resolve()?.successCriteria?.resolve()?.let(SuccessCriterion.Companion::from)?.let(::wrap),
        )
    }

    private fun buildSettings(legacyConfig: SpecmaticConfigV1V2Common, view: LegacyConfigView): ConcreteSettings {
        return ConcreteSettings(
            test = wrap(buildTestSettings(view)),
            mock = wrap(buildMockSettings(view)),
            general = wrap(buildGeneralSettings(legacyConfig, view)),
            backwardCompatibility = legacyConfig.getBackwardCompatibilityConfig()?.let(::wrap),
        )
    }

    private fun buildGeneralSettings(legacyConfig: SpecmaticConfigV1V2Common, view: LegacyConfigView): GeneralSettings {
        return GeneralSettings(
            featureFlags = wrap(featureFlagsFrom(legacyConfig)),
            specExamplesDirectoryTemplate = view.globalSettings.specExamplesDirectoryTemplate,
            sharedExamplesDirectoryTemplate = view.globalSettings.sharedExamplesDirectoryTemplate,
            prettyPrint = SpecmaticConfigV1V2Common.getPrettyPrintOrNull(legacyConfig)?.let(::wrap),
            logging = SpecmaticConfigV1V2Common.getLogConfigurationOrNull(legacyConfig)?.let(::wrap),
            disableTelemetry = SpecmaticConfigV1V2Common.isTelemetryDisabledOrNull(legacyConfig)?.let(::wrap),
            ignoreInlineExamples = SpecmaticConfigV1V2Common.getIgnoreInlineExamples(legacyConfig)?.let(::wrap),
            ignoreInlineExampleWarnings = SpecmaticConfigV1V2Common.getIgnoreInlineExampleWarningsOrNull(legacyConfig)?.let(::wrap),
        )
    }

    private fun featureFlagsFrom(legacyConfig: SpecmaticConfigV1V2Common): FeatureFlags {
        return FeatureFlags(
            escapeSoapAction = SpecmaticConfigV1V2Common.getEscapeSoapActionOrNull(legacyConfig)?.let(::wrap),
            schemaExampleDefault = SpecmaticConfigV1V2Common.getSchemaExampleDefaultOrNull(legacyConfig)?.let(::wrap),
            fuzzyMatcherForPayloads = SpecmaticConfigV1V2Common.getFuzzyMatchingEnabledOrNull(legacyConfig)?.let(::wrap),
        )
    }

    private fun buildTestSettings(view: LegacyConfigView): TestSettings {
        val testConfigs = view.sources.flatMap { it.test?.resolveFully().orEmpty() }
        val schemaResiliencyTestsFromProvides = extractSchemaResiliencyTestsFromProvides(testConfigs) ?: extractSchemaResiliencyTestsFromConfigValue(testConfigs)

        return TestSettings(
            strictMode = view.testConfig?.strictMode,
            lenientMode = view.testConfig?.lenientMode,
            parallelism = view.testConfig?.parallelism,
            maxTestCount = view.testConfig?.maxTestCount,
            junitReportDir = view.testConfig?.junitReportDir,
            timeoutInMilliseconds = view.testConfig?.timeoutInMilliseconds,
            validateResponseValues = view.testConfig?.validateResponseValues,
            maxTestRequestCombinations = view.testConfig?.maxTestRequestCombinations,
            schemaResiliencyTests = view.testConfig?.resiliencyTests?.resolve()?.enable ?: schemaResiliencyTestsFromProvides?.let(::wrap),
        )
    }

    private fun extractSchemaResiliencyTestsFromProvides(testConfigs: List<SpecExecutionConfig>): ResiliencyTestSuite? {
        return testConfigs.asReversed().firstNotNullOfOrNull { config ->
            (config as? SpecExecutionConfig.ObjectValue)?.resiliencyTests?.enable
        }
    }

    private fun extractSchemaResiliencyTestsFromConfigValue(testConfigs: List<SpecExecutionConfig>): ResiliencyTestSuite? {
        return testConfigs.asConfigValues().firstNotNullOfOrNull { configValue ->
            runCatching { LegacyOpenAPITestConfig.from(configValue.config.resolve()).resiliencyTests?.resolve()?.enable?.resolve() }.getOrNull()
        }
    }

    private fun List<SpecExecutionConfig>.asConfigValues(): Sequence<SpecExecutionConfig.ConfigValue> {
        return asSequence().mapNotNull { it as? SpecExecutionConfig.ConfigValue }
    }

    private fun buildMockSettings(view: LegacyConfigView): MockSettings {
        return MockSettings(
            generative = view.stubConfig?.getGenerative()?.let(::wrap),
            strictMode = view.stubConfig?.getStrictMode()?.let(::wrap),
            lenientMode = view.stubConfig?.getLenientMode()?.let(::wrap),
            hotReload = view.stubConfig?.getHotReload()?.toBoolean()?.let(::wrap),
            delayInMilliseconds = view.stubConfig?.getDelayInMilliseconds()?.let(::wrap),
            startTimeoutInMilliseconds = view.stubConfig?.getStartTimeoutInMilliseconds()?.let(::wrap),
            gracefulRestartTimeoutInMilliseconds = view.stubConfig?.getGracefulRestartTimeoutInMilliseconds()?.let(::wrap),
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
