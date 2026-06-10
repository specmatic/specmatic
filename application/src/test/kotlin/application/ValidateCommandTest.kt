package application

import application.validate.ConfigBackedSpecificationLoader
import application.validate.ExampleValidationResult
import application.validate.OpenApiValidator
import application.validate.SpecValidationResult
import application.validate.ValidateCommand
import application.validate.Validator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.specmatic.core.Result
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.QueryParameters
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.core.utilities.GitRepo
import io.specmatic.loader.OpenApiSpecCompatibilityChecker
import io.specmatic.loader.RecursiveSpecificationAndExampleClassifier
import io.specmatic.mock.ScenarioStub
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
    fun `when config derived spec cannot be parsed validate ignores it`(@TempDir tempDir: File) {
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

        assertThat(exitCode).isZero()
        assertThat(output).contains("No Tracking specifications found to validate.")
    }

    @Test
    fun `when no specs found validate command should mention validator name`(@TempDir tempDir: File) {
        writeSpecmaticYaml(tempDir, "version: 3")
        val (output, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
            CommandLine(
                ValidateCommand(validator = OpenApiValidator(), specmaticConfig = loadedConfig(tempDir), currentDirectoryProvider = { tempDir.canonicalFile })
            ).execute()
        }

        assertThat(exitCode).isZero()
        assertThat(output).contains("No OpenAPI specifications found to validate.")
    }

    @Test
    fun `validate command parses debug spec file and directory options`(@TempDir tempDir: File) {
        val specFile = writeOpenApiFile(tempDir.resolve("specs/pets.yaml"))
        val directorySpec = writeOpenApiFile(tempDir.resolve("contracts/orders.yaml"))
        writeSpecmaticYaml(tempDir, "version: 3")

        val specValidator = TrackingValidator()
        val specCommand = ValidateCommand(validator = specValidator, specmaticConfig = loadedConfig(tempDir), currentDirectoryProvider = { tempDir.canonicalFile })
        val specExitCode = CommandLine(specCommand).execute("--debug", "--spec-file", specFile.canonicalPath)

        val directoryValidator = TrackingValidator()
        val directoryCommand = ValidateCommand(validator = directoryValidator, specmaticConfig = loadedConfig(tempDir), currentDirectoryProvider = { tempDir.canonicalFile })
        val directoryExitCode = CommandLine(directoryCommand).execute("--debug", "--directory", tempDir.resolve("contracts").canonicalPath)

        assertThat(specExitCode).isZero()
        assertThat(specCommand.validateOptions.debug).isTrue()
        assertThat(specCommand.validateOptions.file?.canonicalPath).isEqualTo(specFile.canonicalPath)
        assertThat(specValidator.validatedSpecifications).containsExactly(specFile.canonicalPath)

        assertThat(directoryExitCode).isZero()
        assertThat(directoryCommand.validateOptions.debug).isTrue()
        assertThat(directoryCommand.validateOptions.directory?.canonicalPath).isEqualTo(tempDir.resolve("contracts").canonicalPath)
        assertThat(directoryValidator.validatedSpecifications).containsExactly(directorySpec.canonicalPath)
    }

    @Test
    fun `when validate is given a relative json spec file in current directory it does not fail`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("petstore.json")
        specFile.writeText(
            """
            {
              "openapi": "3.0.1",
              "info": {
                "title": "Test API",
                "version": "1"
              },
              "paths": {}
            }
            """.trimIndent()
        )

        val validator = TrackingValidator()
        val exitCode = CommandLine(
            ValidateCommand(
                validator = validator,
                currentDirectoryProvider = { tempDir.canonicalFile }
            )
        ).execute("--spec-file", "petstore.json")

        assertThat(exitCode).isZero()
        assertThat(validator.validatedSpecifications).containsExactly(specFile.canonicalPath)
    }

    @Test
    fun `validate command validates inline nested object query examples`(@TempDir tempDir: File) {
        val specFile = writeNestedObjectQueryOpenApiFile(
            file = tempDir.resolve("nested-object-query.yaml"),
            inlineExample = "price.min=50&price.max=150"
        )

        val exitCode = CommandLine(openApiValidateCommand(tempDir)).execute("--spec-file", specFile.canonicalPath)

        assertThat(exitCode).isZero()
    }

    @Test
    fun `validate command validates inline nested array query examples`(@TempDir tempDir: File) {
        val specFile = writeNestedArrayQueryOpenApiFile(
            file = tempDir.resolve("nested-array-query.yaml"),
            inlineExample = "variants[0].color=black&variants[0].sizes[0]=9"
        )

        val exitCode = CommandLine(openApiValidateCommand(tempDir)).execute("--spec-file", specFile.canonicalPath)

        assertThat(exitCode).isZero()
    }

    @Test
    fun `validate command validates external nested object query examples`(@TempDir tempDir: File) {
        val specFile = writeNestedObjectQueryOpenApiFile(tempDir.resolve("nested-object-query.yaml"))
        writeExternalExample(
            examplesDir = tempDir.resolve("nested-object-query_examples"),
            queryParams = mapOf(
                "price.min" to "50",
                "price.max" to "150"
            )
        )

        val exitCode = CommandLine(openApiValidateCommand(tempDir)).execute("--spec-file", specFile.canonicalPath)

        assertThat(exitCode).isZero()
    }

    @Test
    fun `validate command validates external nested array query examples`(@TempDir tempDir: File) {
        val specFile = writeNestedArrayQueryOpenApiFile(tempDir.resolve("nested-array-query.yaml"))
        writeExternalExample(
            examplesDir = tempDir.resolve("nested-array-query_examples"),
            queryParams = mapOf(
                "variants[0].color" to "black",
                "variants[0].sizes[0]" to "9"
            )
        )

        val exitCode = CommandLine(openApiValidateCommand(tempDir)).execute("--spec-file", specFile.canonicalPath)

        assertThat(exitCode).isZero()
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

    private fun openApiValidateCommand(baseDir: File): ValidateCommand {
        return ValidateCommand(
            validator = OpenApiValidator(),
            specmaticConfig = SpecmaticConfig(),
            currentDirectoryProvider = { baseDir.canonicalFile }
        )
    }

    private fun loadedConfig(baseDir: File): SpecmaticConfig {
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

    private fun writeNestedObjectQueryOpenApiFile(file: File, inlineExample: String? = null): File {
        return writeNestedQueryOpenApiFile(
            file = file,
            parameterSchema = """
                type: object
                required:
                  - price
                properties:
                  price:
                    type: object
                    required:
                      - min
                      - max
                    properties:
                      min:
                        type: string
                      max:
                        type: string
            """.trimIndent(),
            inlineExample = inlineExample
        )
    }

    private fun writeNestedArrayQueryOpenApiFile(file: File, inlineExample: String? = null): File {
        return writeNestedQueryOpenApiFile(
            file = file,
            parameterSchema = """
                type: object
                required:
                  - variants
                properties:
                  variants:
                    type: array
                    items:
                      type: object
                      required:
                        - color
                        - sizes
                      properties:
                        color:
                          type: string
                        sizes:
                          type: array
                          items:
                            type: string
            """.trimIndent(),
            inlineExample = inlineExample
        )
    }

    private fun writeNestedQueryOpenApiFile(file: File, parameterSchema: String, inlineExample: String?): File {
        val examples = if (inlineExample != null) {
            """
            examples:
              SUCCESS:
                value: $inlineExample
            """.trimIndent().prependIndent("                      ")
        } else {
            ""
        }

        file.parentFile.mkdirs()
        file.writeText(
            """
            openapi: 3.0.0
            info:
              title: Nested Query API
              version: "1"
            paths:
              /products/search:
                get:
                  parameters:
                    - in: query
                      name: filter
                      required: true
                      schema:
${parameterSchema.prependIndent("                        ")}
${examples}
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: integer
                          examples:
                            SUCCESS:
                              value:
                                id: 10
            """.trimIndent()
        )
        return file
    }

    private fun writeExternalExample(examplesDir: File, queryParams: Map<String, String>): File {
        examplesDir.mkdirs()
        return examplesDir.resolve("success.json").also { exampleFile ->
            exampleFile.writeText(
                ScenarioStub(
                    request = HttpRequest(
                        method = "GET",
                        path = "/products/search",
                        queryParams = QueryParameters(queryParams)
                    ),
                    response = HttpResponse(200, parsedJSONObject("""{"id": 10}"""))
                ).toJSON().toStringLiteral()
            )
        }
    }

    private class TrackingValidator(
        private val invalidSpecifications: Set<String> = emptySet()
    ) : Validator<String> {
        override val name: String = "Tracking"
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
