package application

import application.validate.ConfigBackedSpecificationLoader
import application.validate.ExampleValidationResult
import application.validate.SpecValidationResult
import application.validate.ValidateCommand
import application.validate.Validator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.core.utilities.GitRepo
import io.specmatic.loader.OpenApiSpecCompatibilityChecker
import io.specmatic.loader.RecursiveSpecificationAndExampleClassifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.File

class ValidateCommandTest {
    @AfterEach
    fun cleanup() {
        System.clearProperty(CONFIG_FILE_PATH)
    }

    @Test
    fun `when validate runs without args it validates scanned and config filesystem specs`(@TempDir tempDir: File) {
        val scannedSpec = writeOpenApiFile(tempDir.resolve("scanned/pets.yaml"))
        val systemSpec = writeOpenApiFile(tempDir.resolve("contracts/service/service.yaml"))
        val dependencySpec = writeOpenApiFile(tempDir.resolve("contracts/dependencies/dependency.yaml"))
        writeSpecmaticYaml(tempDir, """
            version: 3
            systemUnderTest:
              service:
                definitions:
                - definition:
                    source:
                      filesystem:
                        directory: contracts/service
                    specs:
                    - service.yaml
            dependencies:
              services:
              - service:
                  definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: contracts/dependencies
                      specs:
                      - dependency.yaml
        """.trimIndent())

        val validator = TrackingValidator()
        val exitCode = CommandLine(commandWithCurrentConfig(tempDir, validator)).execute()

        assertThat(exitCode).isZero()
        assertThat(validator.validatedSpecifications).containsExactlyInAnyOrder(
            scannedSpec.canonicalPath,
            systemSpec.canonicalPath,
            dependencySpec.canonicalPath
        )
    }

    @Test
    fun `when same spec is in scan and config it is validated once`(@TempDir tempDir: File) {
        val sharedSpec = writeOpenApiFile(tempDir.resolve("contracts/shared.yaml"))
        writeSpecmaticYaml(tempDir, """
            version: 3
            systemUnderTest:
              service:
                definitions:
                - definition:
                    source:
                      filesystem:
                        directory: contracts
                    specs:
                    - shared.yaml
            dependencies:
              services:
              - service:
                  definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: contracts
                      specs:
                      - shared.yaml
        """.trimIndent())

        val validator = TrackingValidator()
        val exitCode = CommandLine(commandWithCurrentConfig(tempDir, validator)).execute()

        assertThat(exitCode).isZero()
        assertThat(validator.validatedSpecifications).containsExactly(sharedSpec.canonicalPath)
    }

    @Test
    fun `when validate scans directories it omits specs under dot specmatic`(@TempDir tempDir: File) {
        val scannedSpec = writeOpenApiFile(tempDir.resolve("contracts/kept.yaml"))
        writeOpenApiFile(tempDir.resolve(".specmatic/repos/ignored.yaml"))

        val validator = TrackingValidator()
        val exitCode = CommandLine(commandWithCurrentConfig(tempDir, validator)).execute()

        assertThat(exitCode).isZero()
        assertThat(validator.validatedSpecifications).containsExactly(scannedSpec.canonicalPath)
    }

    @Test
    fun `when config references spec under dot specmatic validate still includes it`(@TempDir tempDir: File) {
        val scannedSpec = writeOpenApiFile(tempDir.resolve("contracts/kept.yaml"))
        val configSpec = writeOpenApiFile(tempDir.resolve(".specmatic/repos/service.yaml"))
        writeSpecmaticYaml(tempDir, "version: 3")
        val configBackedLoader = mockk<ConfigBackedSpecificationLoader>()
        every { configBackedLoader.load() } returns listOf(
            RecursiveSpecificationAndExampleClassifier(loadedConfig(tempDir), OpenApiSpecCompatibilityChecker())
                .load(configSpec, tempDir)
                ?: error("Expected config spec to be loadable")
        )
        val validator = TrackingValidator()
        val exitCode = CommandLine(
            ValidateCommand(
                validator = validator,
                specmaticConfig = loadedConfig(tempDir),
                configBackedSpecificationLoader = configBackedLoader,
                currentDirectoryProvider = { tempDir.canonicalFile }
            )
        ).execute()

        assertThat(exitCode).isZero()
        assertThat(validator.validatedSpecifications).containsExactlyInAnyOrder(
            scannedSpec.canonicalPath,
            configSpec.canonicalPath
        )
        verify(exactly = 1) { configBackedLoader.load() }
    }

    @Test
    fun `when config derived spec fails validate returns non zero and mentions the spec`(@TempDir tempDir: File) {
        val failingSpec = writeOpenApiFile(tempDir.resolve("contracts/dependencies/broken.yaml"))
        writeSpecmaticYaml(tempDir, """
            version: 3
            dependencies:
              services:
              - service:
                  definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: contracts/dependencies
                      specs:
                      - broken.yaml
        """.trimIndent())

        val validator = TrackingValidator(invalidSpecifications = setOf(failingSpec.canonicalPath))
        val (output, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
            CommandLine(commandWithCurrentConfig(tempDir, validator)).execute()
        }

        assertThat(exitCode).isEqualTo(1)
        assertThat(output).contains(failingSpec.canonicalPath)
    }

    @Test
    fun `when no config file exists validate falls back to scanning current directory`(@TempDir tempDir: File) {
        val scannedSpec = writeOpenApiFile(tempDir.resolve("fallback/only-scanned.yaml"))

        val validator = TrackingValidator()
        val exitCode = CommandLine(
            ValidateCommand(
                validator = validator,
                currentDirectoryProvider = { tempDir.canonicalFile }
            )
        ).execute()

        assertThat(exitCode).isZero()
        assertThat(validator.validatedSpecifications).containsExactly(scannedSpec.canonicalPath)
    }

