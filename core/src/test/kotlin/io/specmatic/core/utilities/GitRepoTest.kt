package io.specmatic.core.utilities

import io.mockk.*
import io.specmatic.core.git.GitCommand
import io.specmatic.core.git.checkout
import io.specmatic.core.git.clone
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GitRepoTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            mockkStatic("io.specmatic.core.git.GitOperations")
            mockkStatic("io.specmatic.core.utilities.Utilities")
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            unmockkAll()
        }
    }

    @AfterEach
    fun clearMocks() {
        clearAllMocks()
    }

    @Test
    fun `should reset the directory and checkout the correct branch if directory on the wrong branch`(@TempDir tempDir: File) {
        tempDir.resolve("repos").resolve("specmatic").mkdirs()
        val fakeGit = mockk<GitCommand>()
        every { fakeGit.currentBranch() } returns "feature"

        every { getSystemGit(any()) } returns fakeGit
        every { clone(any(), any()) } returns tempDir
        every { checkout(any(), any()) } returns Unit

        val gitRepo = GitRepo(
            gitRepositoryURL = "https://github.com/specmatic/specmatic.git",
            branchName = "main",
            testContracts = emptyList(),
            stubContracts = emptyList(),
            type = null
        )
        gitRepo.loadContracts(workingDirectory = tempDir.canonicalPath, configFilePath = "", selector = { it.stubContracts })

        verify(exactly = 1) { clone(tempDir.canonicalFile.resolve("repos"), gitRepo) }
        verify(exactly = 1) { checkout(tempDir,"main") }
    }

    @Test
    fun `should reset when branch does not match origin default while config branch is not set`(@TempDir tempDir: File) {
        tempDir.resolve("repos").resolve("specmatic").mkdirs()
        val fakeGit = mockk<GitCommand>()
        every { fakeGit.currentBranch() } returns "feature"
        every { fakeGit.getOriginDefaultBranchName() } returns "main"

        every { getSystemGit(any()) } returns fakeGit
        every { clone(any(), any()) } returns tempDir
        every { checkout(any(), any()) } returns Unit

        val gitRepo = GitRepo(
            gitRepositoryURL = "https://github.com/specmatic/specmatic.git",
            branchName = null,
            testContracts = emptyList(),
            stubContracts = emptyList(),
            type = null
        )
        gitRepo.loadContracts(workingDirectory = tempDir.canonicalPath, configFilePath = "", selector = { it.stubContracts })

        verify(exactly = 1) { clone(tempDir.canonicalFile.resolve("repos"), gitRepo) }
        verify(exactly = 0) { checkout(tempDir,"main") }
    }

    @Test
    fun `should not re-clone when current branch matches origin default while config branch is not set`(@TempDir tempDir: File) {
        tempDir.resolve("repos").resolve("specmatic").mkdirs()
        val fakeGit = mockk<GitCommand>()
        every { fakeGit.currentBranch() } returns "main"
        every { fakeGit.getOriginDefaultBranchName() } returns "main"
        every { fakeGit.fetch() } returns ""
        every { fakeGit.revisionsBehindCount() } returns 0
        every { fakeGit.statusPorcelain() } returns ""
        every { fakeGit.currentRemoteBranch() } returns "origin/main"

        every { getSystemGit(any()) } returns fakeGit
        every { getSystemGitWithAuth(any()) } returns fakeGit
        every { clone(any(), any()) } returns tempDir
        every { checkout(any(), any()) } returns Unit

        val gitRepo = GitRepo(
            gitRepositoryURL = "https://github.com/specmatic/specmatic.git",
            branchName = null,
            testContracts = emptyList(),
            stubContracts = emptyList(),
            type = null
        )
        val (stdOut, _) = captureStandardOutput {
            gitRepo.loadContracts(workingDirectory = tempDir.canonicalPath, configFilePath = "", selector = { it.stubContracts })
        }

        verify(exactly = 0) { clone(tempDir.resolve("repos"), gitRepo) }
        verify(exactly = 0) { checkout(tempDir,"main") }
        assertThat(stdOut).containsIgnoringWhitespaces("Contract repo exists, is clean, and is up to date with remote.")
    }

    @Test
    fun `should treat repo as up to date when upstream is missing`(@TempDir tempDir: File) {
        tempDir.resolve("repos").resolve("specmatic").mkdirs()
        val fakeGit = mockk<GitCommand>()
        every { fakeGit.currentBranch() } returns "feature/match-branch"
        every { fakeGit.getOriginDefaultBranchName() } returns "feature/match-branch"
        every { fakeGit.currentRemoteBranch() } returns "feature/match-branch"
        every { fakeGit.statusPorcelain() } returns ""

        every { getSystemGit(any()) } returns fakeGit
        every { getSystemGitWithAuth(any()) } returns fakeGit
        every { clone(any(), any()) } returns tempDir
        every { checkout(any(), any()) } returns Unit

        val gitRepo = GitRepo(
            gitRepositoryURL = "https://github.com/specmatic/specmatic.git",
            branchName = null,
            testContracts = emptyList(),
            stubContracts = emptyList(),
            type = null
        )

        gitRepo.loadContracts(workingDirectory = tempDir.canonicalPath, configFilePath = "", selector = { it.stubContracts })

        verify(exactly = 0) { fakeGit.fetch() }
        verify(exactly = 0) { fakeGit.revisionsBehindCount() }
    }
}