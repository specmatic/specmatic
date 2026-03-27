package io.specmatic.core.git

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SystemGitTest {
    @Test
    fun `workingDirectoryIsGitRepo should return true for a git repository`(@TempDir tempDir: File) {
        initialiseGitRepository(tempDir)

        assertThat(SystemGit(tempDir.absolutePath).workingDirectoryIsGitRepo()).isTrue()
    }

    @Test
    fun `currentRemoteBranch should return current branch when upstream is not configured`(@TempDir tempDir: File) {
        initialiseGitRepository(tempDir)
        commitFile(tempDir, "contract.spec", "openapi: 3.0.0")

        val systemGit = SystemGit(tempDir.absolutePath)
        assertThat(systemGit.currentRemoteBranch()).isEqualTo("main")
    }

    @Test
    fun `getChangedFiles should return repository relative paths for modified files`(@TempDir tempDir: File) {
        initialiseGitRepository(tempDir)
        val contractFile = tempDir.resolve("apis").resolve("contract.spec")
        commitFile(tempDir, "apis/contract.spec", "openapi: 3.0.0")

        contractFile.writeText("openapi: 3.0.1")

        val systemGit = SystemGit(tempDir.absolutePath)
        assertThat(systemGit.getChangedFiles()).containsExactly("apis/contract.spec")
    }

    @Test
    fun `getUntrackedFiles should return absolute paths for untracked files`(@TempDir tempDir: File) {
        initialiseGitRepository(tempDir)
        val untrackedFile = tempDir.resolve("apis").resolve("new-contract.spec")
        untrackedFile.parentFile.mkdirs()
        untrackedFile.writeText("openapi: 3.0.0")

        val systemGit = SystemGit(tempDir.absolutePath)
        assertThat(systemGit.getUntrackedFiles()).containsExactly(untrackedFile.absolutePath)
    }

    private fun initialiseGitRepository(directory: File) {
        runGit(directory, "init", "-b", "main")
        runGit(directory, "config", "user.name", "Specmatic Tests")
        runGit(directory, "config", "user.email", "specmatic-tests@example.com")
    }

    private fun commitFile(directory: File, relativePath: String, contents: String) {
        val file = directory.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(contents)

        runGit(directory, "add", relativePath)
        runGit(directory, "commit", "-m", "Add $relativePath")
    }

    private fun runGit(directory: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args.toList())
            .directory(directory)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        check(exitCode == 0) {
            "git ${args.joinToString(" ")} failed with exit code $exitCode\n$output"
        }

        return output
    }
}
