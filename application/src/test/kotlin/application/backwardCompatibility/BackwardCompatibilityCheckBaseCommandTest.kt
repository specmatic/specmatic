package application.backwardCompatibility

import io.specmatic.core.IFeature
import io.specmatic.core.Results
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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

        val cmd = Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) { TestBackwardCompatibilityCommand() }
        assertThat(cmd.repoDir()).isEqualTo(repoDir.toString())
        assertThat(cmd.baseBranch()).isEqualTo("origin/main")
        assertThat(cmd.targetPath()).isEqualTo("contracts")
        assertThat(cmd.strictMode()).isTrue()
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
                baseBranch = "feature/foo"
                targetPath = "apis"
                repoDir = tempDir.resolve("CLI").also { it.mkdirs() }.canonicalPath
                strictMode = true
            }
        }

        assertThat(cmd.repoDir()).isEqualTo(tempDir.resolve("CLI").toString())
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

        val cmd = Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) { TestBackwardCompatibilityCommand() }
        assertThat(cmd.baseBranch()).isEqualTo("origin/develop")
        assertThat(cmd.repoDir()).isEqualTo(".")
        assertThat(cmd.targetPath()).isEqualTo("")
        assertThat(cmd.strictMode()).isFalse()
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

        val cmd = Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            TestBackwardCompatibilityCommand().apply { targetPath = "from-cli" }
        }

        assertThat(cmd.repoDir()).isEqualTo(repoDirFromConfig.toString())
        assertThat(cmd.baseBranch()).isEqualTo("origin/main")
        assertThat(cmd.targetPath()).isEqualTo("from-cli")
        assertThat(cmd.strictMode()).isTrue()
    }

    fun writeSpecmaticYaml(dir: File, content: String): File = dir.resolve("specmatic.yaml").also { it.writeText(content) }
    class TestBackwardCompatibilityCommand : BackwardCompatibilityCheckBaseCommand() {
        fun repoDir() = effectiveRepoDir
        fun baseBranch() = effectiveBaseBranch
        fun targetPath() = effectiveTargetPath
        fun strictMode() = effectiveStrictMode
        fun git() = gitCommand

        override fun checkBackwardCompatibility(oldFeature: IFeature, newFeature: IFeature): Results {
            TODO("Not yet implemented")
        }

        override fun File.isValidFileFormat(): Boolean {
            TODO("Not yet implemented")
        }

        override fun File.isValidSpec(): Boolean {
            TODO("Not yet implemented")
        }

        override fun getFeatureFromSpecPath(path: String): IFeature {
            TODO("Not yet implemented")
        }

        override fun getSpecsOfChangedExternalisedExamples(filesChangedInCurrentBranch: Set<String>): Set<String> {
            TODO("Not yet implemented")
        }
    }
}
