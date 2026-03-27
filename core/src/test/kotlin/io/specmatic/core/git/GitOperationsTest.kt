package io.specmatic.core.git

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.specmatic.core.Auth
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.pattern.ContractException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GitOperationsTest {
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `orchestration clone should use system git when it succeeds`(@TempDir tempDir: File) {
        val gitRepositoryURI = "https://github.com/specmatic/enterprise.git"
        val cloneDirectory = tempDir.resolve("enterprise")
        val systemGit = mockk<GitCommand>()
        val jGitCloneCommand = mockk<JGitCloneCmd>()

        every { systemGit.clone(gitRepositoryURI, cloneDirectory) } returns mockk<SystemGit>()

        clone(gitRepositoryURI, cloneDirectory, SpecmaticConfigV1V2Common(), systemGit, jGitCloneCommand)

        verify(exactly = 1) { systemGit.clone(gitRepositoryURI, cloneDirectory) }
        verify(exactly = 0) { jGitCloneCommand.execute(any(), any(), any()) }
    }

    @Test
    fun `orchestration clone should fall back to jgit when system git fails`(@TempDir tempDir: File) {
        val gitRepositoryURI = "https://github.com/specmatic/enterprise.git"
        val cloneDirectory = tempDir.resolve("enterprise")
        val systemGit = mockk<GitCommand>()
        val jGitCloneCommand = mockk<JGitCloneCmd>()

        every { systemGit.clone(gitRepositoryURI, cloneDirectory) } throws RuntimeException("system git failed")
        every { jGitCloneCommand.execute(any(), any(), any()) } returns Unit

        clone(gitRepositoryURI, cloneDirectory, SpecmaticConfigV1V2Common(), systemGit, jGitCloneCommand)

        verify(exactly = 1) { systemGit.clone(gitRepositoryURI, cloneDirectory) }
        verify(exactly = 1) { jGitCloneCommand.execute(any(), any(), any()) }
    }

    @Test
    fun `orchestration clone should enrich auth related failures`(@TempDir tempDir: File) {
        val gitRepositoryURI = "https://github.com/specmatic/enterprise.git"
        val cloneDirectory = tempDir.resolve("enterprise")
        val systemGit = mockk<GitCommand>()
        val jGitCloneCommand = mockk<JGitCloneCmd>()
        val originalMessage =
            """
            remote: Write access to repository not granted.
            fatal: unable to access '$gitRepositoryURI/': The requested URL returned error: 403
            """.trimIndent()

        every { systemGit.clone(gitRepositoryURI, cloneDirectory) } throws RuntimeException("system git failed")
        every { jGitCloneCommand.execute(any(), any(), any()) } throws RuntimeException(originalMessage)

        val exception = assertThrows<ContractException> {
            clone(gitRepositoryURI, cloneDirectory, SpecmaticConfigV1V2Common(), systemGit, jGitCloneCommand)
        }

        assertThat(exception.message).contains("Failed to read contract repository $gitRepositoryURI")
        assertThat(exception.message).contains("Specmatic only needs read access to load contracts.")
        assertThat(exception.message).contains("Write access to repository not granted")
        assertThat(exception.message).contains("The requested URL returned error: 403")
    }

    @Test
    fun `orchestration clone should preserve non auth failure messages`(@TempDir tempDir: File) {
        val gitRepositoryURI = "https://github.com/specmatic/enterprise.git"
        val cloneDirectory = tempDir.resolve("enterprise")
        val systemGit = mockk<GitCommand>()
        val jGitCloneCommand = mockk<JGitCloneCmd>()

        every { systemGit.clone(gitRepositoryURI, cloneDirectory) } throws RuntimeException("system git failed")
        every { jGitCloneCommand.execute(any(), any(), any()) } throws RuntimeException("repository unavailable")

        val exception = assertThrows<ContractException> {
            clone(gitRepositoryURI, cloneDirectory, SpecmaticConfigV1V2Common(), systemGit, jGitCloneCommand)
        }

        assertThat(exception.message).isEqualTo("repository unavailable")
        assertThat(exception.message).doesNotContain("Specmatic only needs read access")
    }

    @Test
    fun `orchestration clone should use cause message when throwable message is null`(@TempDir tempDir: File) {
        val gitRepositoryURI = "https://github.com/specmatic/enterprise.git"
        val cloneDirectory = tempDir.resolve("enterprise")
        val systemGit = mockk<GitCommand>()
        val jGitCloneCommand = mockk<JGitCloneCmd>()

        every { systemGit.clone(gitRepositoryURI, cloneDirectory) } throws RuntimeException("system git failed")
        every { jGitCloneCommand.execute(any(), any(), any()) } throws RuntimeException(null, IllegalStateException("deep cause"))

        val exception = assertThrows<ContractException> {
            clone(gitRepositoryURI, cloneDirectory, SpecmaticConfigV1V2Common(), systemGit, jGitCloneCommand)
        }

        assertThat(exception.message).isEqualTo("deep cause")
    }

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
