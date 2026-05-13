package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.Auth
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.v3.components.sources.GitAuthentication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LegacyConfigViewTest {
    @Test
    fun `maps legacy config fields and prefers personal access token`() {
        val legacy = SpecmaticConfigV1V2Common(
            sources = listOf(Source(provider = SourceProvider.filesystem, directory = "./specs")),
            auth = Auth(bearerFile = "bearer.txt", bearerEnvironmentVariable = "TOKEN_ENV", personalAccessToken = "pat-123"),
            hooks = mapOf("request-body" to "hooks/req.js"),
            examples = listOf("examples"),
        )

        val view = LegacyConfigView.from(legacy)
        assertThat(view.sources).hasSize(1)
        assertThat(view.globalHooks).containsEntry("request-body", "hooks/req.js")
        assertThat(view.globalExamples).containsExactly("examples")
        assertThat(view.gitAuth).isEqualTo(GitAuthentication.PersonalAccessToken("pat-123"))
    }

    @Test
    fun `uses bearer env when pat absent`() {
        val legacy = SpecmaticConfigV1V2Common(auth = Auth(bearerFile = "bearer.txt", bearerEnvironmentVariable = "TOKEN_ENV"))
        val view = LegacyConfigView.from(legacy)
        assertThat(view.gitAuth).isEqualTo(GitAuthentication.BearerEnv("TOKEN_ENV"))
    }

    @Test
    fun `fallback to bearer file auth when no token is found, but auth field is present`() {
        val legacy = SpecmaticConfigV1V2Common(auth = Auth())
        val view = LegacyConfigView.from(legacy)
        assertThat(view.gitAuth).isEqualTo(GitAuthentication.BearerFile("bearer.txt"))
    }
}
