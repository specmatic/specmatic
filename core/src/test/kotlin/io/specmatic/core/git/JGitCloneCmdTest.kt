package io.specmatic.core.git

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.specmatic.core.Auth
import io.specmatic.core.SpecmaticConfigV1V2Common
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JGitCloneCmdTest {
    @Test
    fun `should configure clone command with evaluated URI and target directory`(@TempDir tempDir: File) {
        val cloneCommand = cloneCommand()
        val originalConnectionFactory = mockk<HttpConnectionFactory>()
        val transportManager = FakeHttpTransportFactoryManager(originalConnectionFactory)
        val cloneDirectory = tempDir.resolve("clone-target")

        JGitCloneCmd(
            cloneCommandFactory = { cloneCommand },
            environmentVariables = mapOf("CI_JOB_TOKEN" to "secret"),
            httpTransportFactoryManager = transportManager
        ).execute(
            gitRepositoryURI = "https://gitlab-ci-token:${'$'}{CI_JOB_TOKEN}@gitlab.com/group/project.git",
            cloneDirectory = cloneDirectory,
            specmaticConfig = SpecmaticConfigV1V2Common()
        )

        verify(exactly = 1) { cloneCommand.setURI("https://gitlab-ci-token:secret@gitlab.com/group/project.git") }
        verify(exactly = 1) { cloneCommand.setDirectory(cloneDirectory) }
        verify(exactly = 1) { cloneCommand.setTransportConfigCallback(any()) }
        verify(exactly = 1) { cloneCommand.call() }
        assertThat(transportManager.setFactories.first()).isInstanceOf(InsecureHttpConnectionFactory::class.java)
        assertThat(transportManager.setFactories.last()).isSameAs(originalConnectionFactory)
    }

    @Test
    fun `should apply personal access token credentials provider when configured`(@TempDir tempDir: File) {
        val cloneCommand = cloneCommand()

        JGitCloneCmd(
            cloneCommandFactory = { cloneCommand },
            httpTransportFactoryManager = FakeHttpTransportFactoryManager(mockk())
        ).execute(
            gitRepositoryURI = "https://github.com/specmatic/enterprise.git",
            cloneDirectory = tempDir.resolve("clone-target"),
            specmaticConfig = SpecmaticConfigV1V2Common(auth = Auth(personalAccessToken = "token-123"))
        )

        verify(exactly = 1) { cloneCommand.setCredentialsProvider(any()) }
        verify(exactly = 1) { cloneCommand.setTransportConfigCallback(any()) }
    }

    @Test
    fun `should apply bearer transport callback when bearer file is configured`(@TempDir tempDir: File) {
        val cloneCommand = cloneCommand()
        val bearerFile = tempDir.resolve("bearer.txt").apply {
            writeText("bearer-token")
        }

        JGitCloneCmd(
            cloneCommandFactory = { cloneCommand },
            httpTransportFactoryManager = FakeHttpTransportFactoryManager(mockk())
        ).execute(
            gitRepositoryURI = "https://github.com/specmatic/enterprise.git",
            cloneDirectory = tempDir.resolve("clone-target"),
            specmaticConfig = SpecmaticConfigV1V2Common(auth = Auth(bearerFile = bearerFile.absolutePath))
        )

        verify(exactly = 2) { cloneCommand.setTransportConfigCallback(any()) }
        verify(exactly = 0) { cloneCommand.setCredentialsProvider(any()) }
    }

    @Test
    fun `should prefer personal access token over bearer file`(@TempDir tempDir: File) {
        val cloneCommand = cloneCommand()
        val bearerFile = tempDir.resolve("bearer.txt").apply {
            writeText("bearer-token")
        }

        JGitCloneCmd(
            cloneCommandFactory = { cloneCommand },
            httpTransportFactoryManager = FakeHttpTransportFactoryManager(mockk())
        ).execute(
            gitRepositoryURI = "https://github.com/specmatic/enterprise.git",
            cloneDirectory = tempDir.resolve("clone-target"),
            specmaticConfig = SpecmaticConfigV1V2Common(
                auth = Auth(
                    bearerFile = bearerFile.absolutePath,
                    personalAccessToken = "token-123"
                )
            )
        )

        verify(exactly = 1) { cloneCommand.setCredentialsProvider(any()) }
        verify(exactly = 1) { cloneCommand.setTransportConfigCallback(any()) }
    }

    @Test
    fun `should restore connection factory after clone failure`(@TempDir tempDir: File) {
        val cloneCommand = cloneCommand {
            throw RuntimeException("clone failed")
        }
        val originalConnectionFactory = mockk<HttpConnectionFactory>()
        val transportManager = FakeHttpTransportFactoryManager(originalConnectionFactory)

        val exception = assertThrows<RuntimeException> {
            JGitCloneCmd(
                cloneCommandFactory = { cloneCommand },
                httpTransportFactoryManager = transportManager
            ).execute(
                gitRepositoryURI = "https://github.com/specmatic/enterprise.git",
                cloneDirectory = tempDir.resolve("clone-target"),
                specmaticConfig = SpecmaticConfigV1V2Common()
            )
        }

        assertThat(exception.message).isEqualTo("clone failed")
        assertThat(transportManager.setFactories.first()).isInstanceOf(InsecureHttpConnectionFactory::class.java)
        assertThat(transportManager.setFactories.last()).isSameAs(originalConnectionFactory)
    }

    private fun cloneCommand(callAnswer: () -> Git = { mockk() }): CloneCommand {
        val cloneCommand = mockk<CloneCommand>()

        every { cloneCommand.setTransportConfigCallback(any()) } returns cloneCommand
        every { cloneCommand.setURI(any()) } returns cloneCommand
        every { cloneCommand.setDirectory(any()) } returns cloneCommand
        every { cloneCommand.setCredentialsProvider(any()) } returns cloneCommand
        every { cloneCommand.call() } answers { callAnswer() }

        return cloneCommand
    }

    private class FakeHttpTransportFactoryManager(
        private var currentConnectionFactory: HttpConnectionFactory
    ) : HttpTransportFactoryManager {
        val setFactories = mutableListOf<HttpConnectionFactory>()

        override fun getConnectionFactory(): HttpConnectionFactory = currentConnectionFactory

        override fun setConnectionFactory(connectionFactory: HttpConnectionFactory) {
            currentConnectionFactory = connectionFactory
            setFactories += connectionFactory
        }
    }
}
