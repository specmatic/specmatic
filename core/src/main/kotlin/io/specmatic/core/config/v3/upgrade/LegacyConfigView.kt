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
    val stubHooks: Map<String, String> by lazy {
        globalHooks.filterKeys {
            when (it) {
                "post_specmatic_response_processor", "pre_specmatic_request_processor" -> true
                "pre_specmatic_response_processor" -> false
                else -> true
            }
        }
    }

    val proxyHooks: Map<String, String> by lazy {
        globalHooks.filterKeys {
            when (it) {
                "pre_specmatic_request_processor", "pre_specmatic_response_processor" -> true
                "post_specmatic_response_processor" -> false
                else -> true
            }
        }
    }

    companion object {
        fun from(legacyConfig: SpecmaticConfigV1V2Common): LegacyConfigView {
            return LegacyConfigView(
                testConfig = legacyConfig.resolvedTest,
                stubConfig = legacyConfig.resolvedStub,
                globalHooks = legacyConfig.resolvedHooks,
                globalExamples = legacyConfig.resolvedExamples,
                gitAuth = legacyConfig.resolvedAuth?.toGitAuthentication(),
                report = legacyConfig.resolvedReport,
                globalSettings = legacyConfig.resolvedGlobalSettings,
                sources = legacyConfig.resolvedSources,
                workflow = legacyConfig.resolvedWorkflow,
                security = legacyConfig.resolvedSecurity
            )
        }

        private fun Auth.toGitAuthentication(): GitAuthentication? {
            val personalAccessToken = resolvedPersonalAccessToken
            val bearerEnvironmentVariable = resolvedBearerEnvironmentVariable
            val bearerFile = resolvedBearerFile
            return when {
                !personalAccessToken.isNullOrBlank() -> GitAuthentication.PersonalAccessToken(personalAccessToken)
                !bearerEnvironmentVariable.isNullOrBlank() -> GitAuthentication.BearerEnv(bearerEnvironmentVariable)
                else -> GitAuthentication.BearerFile(bearerFile)
            }
        }
    }
}
