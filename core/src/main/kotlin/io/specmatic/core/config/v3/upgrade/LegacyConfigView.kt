package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.Auth
import io.specmatic.core.ReportConfigurationDetails
import io.specmatic.core.SecurityConfiguration
import io.specmatic.core.Source
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.TestConfiguration
import io.specmatic.core.WorkflowConfiguration
import io.specmatic.core.config.SpecmaticGlobalSettings
import io.specmatic.core.config.v3.components.sources.GitAuthentication

data class LegacyConfigView(
    val sources: List<Source>,
    val gitAuth: GitAuthentication?,
    val globalExamples: List<String>,
    val testConfig: TestConfiguration?,
    val workflow: WorkflowConfiguration?,
    val security: SecurityConfiguration?,
    val globalHooks: Map<String, String>,
    val report: ReportConfigurationDetails?,
    val globalSettings: SpecmaticGlobalSettings,
    val stubConfig: io.specmatic.core.StubConfiguration?,
) {
    companion object {
        fun from(legacyConfig: SpecmaticConfigV1V2Common): LegacyConfigView {
            val testConfig = SpecmaticConfigV1V2Common.getTestConfigOrNull(legacyConfig)
            val stubConfig = SpecmaticConfigV1V2Common.getStubConfigOrNull(legacyConfig)

            return LegacyConfigView(
                testConfig = testConfig,
                stubConfig = stubConfig,
                globalHooks = legacyConfig.getHooks(),
                globalExamples = legacyConfig.getExamples(),
                gitAuth = legacyConfig.getAuth()?.toGitAuthentication(),
                report = SpecmaticConfigV1V2Common.getReport(legacyConfig),
                globalSettings = legacyConfig.getGlobalSettingsOrDefault(),
                sources = SpecmaticConfigV1V2Common.getSources(legacyConfig),
                workflow = SpecmaticConfigV1V2Common.getWorkflowConfiguration(legacyConfig),
                security = SpecmaticConfigV1V2Common.getSecurityConfiguration(legacyConfig)
            )
        }

        private fun Auth.toGitAuthentication(): GitAuthentication? {
            return when {
                !personalAccessToken.isNullOrBlank() -> GitAuthentication.PersonalAccessToken(personalAccessToken)
                !bearerEnvironmentVariable.isNullOrBlank() -> GitAuthentication.BearerEnv(bearerEnvironmentVariable)
                else -> GitAuthentication.BearerFile(bearerFile)
            }
        }
    }
}
