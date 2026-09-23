package application.backwardCompatibility

import application.captureStandardOutput
import io.specmatic.core.IFeature
import io.specmatic.core.Results
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import picocli.CommandLine
import java.io.File

class BackwardCompatibilityCheckBaseCommandTest {
    @Test
    fun `uses backward compatibility config defaults when cli args are absent`(@TempDir tempDir: File) {
        val repoDir = tempDir.resolve("repo")
        val configFile = writeSpecmaticYaml(tempDir,content = """
        version: 2
        backwardCompatibility:
          repoDirectory: $repoDir
          baseBranch: origin/main
          targetPath: contracts
          strictMode: true
        """.trimIndent())

        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            val cmd = TestBackwardCompatibilityCommand()
            assertThat(cmd.repoDir()).isEqualTo(repoDir.toString())
            assertThat(cmd.baseBranch()).isEqualTo("origin/main")
            assertThat(cmd.targetPath()).isEqualTo("contracts")
            assertThat(cmd.strictMode()).isTrue()
        }
    }

    @Test
    fun `cli arguments override specmatic config`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticYaml(tempDir, content = """
        version: 2
        backwardCompatibility:
          repoDirectory: ${tempDir.canonicalPath}
          baseBranch: origin/main
          targetPath: contracts
          strictMode: false
        """.trimIndent())

        val cmd = Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            TestBackwardCompatibilityCommand().apply {
                options.baseBranch = "feature/foo"
                options.targetPath = "apis"
                options.repoDir = tempDir.resolve("CLI").also { it.mkdirs() }.canonicalPath
                options.strictMode = true
            }
        }

        assertThat(cmd.repoDir()).isEqualTo(tempDir.resolve("CLI").canonicalPath.toString())
        assertThat(cmd.baseBranch()).isEqualTo("feature/foo")
        assertThat(cmd.targetPath()).isEqualTo("apis")
        assertThat(cmd.strictMode()).isTrue()
    }

    @Test
    fun `falls back to hard defaults when neither cli nor config is provided`() {
        val cmd = TestBackwardCompatibilityCommand()
        assertThat(cmd.repoDir()).isEqualTo(".")
        assertThat(cmd.targetPath()).isEqualTo("")
        assertThat(cmd.strictMode()).isFalse()
        assertThat(cmd.baseBranch()).isNotBlank
    }

    @Test
    fun `falls back only for missing backward compatibility config fields`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticYaml(tempDir, content = """
        version: 2
        backwardCompatibility:
          baseBranch: origin/develop
        """.trimIndent())

        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            val cmd = TestBackwardCompatibilityCommand()
            assertThat(cmd.baseBranch()).isEqualTo("origin/develop")
            assertThat(cmd.repoDir()).isEqualTo(".")
            assertThat(cmd.targetPath()).isEqualTo("")
            assertThat(cmd.strictMode()).isFalse()
        }
    }

    @Test
    fun `cli overrides config while config fills missing values`(@TempDir tempDir: File) {
        val repoDirFromConfig = tempDir.resolve("from-config").apply { mkdirs() }

        val configFile = writeSpecmaticYaml(tempDir, content = """
        version: 2
        backwardCompatibility:
          repoDirectory: ${repoDirFromConfig.canonicalPath}
          baseBranch: origin/main
          strictMode: true
        """.trimIndent())

        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            val cmd = TestBackwardCompatibilityCommand().apply { options.targetPath = "from-cli" }
            assertThat(cmd.repoDir()).isEqualTo(repoDirFromConfig.canonicalPath.toString())
            assertThat(cmd.baseBranch()).isEqualTo("origin/main")
            assertThat(cmd.targetPath()).isEqualTo("from-cli")
            assertThat(cmd.strictMode()).isTrue()
        }
    }

    @Test
    fun `parses backward compatibility options from cli`(@TempDir tempDir: File) {
        val cmd = TestBackwardCompatibilityCommand()
        CommandLine(cmd).parseArgs(
            "--debug",
            "--strict",
            "--base-branch", "feature/foo",
            "--target-path", "contracts",
            "--repo-dir", tempDir.canonicalPath
        )

        assertThat(cmd.options.debugLog).isTrue()
        assertThat(cmd.options.strictMode).isTrue()
        assertThat(cmd.options.baseBranch).isEqualTo("feature/foo")
        assertThat(cmd.options.targetPath).isEqualTo("contracts")
        assertThat(cmd.options.repoDir).isEqualTo(tempDir.canonicalPath)
        assertThat(cmd.repoDir()).isEqualTo(tempDir.canonicalPath)
        assertThat(cmd.baseBranch()).isEqualTo("feature/foo")
        assertThat(cmd.targetPath()).isEqualTo("contracts")
        assertThat(cmd.strictMode()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `load failures identify the spec side path and branch`(failingLoad: Int, @TempDir tempDir: File) {
        val repoDir = initializeGitRepo(tempDir)
        val specFile = repoDir.resolve("contract.yaml").canonicalPath
        val command = LoadFailingBackwardCompatibilityCommand(failingLoad).apply {
            options.repoDir = repoDir.canonicalPath
            options.baseBranch = "main"
        }

        val (output, exitCode) = captureStandardOutput { command.call() }

        assertThat(exitCode).isEqualTo(1)
        assertThat(output).contains("synthetic parse failure")
        when (failingLoad) {
            1 -> assertThat(output)
                .contains("Loading newer specification '$specFile' from branch 'feature'")
                .contains("Failed to load newer specification '$specFile' from branch 'feature'")
            2 -> assertThat(output)
                .contains("Loading newer specification '$specFile' from branch 'feature'")
                .contains("Loading older specification '$specFile' from branch 'main'")
                .contains("Failed to load older specification '$specFile' from branch 'main'")
        }
    }

    private fun initializeGitRepo(tempDir: File): File {
        val repoDir = tempDir.resolve("repo").apply { mkdirs() }
        fun git(vararg args: String) {
            val process = ProcessBuilder("git", *args).directory(repoDir).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
        }

        git("init")
        git("symbolic-ref", "HEAD", "refs/heads/main")
        git("config", "--local", "user.name", "developer")
        git("config", "--local", "user.email", "developer@example.com")
        repoDir.resolve("contract.yaml").writeText("main version")
        git("add", "contract.yaml")
        git("commit", "-m", "initial")
        git("switch", "-c", "feature")
        repoDir.resolve("contract.yaml").writeText("feature version")
        git("add", "contract.yaml")
        git("commit", "-m", "change spec")
        return repoDir
    }

    fun writeSpecmaticYaml(dir: File, content: String): File = dir.resolve("specmatic.yaml").also { it.writeText(content) }
    class TestBackwardCompatibilityCommand : BackwardCompatibilityCheckBaseCommand() {
        fun repoDir() = effectiveRepoDir
        fun baseBranch() = effectiveBaseBranch
        fun targetPath() = effectiveTargetPath
        fun strictMode() = effectiveStrictMode
        fun git() = gitCommand

        override fun checkBackwardCompatibility(oldFeature: IFeature, newFeature: IFeature): BackwardCompatibilityCheckResult {
            TODO("Not yet implemented")
        }

        override fun File.isValidFileFormat(): Boolean {
            TODO("Not yet implemented")
        }

        override fun File.isValidSpec(): Boolean {
            TODO("Not yet implemented")
        }

        override fun File.isExternalisedExample(): Boolean {
            TODO("Not yet implemented")
        }

        override fun getFeatureFromSpecPath(path: String): IFeature {
            TODO("Not yet implemented")
        }

        override fun getSpecsOfChangedExternalisedExamples(filesChangedInCurrentBranch: Set<String>): Set<String> {
            TODO("Not yet implemented")
        }
    }

    private class LoadFailingBackwardCompatibilityCommand(private val failingLoad: Int) : BackwardCompatibilityCheckBaseCommand() {
        private var loadCount = 0

        override fun checkBackwardCompatibility(oldFeature: IFeature, newFeature: IFeature) = BackwardCompatibilityCheckResult(Results())

        override fun File.isValidFileFormat(): Boolean = extension == "yaml"

        override fun File.isValidSpec(): Boolean = isFile && extension == "yaml"

        override fun File.isExternalisedExample(): Boolean = false

        override fun getFeatureFromSpecPath(path: String): IFeature {
            loadCount++
            if (loadCount == failingLoad) throw IllegalStateException("synthetic parse failure")
            return object : IFeature {}
        }

        override fun getSpecsReferringTo(specFilePaths: Set<String>): Set<String> = emptySet()

        override fun getSpecsOfChangedExternalisedExamples(filesChangedInCurrentBranch: Set<String>): Set<String> = emptySet()
    }
}