    @Test
    fun `config backed specification loader resolves non filesystem specs via contract sources`(@TempDir tempDir: File) {
        val downloadedSpec = writeOpenApiFile(tempDir.resolve("downloads/remote.yaml"))
        writeSpecmaticYaml(tempDir, "version: 3")
        val classifier = RecursiveSpecificationAndExampleClassifier(loadedConfig(tempDir), OpenApiSpecCompatibilityChecker())
        val remoteSource = mockk<GitRepo>()
        every { remoteSource.loadContracts(any(), any(), any()) } returns listOf(
            io.specmatic.core.utilities.ContractPathData(
                baseDir = tempDir.canonicalPath,
                path = downloadedSpec.canonicalPath,
                provider = "git",
                specificationPath = "apis/remote.yaml"
            )
        )
        every { remoteSource.pathDescriptor("apis/remote.yaml") } returns "remote-repo:apis/remote.yaml"
        val loader = ConfigBackedSpecificationLoader(
            specmaticConfig = loadedConfig(tempDir),
            classifier = classifier,
            loadSources = {
                listOf(
                    remoteSource
                )
            },
            configFileProvider = { tempDir.resolve("specmatic.yaml") }
        )

        val specifications = loader.load()

        verify {
            remoteSource.loadContracts(
                any(),
                tempDir.resolve(".specmatic").canonicalPath,
                tempDir.resolve("specmatic.yaml").canonicalPath
            )
        }
        assertThat(specifications.map { it.specFile.canonicalPath }).containsExactly(downloadedSpec.canonicalPath)
    }

    @Test
    fun `when config derived spec cannot be parsed validate returns non zero`(@TempDir tempDir: File) {
        val malformedSpec = tempDir.resolve("contracts/dependencies/broken.yaml")
        malformedSpec.parentFile.mkdirs()
        malformedSpec.writeText(
            """
            openapi: 3.0.1
            info:
              title: Broken API
              version: "1"
            paths:
              /pets:
                get:
                  responses:
                    '200'
                      description: OK
            """.trimIndent()
        )
        writeSpecmaticYaml(tempDir, """
            version: 3
            dependencies:
              services:
              - service:
                  definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: contracts/dependencies
                      specs:
                      - broken.yaml
        """.trimIndent())

        val (output, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
            CommandLine(commandWithCurrentConfig(tempDir, TrackingValidator())).execute()
        }

        assertThat(exitCode).isEqualTo(1)
        assertThat(output).contains("Failed to parse 1 specification(s):")
        assertThat(output).contains(malformedSpec.canonicalPath)
    }

    @Test
    fun `when malformed spec exists only under dot specmatic scan does not report it`(@TempDir tempDir: File) {
        val scannedSpec = writeOpenApiFile(tempDir.resolve("contracts/kept.yaml"))
        val malformedSpec = tempDir.resolve(".specmatic/repos/broken.yaml")
        malformedSpec.parentFile.mkdirs()
        malformedSpec.writeText(
            """
            openapi: 3.0.1
            info:
              title: Broken API
              version: "1"
            paths:
              /pets:
                get:
                  responses:
                    '200'
                      description: OK
            """.trimIndent()
        )

        val validator = TrackingValidator()
        val (output, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
            CommandLine(commandWithCurrentConfig(tempDir, validator)).execute()
        }

        assertThat(exitCode).isZero()
        assertThat(output).doesNotContain("Failed to parse 1 specification(s):")
        assertThat(output).doesNotContain(malformedSpec.canonicalPath)
        assertThat(validator.validatedSpecifications).containsExactly(scannedSpec.canonicalPath)
    }

    private fun commandWithCurrentConfig(baseDir: File, validator: TrackingValidator): ValidateCommand {
        return ValidateCommand(
            validator = validator,
            specmaticConfig = loadedConfig(baseDir),
            currentDirectoryProvider = { baseDir.canonicalFile }
        )
    }

    private fun loadedConfig(baseDir: File): io.specmatic.core.SpecmaticConfig {
        val configFile = baseDir.resolve("specmatic.yaml")
        System.setProperty(CONFIG_FILE_PATH, configFile.canonicalPath)
        return io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault(configFile.canonicalPath)
    }

    private fun writeSpecmaticYaml(dir: File, content: String): File {
        return dir.resolve("specmatic.yaml").also { it.writeText(content) }
    }

    private fun writeOpenApiFile(file: File): File {
        file.parentFile.mkdirs()
        file.writeText(
            """
            openapi: 3.0.1
            info:
              title: Test API
              version: "1"
            paths: {}
            """.trimIndent()
        )
        return file
    }

    private class TrackingValidator(
        private val invalidSpecifications: Set<String> = emptySet()
    ) : Validator<String> {
        val validatedSpecifications = mutableListOf<String>()

        override fun validateSpecification(specification: File, specmaticConfig: SpecmaticConfig): SpecValidationResult<String> {
            val canonicalPath = specification.canonicalPath
            validatedSpecifications.add(canonicalPath)
            val result = if (canonicalPath in invalidSpecifications) Result.Failure("Invalid specification") else Result.Success()
            return SpecValidationResult.ValidationResult(canonicalPath, result)
        }

        override fun validateInlineExamples(specification: File, feature: String, specmaticConfig: SpecmaticConfig): Map<String, ExampleValidationResult> {
            return emptyMap()
        }

        override fun validateExample(feature: String, file: File, specmaticConfig: SpecmaticConfig): ExampleValidationResult {
            return ExampleValidationResult.ValidationResult(file, Result.Success())
        }

        override fun validateExamples(feature: String, files: List<File>, specmaticConfig: SpecmaticConfig): Result {
            return Result.Success()
        }
    }
}
