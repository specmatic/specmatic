package io.specmatic.core.git

import io.specmatic.core.Auth
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SpecmaticConfigV1V2Common
import org.assertj.core.api.AssertionsForClassTypes.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GitOperationsTest {
    @Test
    fun shouldNotChangeGitRepoUrlWhenThereAreNoEnvVariables() {
        val gitRepositoryURI = "https://gitlab.com/group/project.git"
        val evaluatedGitRepositoryURI =
            evaluateEnvVariablesInGitRepoURI(gitRepositoryURI = gitRepositoryURI, emptyMap())
        assertThat(evaluatedGitRepositoryURI).isEqualTo(gitRepositoryURI)
    }

    @Test
    fun shouldChangeGitRepoUrlWhenThereIsOneEnvVariable() {
        val gitRepositoryURI = "https://gitlab-ci-token:${'$'}{CI_JOB_TOKEN}@gitlab.com/group/project.git"
        val evaluatedGitRepositoryURI =
            evaluateEnvVariablesInGitRepoURI(gitRepositoryURI = gitRepositoryURI, mapOf("CI_JOB_TOKEN" to "token"))
        assertThat(evaluatedGitRepositoryURI).isEqualTo("https://gitlab-ci-token:token@gitlab.com/group/project.git")
    }

    @Test
    fun shouldChangeGitRepoUrlWhenThereAreMultipleEnvVariable() {
        val gitRepositoryURI = "https://${'$'}{USER_NAME}:${'$'}{PASSWORD}@gitlab.com/group/project.git"
        val evaluatedGitRepositoryURI =
            evaluateEnvVariablesInGitRepoURI(
                gitRepositoryURI = gitRepositoryURI,
                mapOf("USER_NAME" to "john", "PASSWORD" to "password")
            )
        assertThat(evaluatedGitRepositoryURI).isEqualTo("https://john:password@gitlab.com/group/project.git")
    }

    @Test
    fun shouldNotEvaluatePatternsThatResembleEnvVarsWhenTheValueIsNotAvailable() {
        val gitRepositoryURI = "https://gitlab-ci-token:${'$'}{CI_JOB_TOKEN}@gitlab.com/group/${'$'}{do-not-eval}/project.git"
        val evaluatedGitRepositoryURI =
            evaluateEnvVariablesInGitRepoURI(
                gitRepositoryURI = gitRepositoryURI,
                mapOf("CI_JOB_TOKEN" to "token")
            )
        assertThat(evaluatedGitRepositoryURI).isEqualTo("https://gitlab-ci-token:token@gitlab.com/group/${'$'}{do-not-eval}/project.git")
    }

    @Test
    fun shouldReturnPersonalAccessTokenFromAuthConfig() {
        val specmaticConfig = SpecmaticConfigV1V2Common(auth = Auth(personalAccessToken = "token-123"))

        assertThat(getPersonalAccessToken(specmaticConfig, "https://gihub.com/org/repo.git")).isEqualTo("token-123")
    }

    @Test
    fun shouldReturnNullWhenPersonalAccessTokenIsNotConfigured() {
        val specmaticConfig = SpecmaticConfig()

        assertThat(getPersonalAccessToken(specmaticConfig, "https://gihub.com/org/repo.git")).isNull()
    }

    @Test
    fun shouldReturnPersonalAccessTokenFromHomeDirectoryConfig(@TempDir tempDir: Path) {
        val originalHome = System.getProperty("user.home")
        val originalPersonalAccessTokenProperty = System.getProperty("personalAccessToken")

        System.setProperty("user.home", tempDir.toString())
        System.clearProperty("personalAccessToken")

        val configFile = tempDir.resolve("specmatic-azure.json").toFile()
        configFile.writeText("""{"azure-access-token":"token-456"}""")

        try {
            val specmaticConfig = SpecmaticConfig()

            assertThat(getPersonalAccessToken(specmaticConfig, "https://gihub.com/org/repo.git")).isEqualTo("token-456")
        } finally {
            System.setProperty("user.home", originalHome)
            if (originalPersonalAccessTokenProperty == null) {
                System.clearProperty("personalAccessToken")
            } else {
                System.setProperty("personalAccessToken", originalPersonalAccessTokenProperty)
            }
        }
    }
}
