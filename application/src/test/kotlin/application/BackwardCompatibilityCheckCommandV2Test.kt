package application

import application.backwardCompatibility.BackwardCompatibilityCheckBaseCommand
import application.backwardCompatibility.BackwardCompatibilityCheckCommandV2
import application.backwardCompatibility.BackwardCompatibilityCheckHook
import application.backwardCompatibility.CompatibilityResult
import application.backwardCompatibility.ProcessedSpec
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.spyk
import io.specmatic.core.IFeature
import io.specmatic.core.Results
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.git.SystemGit
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.core.utilities.Flags.Companion.using
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicensedProduct
import io.specmatic.license.core.SpecmaticFeature
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.backwardcompat.dto.OperationUsageResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths

class BackwardCompatibilityCheckCommandV2Test {
    private val objectMapper = ObjectMapper()

    companion object {
        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Initialise the license up front so the license-loading log line does not
            // get interleaved into the console output captured by the tests.
            LicenseResolver.utilize(
                product = LicensedProduct.OPEN_SOURCE,
                feature = SpecmaticFeature.BACKWARD_COMPATIBILITY_CHECK,
                protocol = listOf(SpecmaticProtocol.HTTP)
            )
        }
    }

    @TempDir
    private lateinit var tempDir: File

    @TempDir
    private lateinit var remoteDir: File

    @BeforeEach
    fun setup() {
        ProcessBuilder("git", "init", "--bare")
            .directory(remoteDir)
            .inheritIO()
            .start()
            .waitFor()

        ProcessBuilder("git", "init")
            .directory(tempDir)
            .inheritIO()
            .start()
            .waitFor()

        ProcessBuilder("git", "symbolic-ref", "HEAD", "refs/heads/main").directory(tempDir).inheritIO().start().waitFor()

        ProcessBuilder("git", "config", "--local", "user.name", "developer")
            .directory(tempDir)
            .inheritIO()
            .start()
            .waitFor()

        ProcessBuilder("git", "config", "--local", "user.email", "developer@example.com")
            .directory(tempDir)
            .inheritIO()
            .start()
            .waitFor()

        ProcessBuilder("git", "remote", "add", "origin", remoteDir.absolutePath)
            .directory(tempDir)
            .inheritIO()
            .start()
            .waitFor()
    }

    @Nested
    inner class GetSpecsReferringToTests {
        @Test
        fun `getSpecsReferringTo returns empty set when input is empty`() {
            val command = BackwardCompatibilityCheckCommandV2()
            val result = command.getSpecsReferringTo(emptySet())
            assertTrue(result.isEmpty())
        }

        @Test
        fun `getSpecsReferringTo returns empty set when no files refer to changed schema files`() {
            val command = spyk<BackwardCompatibilityCheckCommandV2>()
            every { command.allSpecFiles() } returns listOf(
                File("file1.yaml").apply { writeText("content1") },
                File("file2.yaml").apply { writeText("content2") }
            )
            val result = command.getSpecsReferringTo(setOf("file3.yaml"))
            assertTrue(result.isEmpty())
        }

        @Test
        fun `getSpecsReferringTo returns set of files that refer to changed schema files`() {
            val command = spyk<BackwardCompatibilityCheckCommandV2>()
            every { command.allSpecFiles() } returns listOf(
                File("file1.yaml").apply { writeText("file3.yaml") },
                File("file2.yaml").apply { writeText("file4.yaml") }
            )
            val result = command.getSpecsReferringTo(setOf("file3.yaml"))
            assertEquals(setOf(File("file1.yaml").canonicalPath), result)
        }

        @Test
        fun `getSpecsReferringTo returns set of files which are referring to a changed schema that is one level down`() {
            val command = spyk<BackwardCompatibilityCheckCommandV2>()
            every { command.allSpecFiles() } returns listOf(
                File("file1.yaml").apply { referTo("schema_file1.yaml") },
                File("schema_file2.yaml").apply { referTo("schema_file1.yaml") }, // schema within a schema
                File("file2.yaml").apply { referTo("schema_file2.yaml") }
            )
            val result = command.getSpecsReferringTo(setOf("schema_file1.yaml"))
            assertEquals(
                setOf("file1.yaml", "schema_file2.yaml", "file2.yaml").map { File(it).canonicalPath }.toSet(), result
            )
        }

        @Test
        fun `getSpecsReferringTo should not hang if there is a circular dependency`() {
            val command = spyk<BackwardCompatibilityCheckCommandV2>()
            every { command.allSpecFiles() } returns listOf(
                File("a.yaml").apply { referTo("b.yaml") },
                File("b.yaml").apply { referTo("c.yaml") },
                File("c.yaml").apply { referTo("a.yaml") }
            )

            assertThat(command.getSpecsReferringTo(setOf("a.yaml"))).isEqualTo(
                setOf(
                    "b.yaml",
                    "c.yaml"
                ).map { File(it).canonicalPath }.toSet()
            )
            assertThat(command.getSpecsReferringTo(setOf("b.yaml"))).isEqualTo(
                setOf(
                    "c.yaml",
                    "a.yaml"
                ).map { File(it).canonicalPath }.toSet()
            )
            assertThat(command.getSpecsReferringTo(setOf("c.yaml"))).isEqualTo(
                setOf(
                    "a.yaml",
                    "b.yaml"
                ).map { File(it).canonicalPath }.toSet()
            )
        }

        @Test
        fun `should show message for untracked files`() {
            val apiFile = File("src/test/resources/specifications/spec_with_examples/api.yaml")
            apiFile.copyTo(tempDir.resolve("api.yaml"))
            commitAndPush(tempDir, "Initial commit")
            apiFile.copyTo(tempDir.resolve("contract.yaml"))

            val (stdOut, exitCode) = captureStandardOutput {
                BackwardCompatibilityCheckCommandV2().apply { options.repoDir = tempDir.canonicalPath }.call()
            }

            assertThat(exitCode).isEqualTo(0)
            assertThat(stdOut).containsIgnoringWhitespaces(
                """
            - Specs that will be skipped (untracked specs, or schema files that are not referred to in other specs):
            1. contract.yaml
            """.trimIndent()
            ).containsIgnoringWhitespaces(
                """
            Specs checked: 0 (Passed: 0, Failed: 0)
            Changed specs: 0
            Changed externalised example files: 0
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent()
            )
        }

        @Test
        fun `should include message for untracked files with changed files`() {
            val apiFile = File("src/test/resources/specifications/spec_with_examples/api.yaml").canonicalFile
            val gitApiFile = tempDir.resolve("api.yaml").canonicalFile
            apiFile.copyTo(gitApiFile)
            commitAndPush(tempDir, "Initial commit")
            gitApiFile.writeText(gitApiFile.readText().replace("endpoint", "modified endpoint"))
            apiFile.copyTo(tempDir.resolve("contract.yaml"))

            val (stdOut, exitCode) = captureStandardOutput {
                BackwardCompatibilityCheckCommandV2().apply { options.repoDir = tempDir.canonicalPath }.call()
            }

            assertThat(exitCode).isEqualTo(0)
            assertThat(stdOut).containsIgnoringWhitespaces(
                """
            - Specs that have changed: 
            1. api.yaml
            - Specs whose externalised examples will be validated:
            1. api.yaml (changed spec)
            - Specs that will be skipped (untracked specs, or schema files that are not referred to in other specs):
            1. contract.yaml
            """.trimIndent()
            ).containsIgnoringWhitespaces(
                """
            Specs checked: 1 (Passed: 1, Failed: 0)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent()
            )
        }

        @Test
        fun `should exclude references of spec from untracked files`() {
            File("a.yaml").apply {
                referTo("a.yaml")
            }.copyTo(tempDir.resolve("a.yaml"))
            commitAndPush(tempDir, "Initial commit")
            File("src/test/resources/specifications/spec_with_external_reference/").copyRecursively(tempDir)

            val (stdOut, exitCode) = captureStandardOutput {
                BackwardCompatibilityCheckCommandV2().apply { options.repoDir = tempDir.canonicalPath }.call()
            }

            assertThat(exitCode).isEqualTo(0)
            assertThat(stdOut).containsIgnoringWhitespaces(
                """
            - Specs that will be skipped (untracked specs, or schema files that are not referred to in other specs):
            1. api.yaml
            """.trimIndent()
            ).containsIgnoringWhitespaces(
                """
            Specs checked: 0 (Passed: 0, Failed: 0)
            Changed specs: 0
            Changed externalised example files: 0
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent()
            )
        }

        @Test
        fun `should work if path is relative in windows and linux based os`() {
            val baseApiSpec = """
            openapi: 3.0.0
            info:
              title: Base API
              version: 1.0.0
            paths:
              /health:
                get:
                  summary: Health check
                  responses:
                    '200':
                      description: OK
            """.trimIndent()

            val otherApiSpec = """
            openapi: 3.0.0
            info:
              title: Other API
              version: 1.0.0
            paths:
              /status:
                get:
                  summary: Status check
                  responses:
                    '200':
                      description: OK
            """.trimIndent()

            File(tempDir, "base-api.yaml").writeText(baseApiSpec)
            commitAndPush(tempDir, "Initial commit")
            File(tempDir, "other-api.yaml").writeText(otherApiSpec)

            val (stdOut, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
                BackwardCompatibilityCheckCommandV2().apply {
                    options.repoDir = tempDir.canonicalPath
                    options.targetPath = "${tempDir.canonicalPath}/other-api.yaml"
                }.call()
            }

            assertThat(exitCode).isEqualTo(0)
            assertThat(stdOut).containsIgnoringWhitespaces(
                """
            - Specs that will be skipped (untracked specs, or schema files that are not referred to in other specs):
            1. other-api.yaml
            """.trimIndent()
            ).containsIgnoringWhitespaces(
                """
            Specs checked: 0 (Passed: 0, Failed: 0)
            Changed specs: 0
            Changed externalised example files: 0
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent()
            )
        }
    }

    @Nested
    inner class SystemGitTestsSpecificToBackwardCompatibility {
        @Test
        fun `getFilesChangedInCurrentBranch returns the uncommitted, unstaged changed file`() {
            File(tempDir, "file1.txt").writeText("File 1 content")
            ProcessBuilder("git", "add", "file1.txt")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()
            ProcessBuilder("git", "commit", "-m", "Add file1")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()
            // Push the committed changes to the remote repository
            ProcessBuilder("git", "push", "origin", "main")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()


            val uncommittedFile = File(tempDir, "file1.txt")
            uncommittedFile.writeText("File 1 changed content")

            val gitCommand = SystemGit(tempDir.absolutePath)
            val result = gitCommand.getFilesChangedInCurrentBranch(
                gitCommand.currentRemoteBranch()
            ).map { it.substringAfterLast(File.separator) }

            assert(result.contains("file1.txt"))
        }

        @Test
        fun `getFilesChangedInCurrentBranch returns the uncommitted, staged changed file`() {
            File(tempDir, "file1.txt").writeText("File 1 content")
            ProcessBuilder("git", "add", "file1.txt")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()
            ProcessBuilder("git", "commit", "-m", "Add file1")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()
            // Push the committed changes to the remote repository
            ProcessBuilder("git", "push", "origin", "main")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()


            val uncommittedFile = File(tempDir, "file1.txt")
            uncommittedFile.writeText("File 1 changed content")
            ProcessBuilder("git", "add", "file1.txt")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()

            val gitCommand = SystemGit(tempDir.absolutePath)
            val result = gitCommand.getFilesChangedInCurrentBranch(
                gitCommand.currentRemoteBranch()
            ).map { it.substringAfterLast(File.separator) }

            assert(result.contains("file1.txt"))
        }
    }

    @Nested
    inner class VerdictMessageTests {
        val processedSpec = ProcessedSpec(
            specFilePath = "spec.yaml",
            backwardCompatibilityResult = Results(),
            precomputedCompatibilityResult = CompatibilityResult.FAILED,
            computedCompatibilityCheckHookResult = Pair(CompatibilityResult.UNKNOWN, emptyList()),
            isNewFile = false
        )

        @Test
        fun `verdictMessage without hook returns base message`() {
            val result = BackwardCompatibilityCheckBaseCommand.failedVerdictMessage(
                processedSpec,
                null,
                true,
                "some-branch"
            )

            assertThat(result.first).isEqualTo(CompatibilityResult.FAILED)
            assertThat(result.second).isEqualTo("(INCOMPATIBLE) The changes to the spec are NOT backward compatible with the corresponding spec from some-branch")
        }

        @Test
        fun `verdictMessage with hook returns verdict from hook`() {
            val hook = object : BackwardCompatibilityCheckHook {
                override fun logStartedMessage(failedSpecs: List<ProcessedSpec>) {}
                override fun check(
                    backwardCompatibilityResult: Results,
                    remoteUrl: String,
                    relativePath: String
                ): Pair<CompatibilityResult, List<OperationUsageResponse>> =
                    Pair(CompatibilityResult.UNKNOWN, emptyList())

                override fun logCompletedMessage() {}
                override fun failedVerdictAndMessage(
                    processedSpec: ProcessedSpec,
                    strictMode: Boolean,
                ): Pair<CompatibilityResult, String> {
                    return Pair(CompatibilityResult.UNKNOWN, "(HOOK: override)")
                }

            }

            val result = BackwardCompatibilityCheckBaseCommand.failedVerdictMessage(
                processedSpec,
                hook,
                true,
                "some-branch"
            )

            assertThat(result.second).isEqualTo("(HOOK: override)")
        }
    }

    @Nested
    inner class ExternalExampleTests {

        @Test
        fun `should validate changed externalised examples of unchanged specs`() {
            val oasDir = File("src/test/resources/specifications/spec_with_examples")
            oasDir.copyRecursively(remoteDir); oasDir.copyRecursively(tempDir)
            commitAndPush(tempDir, "Initial commit")

            val exampleFile = tempDir.resolve("api_examples").resolve("example.json")
            exampleFile.writeText(exampleFile.readText().replace("john", "jane"))

            val (stdOut, exitCode) = captureStandardOutput {
                BackwardCompatibilityCheckCommandV2().apply { options.repoDir = tempDir.canonicalPath }.call()
            }

            assertThat(exitCode).isEqualTo(0)
            assertThat(stdOut).containsIgnoringWhitespaces(
                """
            - Externalised example directories whose spec has changed or which contain changed examples: 
            1. api_examples (1 file changed)

            - Specs whose externalised examples will be validated:
            1. api.yaml (changed externalised examples associated with unchanged spec)
            """.trimIndent()
            ).containsIgnoringWhitespaces(
                """
            Specs checked: 1 (Passed: 1, Failed: 0)
            Changed specs: 0
            Changed externalised example files: 1
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent()
            )
            assertThat(stdOut).contains("Externalised Example Scope:")
            assertThat(stdOut).contains("Validation triggered by changed externalised examples associated with unchanged spec.")
            assertThat(stdOut).contains("Changed externalised example files in scope: 1")
            assertThat(stdOut).contains("The Examples Validation Summary:")
            assertThat(stdOut).contains("External Example Validation Summary")
            assertThat(stdOut).contains("Changed externalised examples associated with unchanged spec are valid.")
        }

        @Test
        fun `should show successful externalised example validation summary for changed specs`() {
            val oasDir = File("src/test/resources/specifications/spec_with_examples")
            oasDir.copyRecursively(remoteDir)
            oasDir.copyRecursively(tempDir)
            commitAndPush(tempDir, "Initial commit")

            val specFile = tempDir.resolve("api.yaml")
            specFile.writeText(specFile.readText().replace("""type: integer""", """type: integer
        description: response id"""))

            val (stdOut, exitCode) = captureStandardOutput {
                BackwardCompatibilityCheckCommandV2().apply { options.repoDir = tempDir.canonicalPath }.call()
            }

            assertThat(exitCode).isEqualTo(0)
            assertThat(stdOut).contains("Validation triggered by externalised examples of changed spec.")
            assertThat(stdOut).contains("Externalised Example Scope:")
            assertThat(stdOut).contains("The Examples Validation Summary:")
            assertThat(stdOut).contains("External Example Validation Summary")
            assertThat(stdOut).contains("Validated 1 externalised example(s).")
            assertThat(stdOut).contains("All 1 example(s) are valid.")
            assertThat(stdOut).contains("Compatibility verdict: PASS. The spec is backward compatible with the corresponding spec from main")
        }

        @Test
        fun `should treat changed externalised example without corresponding spec as changed file`() {
            val exampleDir = tempDir.resolve("orders_examples").apply { mkdirs() }
            val exampleFile = exampleDir.resolve("example.json").apply { writeText("""{"id":1}""") }
            commitAndPush(tempDir, "Initial commit")
            exampleFile.writeText("""{"id":2}""")

            val (stdOut, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
                BackwardCompatibilityCheckCommandV2().apply { options.repoDir = tempDir.canonicalPath }.call()
            }

            assertThat(exitCode).isEqualTo(0)
            assertThat(stdOut).containsIgnoringWhitespaces(
                """
                - Specs that have changed:
                1. orders_examples/example.json
                - Specs whose externalised examples will be validated:
                1. orders_examples/example.json (changed spec)
                """.trimIndent()
            )
            assertThat(stdOut).contains(
                """
                Specs checked: 1 (Passed: 1, Failed: 0)
                Changed specs: 1
                Changed externalised example files: 0
                Specs with backward compatibility failures: 0
                Specs with externalised example validation failures: 0
                """.trimIndent()
            )
        }

        @Test
        fun `should validate unloadable externalised examples of changed specs`() {
            val oasDir = File("src/test/resources/specifications/spec_with_examples")
            oasDir.copyRecursively(remoteDir)
            oasDir.copyRecursively(tempDir)

            val exampleFile = tempDir.resolve("api_examples").resolve("example.json")
            exampleFile.writeText(exampleFile.readText().replace(""""path": "/test"""", """"path": "/missing""""))
            commitAndPush(tempDir, "Initial commit with unloadable external example")

            val specFile = tempDir.resolve("api.yaml")
            specFile.writeText(specFile.readText().replace("""type: integer""", """type: integer
        description: response id"""))

            val (stdOut, exitCode) = captureStandardOutput {
                BackwardCompatibilityCheckCommandV2().apply { options.repoDir = tempDir.canonicalPath }.call()
            }

            assertThat(exitCode).isEqualTo(1)
            assertThat(stdOut).contains("Validation triggered by externalised examples of changed spec.")
            assertThat(stdOut).contains("External Example Loading Results")
            assertThat(stdOut).contains("api_examples/example.json")
            assertThat(stdOut).contains("Compatibility verdict: FAIL. The spec is backward compatible but externalised examples of changed spec are NOT backward compatible or are INVALID.")
        }

        @Test
        fun `should validate matched externalised examples of changed specs`() {
            val oasDir = File("src/test/resources/specifications/spec_with_examples")
            oasDir.copyRecursively(remoteDir)
            oasDir.copyRecursively(tempDir)

            val exampleFile = tempDir.resolve("api_examples").resolve("example.json")
            exampleFile.writeText(exampleFile.readText().replace(""""id": 10""", """"id": "10""""))
            commitAndPush(tempDir, "Initial commit with invalid matched external example")

            val specFile = tempDir.resolve("api.yaml")
            specFile.writeText(specFile.readText().replace("""type: integer""", """type: integer
        description: response id"""))

            val (stdOut, exitCode) = captureStandardOutput {
                BackwardCompatibilityCheckCommandV2().apply { options.repoDir = tempDir.canonicalPath }.call()
            }

            assertThat(exitCode).isEqualTo(1)
            assertThat(stdOut).contains("Validation triggered by externalised examples of changed spec.")
            assertThat(stdOut).contains("External Example Validation Results")
            assertThat(stdOut).contains("api_examples/example.json")
            assertThat(stdOut).contains("R1001: Type mismatch")
            assertThat(stdOut).contains("Compatibility verdict: FAIL. The spec is backward compatible but externalised examples of changed spec are NOT backward compatible or are INVALID.")
        }

        @ParameterizedTest
        @CsvSource(
            "/api/api_examples/example.json, /api/api_examples",
            "/api/api_examples/product/example.json, /api/api_examples",
            "/api_tests/example.json, ",
            "/api/api_config/config.json, ",
            "/example.json, "
        )
        fun `should be able to properly resolve examples dir when by walking up the example file path`(
            exampleFile: String,
            expectedDir: String?
        ) {
            val exampleDir = BackwardCompatibilityCheckCommandV2().getParentExamplesDirectory(Paths.get(exampleFile))
            assertThat(exampleDir).isEqualTo(expectedDir?.let(Paths::get))
        }
    }

    @Nested
    inner class WipConsoleOutput {
        // Uses the same fixtures as TestBackwardCompatibilityKtTest's
        // "report reflects per-5-tuple change status..." but drives the full command (with git
        // machinery) to assert the entire console output. The breaking WIP operation GET /promotions
        // appears in its own "WIP scenarios" section and does NOT count towards the FAILED verdict;
        // the real breaking changes (GET /orders 400/500, DELETE /orders/{id}) are what fail the spec.
        @Test
        fun `breaking WIP scenarios are shown in a separate section and do not drive the verdict`() {
            val spec = tempDir.resolve("orders.yaml")
            fixture("orders_old.yaml").copyTo(spec)
            commit(tempDir, "Initial contract")
            val baseBranch = SystemGit(tempDir.absolutePath).currentBranch()

            ProcessBuilder("git", "switch", "-c", "orders-change")
                .directory(tempDir).inheritIO().start().waitFor()

            fixture("orders_new.yaml").copyTo(spec, overwrite = true)
            commit(tempDir, "Breaking changes plus a breaking WIP operation")

            val (stdOut, exitCode) = captureStandardOutput {
                BackwardCompatibilityCheckCommandV2().apply {
                    options.repoDir = tempDir.canonicalPath
                    options.baseBranch = baseBranch
                }.call()
            }

            assertThat(exitCode).isEqualTo(1)

            // Normalize trailing whitespace per line so the literal stays maintainable;
            // the full output is asserted otherwise.
            val normalizedOutput = stdOut.lineSequence().joinToString("\n") { it.trimEnd() }.replace('\\', '/')

            assertThat(normalizedOutput).contains("Checking backward compatibility of the following specs:")
            assertThat(normalizedOutput).contains("API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}")
            assertThat(normalizedOutput).contains("The Incompatibility Report:")
            assertThat(normalizedOutput).contains("In scenario \"GET /orders. Response: bad request\"")
            assertThat(normalizedOutput).contains("This API exists in the old contract but not in the new contract (${spec.name}:28:9)")
            assertThat(normalizedOutput).contains("WIP scenarios (incompatible, not breaking the check):")
            assertThat(normalizedOutput).contains("In scenario \"GET /promotions. Response: ok\"")
            assertThat(normalizedOutput).contains("Externalised Example Validation:")
            assertThat(normalizedOutput).contains("Skipped because spec itself is backward incompatible.")
            assertThat(normalizedOutput).contains("Compatibility verdict: FAIL")
            assertThat(normalizedOutput).contains(
                """
                Specs checked: 1 (Passed: 0, Failed: 1)
                Changed specs: 1
                Changed externalised example files: 0
                Specs with backward compatibility failures: 1
                Specs with externalised example validation failures: 0
                """.trimIndent()
            )
        }

        private fun fixture(name: String): File =
            File("src/test/resources/specifications/bcc_integration/$name").canonicalFile
    }

    @Nested
    inner class WipMatrix {
        // Commits `oldSpec` on the base branch, then `newSpec` on a feature branch, and runs the
        // backward compatibility check across them. Returns the full console output (trailing
        // whitespace normalized per line), the exit code, and the spec file (for path
        // interpolation in assertions).
        private fun runChange(oldSpec: String, newSpec: String): Triple<String, Int, File> {
            val spec = tempDir.resolve("spec.yaml")
            spec.writeText(oldSpec.trimIndent())
            commit(tempDir, "old")
            val baseBranch = SystemGit(tempDir.absolutePath).currentBranch()
            ProcessBuilder("git", "switch", "-c", "change").directory(tempDir).inheritIO().start().waitFor()
            spec.writeText(newSpec.trimIndent())
            commit(tempDir, "new")
            val (stdOut, exitCode) = captureStandardOutput {
                BackwardCompatibilityCheckCommandV2().apply {
                    options.repoDir = tempDir.canonicalPath
                    options.baseBranch = baseBranch
                }.call()
            }
            return Triple(stdOut.lineSequence().joinToString("\n") { it.trimEnd() }.replace('\\', '/'), exitCode, spec)
        }

        @Test
        fun `1 - all compatible, no WIP - COMPATIBLE, no report sections`() {
            val oldSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: string } }
            """
            val newSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: string }, note: { type: string } }
            """
            val (output, exitCode, spec) = runChange(oldSpec, newSpec)

            assertThat(exitCode).isEqualTo(0)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. ${spec.name}

              - Specs whose externalised examples will be validated:
                1. ${spec.name} (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for ${spec.name}:
            ===============================================================================

            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            [Compatibility Check] Executing 1 scenarios for GET /a against 1 operations
              - GET /a -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: PASS
              --------------------
              Verdict for spec ${spec.name}:
                Compatibility verdict: PASS. The spec is backward compatible with the corresponding spec from main
              --------------------


            =============== Final Compatibility Summary ===============
              1. ${spec.name}
                Final verdict: PASS


            Generating BCC report for 2 checks and 1 operations...
            Generating HTML report in build/reports/specmatic/backward_compatibility/html/index.html
            Specs checked: 1 (Passed: 1, Failed: 0)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent())
        }

        @Test
        fun `2 - compatible non-WIP and compatible WIP - COMPATIBLE, no WIP section`() {
            val oldSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: string } }
                  /b:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [code]
                                properties: { code: { type: string } }
            """
            val newSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: string }, note: { type: string } }
                  /b:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [code]
                                properties: { code: { type: string }, label: { type: string } }
            """
            val (output, exitCode, spec) = runChange(oldSpec, newSpec)

            assertThat(exitCode).isEqualTo(0)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. ${spec.name}

              - Specs whose externalised examples will be validated:
                1. ${spec.name} (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for ${spec.name}:
            ===============================================================================

            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 2, API Operations: 2


            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 2, API Operations: 2


            [Compatibility Check] Executing 1 scenarios for GET /a against 1 operations
              - GET /a -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: PASS

            [Compatibility Check] Executing 1 scenarios for GET /b against 1 operations
              - GET /b -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: PASS
              --------------------
              Verdict for spec ${spec.name}:
                Compatibility verdict: PASS. The spec is backward compatible with the corresponding spec from main
              --------------------


            =============== Final Compatibility Summary ===============
              1. ${spec.name}
                Final verdict: PASS


            Generating BCC report for 4 checks and 2 operations...
            Generating HTML report in build/reports/specmatic/backward_compatibility/html/index.html
            Specs checked: 1 (Passed: 1, Failed: 0)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent())
        }

        @Test
        fun `3 - compatible non-WIP and breaking WIP - COMPATIBLE, WIP section shown`() {
            val oldSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: string } }
                  /b:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [code]
                                properties: { code: { type: string } }
            """
            val newSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: string }, note: { type: string } }
                  /b:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [code]
                                properties: { code: { type: integer } }
            """
            val (output, exitCode, spec) = runChange(oldSpec, newSpec)

            assertThat(exitCode).isEqualTo(0)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. ${spec.name}

              - Specs whose externalised examples will be validated:
                1. ${spec.name} (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for ${spec.name}:
            ===============================================================================

            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 2, API Operations: 2


            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 2, API Operations: 2


            [Compatibility Check] Executing 1 scenarios for GET /a against 1 operations
              - GET /a -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: PASS

            [Compatibility Check] Executing 1 scenarios for GET /b against 1 operations
              - GET /b -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL
              ________________________________________
              WIP scenarios (incompatible, not breaking the check):

                In scenario "GET /b. Response: ok"
                API: GET /b -> 200

                  >> RESPONSE.BODY.code (${spec.name}:26:31)

                      This is number in the new specification response but string in the old specification
              --------------------
              Verdict for spec ${spec.name}:
                Compatibility verdict: PASS. The spec is backward compatible with the corresponding spec from main
              --------------------


            =============== Final Compatibility Summary ===============
              1. ${spec.name}
                Final verdict: PASS


            Generating BCC report for 4 checks and 2 operations...
            Generating HTML report in build/reports/specmatic/backward_compatibility/html/index.html
            Specs checked: 1 (Passed: 1, Failed: 0)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent())
        }

        @Test
        fun `4 - breaking non-WIP, no WIP - INCOMPATIBLE, report shown`() {
            val oldSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: string } }
            """
            val newSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: integer } }
            """
            val (output, exitCode, spec) = runChange(oldSpec, newSpec)

            assertThat(exitCode).isEqualTo(1)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. ${spec.name}

              - Specs whose externalised examples will be validated:
                1. ${spec.name} (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for ${spec.name}:
            ===============================================================================

            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            [Compatibility Check] Executing 1 scenarios for GET /a against 1 operations
              - GET /a -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL
              ________________________________________
              The Incompatibility Report:

                In scenario "GET /a. Response: ok"
                API: GET /a -> 200

                  >> RESPONSE.BODY.value (${spec.name}:14:31)

                      This is number in the new specification response but string in the old specification
              ________________________________________
              Externalised Example Validation:

                Skipped because spec itself is backward incompatible.


              --------------------
              Verdict for spec ${spec.name}:
                Compatibility verdict: FAIL
              --------------------


            =============== Final Compatibility Summary ===============
              1. ${spec.name}
                Final verdict: FAIL. (INCOMPATIBLE) The changes to the spec are NOT backward compatible with the corresponding spec from main


            Generating BCC report for 2 checks and 1 operations...
            Generating HTML report in build/reports/specmatic/backward_compatibility/html/index.html
            Specs checked: 1 (Passed: 0, Failed: 1)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 1
            Specs with externalised example validation failures: 0
            """.trimIndent())
        }

        @Test
        fun `5 - breaking non-WIP and compatible WIP - INCOMPATIBLE, no WIP section`() {
            val oldSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: string } }
                  /b:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [code]
                                properties: { code: { type: string } }
            """
            val newSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: integer } }
                  /b:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [code]
                                properties: { code: { type: string }, label: { type: string } }
            """
            val (output, exitCode, spec) = runChange(oldSpec, newSpec)

            assertThat(exitCode).isEqualTo(1)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. ${spec.name}

              - Specs whose externalised examples will be validated:
                1. ${spec.name} (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for ${spec.name}:
            ===============================================================================

            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 2, API Operations: 2


            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 2, API Operations: 2


            [Compatibility Check] Executing 1 scenarios for GET /a against 1 operations
              - GET /a -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL

            [Compatibility Check] Executing 1 scenarios for GET /b against 1 operations
              - GET /b -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: PASS
              ________________________________________
              The Incompatibility Report:

                In scenario "GET /a. Response: ok"
                API: GET /a -> 200

                  >> RESPONSE.BODY.value (${spec.name}:14:31)

                      This is number in the new specification response but string in the old specification
              ________________________________________
              Externalised Example Validation:

                Skipped because spec itself is backward incompatible.


              --------------------
              Verdict for spec ${spec.name}:
                Compatibility verdict: FAIL
              --------------------


            =============== Final Compatibility Summary ===============
              1. ${spec.name}
                Final verdict: FAIL. (INCOMPATIBLE) The changes to the spec are NOT backward compatible with the corresponding spec from main


            Generating BCC report for 4 checks and 2 operations...
            Generating HTML report in build/reports/specmatic/backward_compatibility/html/index.html
            Specs checked: 1 (Passed: 0, Failed: 1)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 1
            Specs with externalised example validation failures: 0
            """.trimIndent())
        }

        @Test
        fun `6 - breaking non-WIP and breaking WIP - INCOMPATIBLE, both sections shown`() {
            val oldSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: string } }
                  /b:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [code]
                                properties: { code: { type: string } }
            """
            val newSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: integer } }
                  /b:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [code]
                                properties: { code: { type: integer } }
            """
            val (output, exitCode, spec) = runChange(oldSpec, newSpec)

            assertThat(exitCode).isEqualTo(1)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. ${spec.name}

              - Specs whose externalised examples will be validated:
                1. ${spec.name} (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for ${spec.name}:
            ===============================================================================

            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 2, API Operations: 2


            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 2, API Operations: 2


            [Compatibility Check] Executing 1 scenarios for GET /a against 1 operations
              - GET /a -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL

            [Compatibility Check] Executing 1 scenarios for GET /b against 1 operations
              - GET /b -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL
              ________________________________________
              The Incompatibility Report:

                In scenario "GET /a. Response: ok"
                API: GET /a -> 200

                  >> RESPONSE.BODY.value (${spec.name}:14:31)

                      This is number in the new specification response but string in the old specification
              ________________________________________
              WIP scenarios (incompatible, not breaking the check):

                In scenario "GET /b. Response: ok"
                API: GET /b -> 200

                  >> RESPONSE.BODY.code (${spec.name}:26:31)

                      This is number in the new specification response but string in the old specification
              ________________________________________
              Externalised Example Validation:

                Skipped because spec itself is backward incompatible.


              --------------------
              Verdict for spec ${spec.name}:
                Compatibility verdict: FAIL
              --------------------


            =============== Final Compatibility Summary ===============
              1. ${spec.name}
                Final verdict: FAIL. (INCOMPATIBLE) The changes to the spec are NOT backward compatible with the corresponding spec from main


            Generating BCC report for 4 checks and 2 operations...
            Generating HTML report in build/reports/specmatic/backward_compatibility/html/index.html
            Specs checked: 1 (Passed: 0, Failed: 1)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 1
            Specs with externalised example validation failures: 0
            """.trimIndent())
        }

        @Test
        fun `7 - spec is entirely WIP and breaking - COMPATIBLE, only WIP section shown`() {
            val oldSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /b:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [code]
                                properties: { code: { type: string } }
            """
            val newSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /b:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [code]
                                properties: { code: { type: integer } }
            """
            val (output, exitCode, spec) = runChange(oldSpec, newSpec)

            assertThat(exitCode).isEqualTo(0)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. ${spec.name}

              - Specs whose externalised examples will be validated:
                1. ${spec.name} (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for ${spec.name}:
            ===============================================================================

            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            [Compatibility Check] Executing 1 scenarios for GET /b against 1 operations
              - GET /b -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL
              ________________________________________
              WIP scenarios (incompatible, not breaking the check):

                In scenario "GET /b. Response: ok"
                API: GET /b -> 200

                  >> RESPONSE.BODY.code (${spec.name}:15:31)

                      This is number in the new specification response but string in the old specification
              --------------------
              Verdict for spec ${spec.name}:
                Compatibility verdict: PASS. The spec is backward compatible with the corresponding spec from main
              --------------------


            =============== Final Compatibility Summary ===============
              1. ${spec.name}
                Final verdict: PASS


            Generating BCC report for 2 checks and 1 operations...
            Generating HTML report in build/reports/specmatic/backward_compatibility/html/index.html
            Specs checked: 1 (Passed: 1, Failed: 0)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent())
        }

        @Test
        fun `8 - WIP tag added in new and breaking - COMPATIBLE, WIP section shown (new spec governs)`() {
            val oldSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: string } }
            """
            val newSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: integer } }
            """
            val (output, exitCode, spec) = runChange(oldSpec, newSpec)

            assertThat(exitCode).isEqualTo(0)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. ${spec.name}

              - Specs whose externalised examples will be validated:
                1. ${spec.name} (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for ${spec.name}:
            ===============================================================================

            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            [Compatibility Check] Executing 1 scenarios for GET /a against 1 operations
              - GET /a -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL
              ________________________________________
              WIP scenarios (incompatible, not breaking the check):

                In scenario "GET /a. Response: ok"
                API: GET /a -> 200

                  >> RESPONSE.BODY.value (${spec.name}:15:31)

                      This is number in the new specification response but string in the old specification
              --------------------
              Verdict for spec ${spec.name}:
                Compatibility verdict: PASS. The spec is backward compatible with the corresponding spec from main
              --------------------


            =============== Final Compatibility Summary ===============
              1. ${spec.name}
                Final verdict: PASS


            Generating BCC report for 2 checks and 1 operations...
            Generating HTML report in build/reports/specmatic/backward_compatibility/html/index.html
            Specs checked: 1 (Passed: 1, Failed: 0)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent())
        }

        @Test
        fun `9 - WIP tag removed in new and breaking - INCOMPATIBLE, report shown (new spec governs)`() {
            val oldSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      tags: [WIP]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: string } }
            """
            val newSpec = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: integer } }
            """
            val (output, exitCode, spec) = runChange(oldSpec, newSpec)

            assertThat(exitCode).isEqualTo(1)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. ${spec.name}

              - Specs whose externalised examples will be validated:
                1. ${spec.name} (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for ${spec.name}:
            ===============================================================================

            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            API Specification Summary: ${spec.canonicalFile.invariantSeparatorsPath}
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            [Compatibility Check] Executing 1 scenarios for GET /a against 1 operations
              - GET /a -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL
              ________________________________________
              The Incompatibility Report:

                In scenario "GET /a. Response: ok"
                API: GET /a -> 200

                  >> RESPONSE.BODY.value (${spec.name}:14:31)

                      This is number in the new specification response but string in the old specification
              ________________________________________
              Externalised Example Validation:

                Skipped because spec itself is backward incompatible.


              --------------------
              Verdict for spec ${spec.name}:
                Compatibility verdict: FAIL
              --------------------


            =============== Final Compatibility Summary ===============
              1. ${spec.name}
                Final verdict: FAIL. (INCOMPATIBLE) The changes to the spec are NOT backward compatible with the corresponding spec from main


            Generating BCC report for 2 checks and 1 operations...
            Generating HTML report in build/reports/specmatic/backward_compatibility/html/index.html
            Specs checked: 1 (Passed: 0, Failed: 1)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 1
            Specs with externalised example validation failures: 0
            """.trimIndent())
        }
    }

    @Nested
    inner class ChangeTrackingReportTests {
        @Test
        fun `reports external schema changes after command checks out the base branch`() {
            val apiFile = tempDir.resolve("api.yaml")
            val componentsFile = tempDir.resolve("components.yaml")

            fixture("api.yaml").copyTo(apiFile)
            fixture("components_base.yaml").copyTo(componentsFile)
            commit(tempDir, "Initial contract")
            val baseBranch = SystemGit(tempDir.absolutePath).currentBranch()

            ProcessBuilder("git", "switch", "-c", "external-ref-change")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()

            fixture("components_current.yaml").copyTo(componentsFile, overwrite = true)
            commit(tempDir, "Add optional field in external schema")

            val configFile = remoteDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    reportDirPath: ${tempDir.resolve("reports").canonicalPath}
                    """.trimIndent()
                )
            }

            val (_, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
                using(CONFIG_FILE_PATH to configFile.canonicalPath) {
                    BackwardCompatibilityCheckCommandV2().apply {
                        options.repoDir = tempDir.canonicalPath
                        options.baseBranch = baseBranch
                    }.call()
                }
            }

            assertThat(exitCode).isEqualTo(0)

            val reportJson = bccReportJson(tempDir.resolve("reports/backward_compatibility"))
            val operations = reportJson.path("results").path("summary").path("extra").path("executionDetails").first()
                .path("operations")
            val getOrders = operations.single {
                it.path("method").asText() == "GET" && it.path("path").asText() == "/orders"
            }

            assertThat(getOrders.path("status").asText()).isEqualTo("compatible")
            val qualifiers = getOrders.path("qualifiers").map { it.asText() }
            assertThat(qualifiers).contains("changed")
        }

        private fun fixture(name: String): File =
            File("src/test/resources/specifications/bcc_change_tracking_external_refs/$name").canonicalFile
    }

    @Nested
    inner class BccReportTests {
        @Test
        fun `bcc output should suppress inline example pairing warnings from captured compatibility logs`() {
            val specContent = """
                openapi: 3.0.0
                info:
                  title: Example warning suppression
                  version: 1.0.0
                paths:
                  /orders:
                    post:
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              required: [id]
                              properties:
                                id:
                                  type: integer
                            examples:
                              SUCCESS:
                                value:
                                  id: 10
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [id]
                                properties:
                                  id:
                                    type: integer
            """.trimIndent()

            val (rawConversionOutput, _) = captureStandardOutput {
                OpenApiSpecification.fromYAML(specContent, "spec.yaml").toFeature()
            }

            assertThat(rawConversionOutput).contains("WARNING: Ignoring request example named SUCCESS")

            val spec = tempDir.resolve("spec.yaml")
            spec.writeText(specContent)
            commit(tempDir, "old")
            val baseBranch = SystemGit(tempDir.absolutePath).currentBranch()
            ProcessBuilder("git", "switch", "-c", "change").directory(tempDir).inheritIO().start().waitFor()
            spec.writeText(specContent)
            commit(tempDir, "new")

            val (stdOut, exitCode) = captureStandardOutput {
                BackwardCompatibilityCheckCommandV2().apply {
                    options.repoDir = tempDir.canonicalPath
                    options.baseBranch = baseBranch
                }.call()
            }

            val normalizedOutput = stdOut.lineSequence().joinToString("\n") { it.trimEnd() }.replace('\\', '/')

            assertThat(exitCode).isEqualTo(0)
            assertThat(normalizedOutput).doesNotContain("WARNING: Ignoring request example named SUCCESS")
            assertThat(normalizedOutput).doesNotContain("WARNING: Ignoring response example named SUCCESS")
        }

        @Test
        fun `ctrf report includes compatible specs when hook validates mixed failures`() {
            fun specWithType(path: String, type: String, extraProperty: String = "") = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  $path:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: $type }$extraProperty }
            """.trimIndent()

            val compatibleSpec = tempDir.resolve("compatible.yaml")
            val hookPassedSpec = tempDir.resolve(MixedResultBackwardCompatibilityCheckHook.PASSED_SPEC)
            val hookFailedSpec = tempDir.resolve(MixedResultBackwardCompatibilityCheckHook.FAILED_SPEC)

            compatibleSpec.writeText(specWithType("/compatible", "string"))
            hookPassedSpec.writeText(specWithType("/hook-passed", "string"))
            hookFailedSpec.writeText(specWithType("/hook-failed", "string"))
            commit(tempDir, "Initial contract")
            val baseBranch = SystemGit(tempDir.absolutePath).currentBranch()

            ProcessBuilder("git", "switch", "-c", "mixed-hook-results")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()

            // This spec changes compatibly, while the two hook specs break the response type.
            compatibleSpec.writeText(specWithType("/compatible", "string", ", note: { type: string }"))
            hookPassedSpec.writeText(specWithType("/hook-passed", "integer"))
            hookFailedSpec.writeText(specWithType("/hook-failed", "integer"))
            commit(tempDir, "Mix compatible and breaking specs")

            val configFile = remoteDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    reportDirPath: ${tempDir.resolve("reports").canonicalPath}
                    """.trimIndent()
                )
            }

            val (stdOut, exitCode) = withRegisteredService(
                BackwardCompatibilityCheckHook::class.java,
                MixedResultBackwardCompatibilityCheckHook::class.java
            ) {
                captureStandardOutput(redirectStdErrToStdout = true) {
                    using(CONFIG_FILE_PATH to configFile.canonicalPath) {
                        BackwardCompatibilityCheckCommandV2().apply {
                            options.repoDir = tempDir.canonicalPath
                            options.baseBranch = baseBranch
                        }.call()
                    }
                }
            }

            val output = stdOut.lineSequence().joinToString("\n") { it.trimEnd() }.replace('\\', '/')

            val compatibleSpecPath = compatibleSpec.name
            val hookPassedSpecPath = hookPassedSpec.name
            val hookFailedSpecPath = hookFailedSpec.name
            val compatibleSpecAbsolutePath = compatibleSpec.canonicalFile.invariantSeparatorsPath
            val hookPassedSpecAbsolutePath = hookPassedSpec.canonicalFile.invariantSeparatorsPath
            val hookFailedSpecAbsolutePath = hookFailedSpec.canonicalFile.invariantSeparatorsPath
            val htmlReportPath = tempDir.resolve("reports/backward_compatibility/html/index.html")
                .canonicalFile.invariantSeparatorsPath

            assertThat(exitCode).isEqualTo(1)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. $compatibleSpecPath
                2. $hookFailedSpecPath
                3. $hookPassedSpecPath

              - Specs whose externalised examples will be validated:
                1. $compatibleSpecPath (changed spec)
                2. $hookFailedSpecPath (changed spec)
                3. $hookPassedSpecPath (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for $compatibleSpecPath:
            ===============================================================================

            API Specification Summary: $compatibleSpecAbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            API Specification Summary: $compatibleSpecAbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            [Compatibility Check] Executing 1 scenarios for GET /compatible against 1 operations
              - GET /compatible -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: PASS
              --------------------
              Verdict for spec $compatibleSpecPath:
                Compatibility verdict: PASS. The spec is backward compatible with the corresponding spec from main
              --------------------


            ===============================================================================
            2. Running the check for $hookFailedSpecPath:
            ===============================================================================

            API Specification Summary: $hookFailedSpecAbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            API Specification Summary: $hookFailedSpecAbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            [Compatibility Check] Executing 1 scenarios for GET /hook-failed against 1 operations
              - GET /hook-failed -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL
              ________________________________________
              The Incompatibility Report:

                In scenario "GET /hook-failed. Response: ok"
                API: GET /hook-failed -> 200

                  >> RESPONSE.BODY.value (${hookFailedSpec.name}:14:31)

                      This is number in the new specification response but string in the old specification
              ________________________________________
              Externalised Example Validation:

                Skipped because spec itself is backward incompatible.


              --------------------
              Verdict for spec $hookFailedSpecPath:
                Compatibility verdict: FAIL
              --------------------


            ===============================================================================
            3. Running the check for $hookPassedSpecPath:
            ===============================================================================

            API Specification Summary: $hookPassedSpecAbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            API Specification Summary: $hookPassedSpecAbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            [Compatibility Check] Executing 1 scenarios for GET /hook-passed against 1 operations
              - GET /hook-passed -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL
              ________________________________________
              The Incompatibility Report:

                In scenario "GET /hook-passed. Response: ok"
                API: GET /hook-passed -> 200

                  >> RESPONSE.BODY.value (${hookPassedSpec.name}:14:31)

                      This is number in the new specification response but string in the old specification
              ________________________________________
              Externalised Example Validation:

                Skipped because spec itself is backward incompatible.


              --------------------
              Verdict for spec $hookPassedSpecPath:
                Compatibility verdict: FAIL
              --------------------


            =============== Hook Validation Results ===============
            Validating 2 failed spec(s) with the backward compatibility check hook...
            Hook validation complete.
              1. $hookFailedSpecPath
                Hook verdict: FAIL
              2. $hookPassedSpecPath
                Hook verdict: PASS


            =============== Final Compatibility Summary ===============
              1. $compatibleSpecPath
                Final verdict: PASS
              2. $hookFailedSpecPath
                Final verdict: FAIL. (HOOK: FAILED) The hook did not declare the spec as backward compatible
              3. $hookPassedSpecPath
                Final verdict: PASS. (HOOK: PASSED) The hook declared the failing spec as backward compatible


            Generating BCC report for 6 checks and 3 operations...
            Generating HTML report in $htmlReportPath
            Specs checked: 3 (Passed: 2, Failed: 1)
            Changed specs: 3
            Changed externalised example files: 0
            Specs with backward compatibility failures: 1
            Specs with externalised example validation failures: 0
            """.trimIndent())

            val reportJson = bccReportJson(tempDir.resolve("reports/backward_compatibility"))
            val executionDetails = reportJson.path("results").path("summary").path("extra").path("executionDetails")
            val operationsBySpec = executionDetails.associate { detail ->
                detail.path("specification").asText().replace('\\', '/') to detail.path("operations").single().let {
                    "${it.path("method").asText()} ${it.path("path").asText()} -> ${it.path("status").asText()}"
                }
            }

            // The report records the spec path relative to the repo root, not the absolute path.
            assertThat(operationsBySpec).containsOnly(
                entry("compatible.yaml", "GET /compatible -> compatible"),
                entry(MixedResultBackwardCompatibilityCheckHook.PASSED_SPEC, "GET /hook-passed -> incompatible"),
                entry(MixedResultBackwardCompatibilityCheckHook.FAILED_SPEC, "GET /hook-failed -> incompatible")
            )
        }

        @Test
        fun `ctrf report includes every breaking spec when more than one spec is breaking`() {
            fun specWithType(path: String, type: String) = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  $path:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: $type } }
            """.trimIndent()

            val spec1 = tempDir.resolve("spec1.yaml")
            val spec2 = tempDir.resolve("spec2.yaml")

            spec1.writeText(specWithType("/a", "string"))
            spec2.writeText(specWithType("/b", "string"))
            commit(tempDir, "Initial contract")
            val baseBranch = SystemGit(tempDir.absolutePath).currentBranch()

            ProcessBuilder("git", "switch", "-c", "breaking-change")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()

            // Both specs change the response body type from string to integer, which is breaking.
            spec1.writeText(specWithType("/a", "integer"))
            spec2.writeText(specWithType("/b", "integer"))
            commit(tempDir, "Make both specs breaking")

            val configFile = remoteDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    reportDirPath: ${tempDir.resolve("reports").canonicalPath}
                    """.trimIndent()
                )
            }

            val (stdOut, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
                using(CONFIG_FILE_PATH to configFile.canonicalPath) {
                    BackwardCompatibilityCheckCommandV2().apply {
                        options.repoDir = tempDir.canonicalPath
                        options.baseBranch = baseBranch
                    }.call()
                }
            }

            val output = stdOut.lineSequence().joinToString("\n") { it.trimEnd() }.replace('\\', '/')

            val spec1Path = spec1.name
            val spec2Path = spec2.name
            val spec1AbsolutePath = spec1.canonicalFile.invariantSeparatorsPath
            val spec2AbsolutePath = spec2.canonicalFile.invariantSeparatorsPath
            val htmlReportPath = tempDir.resolve("reports/backward_compatibility/html/index.html")
                .canonicalFile.invariantSeparatorsPath

            // The console output correctly reports BOTH specs as incompatible.
            assertThat(exitCode).isEqualTo(1)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. $spec1Path
                2. $spec2Path

              - Specs whose externalised examples will be validated:
                1. $spec1Path (changed spec)
                2. $spec2Path (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for $spec1Path:
            ===============================================================================

            API Specification Summary: $spec1AbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            API Specification Summary: $spec1AbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            [Compatibility Check] Executing 1 scenarios for GET /a against 1 operations
              - GET /a -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL
              ________________________________________
              The Incompatibility Report:

                In scenario "GET /a. Response: ok"
                API: GET /a -> 200

                  >> RESPONSE.BODY.value (${spec1.name}:14:31)

                      This is number in the new specification response but string in the old specification
              ________________________________________
              Externalised Example Validation:

                Skipped because spec itself is backward incompatible.


              --------------------
              Verdict for spec $spec1Path:
                Compatibility verdict: FAIL
              --------------------


            ===============================================================================
            2. Running the check for $spec2Path:
            ===============================================================================

            API Specification Summary: $spec2AbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            API Specification Summary: $spec2AbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            [Compatibility Check] Executing 1 scenarios for GET /b against 1 operations
              - GET /b -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL
              ________________________________________
              The Incompatibility Report:

                In scenario "GET /b. Response: ok"
                API: GET /b -> 200

                  >> RESPONSE.BODY.value (${spec2.name}:14:31)

                      This is number in the new specification response but string in the old specification
              ________________________________________
              Externalised Example Validation:

                Skipped because spec itself is backward incompatible.


              --------------------
              Verdict for spec $spec2Path:
                Compatibility verdict: FAIL
              --------------------


            =============== Final Compatibility Summary ===============
              1. $spec1Path
                Final verdict: FAIL. (INCOMPATIBLE) The changes to the spec are NOT backward compatible with the corresponding spec from main
              2. $spec2Path
                Final verdict: FAIL. (INCOMPATIBLE) The changes to the spec are NOT backward compatible with the corresponding spec from main


            Generating BCC report for 4 checks and 2 operations...
            Generating HTML report in $htmlReportPath
            Specs checked: 2 (Passed: 0, Failed: 2)
            Changed specs: 2
            Changed externalised example files: 0
            Specs with backward compatibility failures: 2
            Specs with externalised example validation failures: 0
            """.trimIndent())

            val reportJson = bccReportJson(tempDir.resolve("reports/backward_compatibility"))

            // Both specs are present, each reported as incompatible on its own operation.
            val executionDetails = reportJson.path("results").path("summary").path("extra").path("executionDetails")
            val operationsBySpec = executionDetails.associate { detail ->
                detail.path("specification").asText().replace('\\', '/') to detail.path("operations").single().let {
                    "${it.path("method").asText()} ${it.path("path").asText()} -> ${it.path("status").asText()}"
                }
            }
            // The report records the spec path relative to the repo root, not the absolute path.
            assertThat(operationsBySpec).containsOnly(
                entry("spec1.yaml", "GET /a -> incompatible"),
                entry("spec2.yaml", "GET /b -> incompatible")
            )

            // The breadcrumbs in the CTRF report carry the same source locations that appear in
            // the console output: the failing breadcrumb points at the changed schema property.
            val testMessages = reportJson.path("results").path("tests")
                .map { it.path("message").asText().replace('\\', '/') }
            assertThat(testMessages)
                .anyMatch { it.contains("RESPONSE.BODY.value") && it.contains("${spec1.name}:14:31") }
            assertThat(testMessages)
                .anyMatch { it.contains("RESPONSE.BODY.value") && it.contains("${spec2.name}:14:31") }
        }

        @Test
        fun `hook passing a single failing spec reports the failure but exits successfully`() {
            fun specWithType(path: String, type: String) = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  $path:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: $type } }
            """.trimIndent()

            val spec = tempDir.resolve("spec.yaml")

            spec.writeText(specWithType("/a", "string"))
            commit(tempDir, "Initial contract")
            val baseBranch = SystemGit(tempDir.absolutePath).currentBranch()

            ProcessBuilder("git", "switch", "-c", "breaking-change")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()

            // The spec changes the response body type from string to integer, which is breaking.
            spec.writeText(specWithType("/a", "integer"))
            commit(tempDir, "Make the spec breaking")

            val configFile = remoteDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    reportDirPath: ${tempDir.resolve("reports").canonicalPath}
                    """.trimIndent()
                )
            }

            val (stdOut, exitCode) = withRegisteredService(
                BackwardCompatibilityCheckHook::class.java,
                AlwaysPassingBackwardCompatibilityCheckHook::class.java
            ) {
                captureStandardOutput(redirectStdErrToStdout = true) {
                    using(CONFIG_FILE_PATH to configFile.canonicalPath) {
                        BackwardCompatibilityCheckCommandV2().apply {
                            options.repoDir = tempDir.canonicalPath
                            options.baseBranch = baseBranch
                        }.call()
                    }
                }
            }

            val output = stdOut.lineSequence().joinToString("\n") { it.trimEnd() }.replace('\\', '/')

            val specPath = spec.name
            val specAbsolutePath = spec.canonicalFile.invariantSeparatorsPath
            val htmlReportPath = tempDir.resolve("reports/backward_compatibility/html/index.html")
                .canonicalFile.invariantSeparatorsPath

            // The console output still reports the spec as failing, but the hook passes the
            // build so the exit code is 0.
            assertThat(exitCode).isEqualTo(0)
            assertThat(output).isEqualToNormalizingNewlines("""
            Checking backward compatibility of the following specs:

              - Specs that have changed:
                1. $specPath

              - Specs whose externalised examples will be validated:
                1. $specPath (changed spec)

            --------------------


            =============== Compatibility Check Results ===============


            ===============================================================================
            1. Running the check for $specPath:
            ===============================================================================

            API Specification Summary: $specAbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            API Specification Summary: $specAbsolutePath
              OpenAPI Version: 3.0.0
              API Paths: 1, API Operations: 1


            [Compatibility Check] Executing 1 scenarios for GET /a against 1 operations
              - GET /a -> 200 (responseContentType application/json)
            [Compatibility Check] Verdict: FAIL
              ________________________________________
              The Incompatibility Report:

                In scenario "GET /a. Response: ok"
                API: GET /a -> 200

                  >> RESPONSE.BODY.value (${spec.name}:14:31)

                      This is number in the new specification response but string in the old specification
              ________________________________________
              Externalised Example Validation:

                Skipped because spec itself is backward incompatible.


              --------------------
              Verdict for spec $specPath:
                Compatibility verdict: FAIL
              --------------------


            =============== Hook Validation Results ===============
            Validating 1 failed spec(s) with the backward compatibility check hook...
            Hook validation complete.
              1. $specPath
                Hook verdict: PASS


            =============== Final Compatibility Summary ===============
              1. $specPath
                Final verdict: PASS. (HOOK: PASSED) The hook declared the failing spec as backward compatible


            Generating BCC report for 2 checks and 1 operations...
            Generating HTML report in $htmlReportPath
            Specs checked: 1 (Passed: 1, Failed: 0)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent())

            val reportJson = bccReportJson(tempDir.resolve("reports/backward_compatibility"))

            // The failing operation is still present in the report even though the hook passed the build.
            val executionDetails = reportJson.path("results").path("summary").path("extra").path("executionDetails")
            val operationsBySpec = executionDetails.associate { detail ->
                detail.path("specification").asText().replace('\\', '/') to detail.path("operations").single().let {
                    "${it.path("method").asText()} ${it.path("path").asText()} -> ${it.path("status").asText()}"
                }
            }
            assertThat(operationsBySpec).containsOnly(
                entry("spec.yaml", "GET /a -> incompatible")
            )
        }

        @Test
        fun `report records every spec path relative to the repo root`() {
            fun spec(path: String, type: String) = """
                openapi: 3.0.0
                info: { title: API, version: 1.0.0 }
                paths:
                  $path:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [value]
                                properties: { value: { type: $type } }
            """.trimIndent()

            // Three specs at increasing depths under the repo root.
            val rootSpec = tempDir.resolve("orders.yaml")
            val nestedSpec = tempDir.resolve("api/products.yaml").apply { parentFile.mkdirs() }
            val deeplyNestedSpec = tempDir.resolve("internal/v2/payments.yaml").apply { parentFile.mkdirs() }

            rootSpec.writeText(spec("/orders", "string"))
            nestedSpec.writeText(spec("/products", "string"))
            deeplyNestedSpec.writeText(spec("/payments", "string"))
            commit(tempDir, "Initial contracts")
            val baseBranch = SystemGit(tempDir.absolutePath).currentBranch()

            ProcessBuilder("git", "switch", "-c", "breaking-change")
                .directory(tempDir)
                .inheritIO()
                .start()
                .waitFor()

            // Each spec changes its response body type from string to integer (a breaking change),
            // so every spec shows up in the report.
            rootSpec.writeText(spec("/orders", "integer"))
            nestedSpec.writeText(spec("/products", "integer"))
            deeplyNestedSpec.writeText(spec("/payments", "integer"))
            commit(tempDir, "Make every spec breaking")

            val configFile = remoteDir.resolve("specmatic.yaml").apply {
                writeText(
                    """
                    version: 2
                    reportDirPath: ${tempDir.resolve("reports").canonicalPath}
                    """.trimIndent()
                )
            }

            captureStandardOutput(redirectStdErrToStdout = true) {
                using(CONFIG_FILE_PATH to configFile.canonicalPath) {
                    BackwardCompatibilityCheckCommandV2().apply {
                        options.repoDir = tempDir.canonicalPath
                        options.baseBranch = baseBranch
                    }.call()
                }
            }

            val recordedSpecPaths = bccReportJson(tempDir.resolve("reports/backward_compatibility"))
                .path("results").path("summary").path("extra").path("executionDetails")
                .map { it.path("specification").asText().replace('\\', '/') }
                .toSet()

            // The report records each spec as its path relative to the repo root (tempDir),
            // never as an absolute path: orders.yaml, api/products.yaml, internal/v2/payments.yaml.
            val repoRoot = tempDir.canonicalFile
            assertThat(recordedSpecPaths).contains(rootSpec.canonicalFile.relativeTo(repoRoot).invariantSeparatorsPath)
            assertThat(recordedSpecPaths).contains(nestedSpec.canonicalFile.relativeTo(repoRoot).invariantSeparatorsPath)
            assertThat(recordedSpecPaths).contains(deeplyNestedSpec.canonicalFile.relativeTo(repoRoot).invariantSeparatorsPath)
        }
    }

    @Test
    fun `should skip asyncapi spec files`() {
        val asyncApiFile = File("src/test/resources/specifications/asyncapi.yaml").canonicalFile
        val gitAsyncApiFile = tempDir.resolve("asyncapi.yaml").canonicalFile
        asyncApiFile.copyTo(gitAsyncApiFile)

        val apiFile = File("src/test/resources/specifications/spec_with_examples/api.yaml").canonicalFile
        val gitApiFile = tempDir.resolve("api.yaml").canonicalFile
        apiFile.copyTo(gitApiFile)

        commitAndPush(tempDir, "Initial commit")
        gitAsyncApiFile.writeText(gitAsyncApiFile.readText().replace("address: ping", "address: ping-v2"))
        gitApiFile.writeText(gitApiFile.readText().replace("endpoint", "modified endpoint"))

        val (stdOut, exitCode) = captureStandardOutput {
            BackwardCompatibilityCheckCommandV2().apply { options.repoDir = tempDir.canonicalPath }.call()
        }

        assertThat(exitCode).isEqualTo(0)

        assertThat(stdOut).containsIgnoringWhitespaces(
            """
            - Specs that have changed: 
            1. api.yaml
            """.trimIndent()
        ).containsIgnoringWhitespaces(
            """
            Specs checked: 1 (Passed: 1, Failed: 0)
            Changed specs: 1
            Changed externalised example files: 0
            Specs with backward compatibility failures: 0
            Specs with externalised example validation failures: 0
            """.trimIndent()
        )
    }

    @AfterEach
    fun `cleanup files`() {
        listOf(
            "file1.yaml",
            "file2.yaml",
            "file3.yaml",
            "file4.yaml",
            "schema_file1.yaml",
            "schema_file2.yaml",
            "a.yaml",
            "b.yaml",
            "c.yaml"
        ).forEach {
            File(it).delete()
        }
        tempDir.deleteRecursively()
        remoteDir.deleteRecursively()
    }

    private fun File.referTo(schemaFileName: String) {
        val specContent = """
           openapi: 3.1.0  # OpenAPI version specified here
           info:
             title: My API
             version: 1.0.0
           components:
             schemas:
               User:
                 ${"$"}ref: '#/components/schemas/$schemaFileName' 
       """.trimIndent()
        this.writeText(specContent)
    }

    private fun commitAndPush(repoDir: File, commitMessage: String) {
        commit(repoDir, commitMessage)
        ProcessBuilder("git", "push", "origin", "main").directory(repoDir).inheritIO().start().waitFor()
    }

    private fun commit(repoDir: File, commitMessage: String) {
        ProcessBuilder("git", "add", ".").directory(repoDir).inheritIO().start().waitFor()
        ProcessBuilder("git", "commit", "-m", commitMessage).directory(repoDir).inheritIO().start().waitFor()
    }

    private fun bccReportJson(reportDir: File): JsonNode {
        val htmlReport = reportDir.resolve("html/index.html")
        assertThat(htmlReport).exists()

        val html = htmlReport.readText()
        val reportLiteral = html
            .substringAfter("const report = ")
            .substringBefore("const specmaticConfig =")
            .trim()
            .removeSuffix(";")
            .trim()

        return objectMapper.readTree(reportLiteral)
    }

    private fun <T> withRegisteredService(service: Class<*>, implementation: Class<*>, block: () -> T): T {
        val previousContextClassLoader = Thread.currentThread().contextClassLoader
        val serviceLoaderDir = Files.createTempDirectory("specmatic-service-loader-test")
        val serviceFile = serviceLoaderDir.resolve("META-INF/services/${service.name}")
        Files.createDirectories(serviceFile.parent)
        Files.writeString(serviceFile, "${implementation.name}\n")

        val classLoader = URLClassLoader(arrayOf(serviceLoaderDir.toUri().toURL()), previousContextClassLoader)
        Thread.currentThread().contextClassLoader = classLoader

        return try {
            block()
        } finally {
            Thread.currentThread().contextClassLoader = previousContextClassLoader
            classLoader.close()
            serviceLoaderDir.toFile().deleteRecursively()
        }
    }

    @Nested
    inner class SharedExternalFile {
        // When a shared external schema file changes, the command flags EVERY spec that refers to it,
        // and each referring spec's breadcrumb points at the change in that shared external file.
        @Test
        fun `a breaking change in a shared external file flags every referring spec at the external location`() {
            tempDir.resolve("common.yaml").writeText(productBase("string"))
            tempDir.resolve("apiA.yaml").writeText(referringSpec("A"))
            tempDir.resolve("apiB.yaml").writeText(referringSpec("B"))
            commit(tempDir, "Initial contracts")
            val baseBranch = SystemGit(tempDir.absolutePath).currentBranch()

            ProcessBuilder("git", "switch", "-c", "break-shared-schema").directory(tempDir).inheritIO().start().waitFor()
            tempDir.resolve("common.yaml").writeText(productBase("integer"))
            commit(tempDir, "Change name type in the shared schema")

            val (stdOut, exitCode) = captureStandardOutput {
                BackwardCompatibilityCheckCommandV2().apply {
                    options.repoDir = tempDir.canonicalPath
                    options.baseBranch = baseBranch
                }.call()
            }

            assertThat(exitCode).isEqualTo(1)
            val apiA = "apiA.yaml"
            val apiB = "apiB.yaml"

            // The verdict line prints OS-native paths, so normalize separators before matching the
            // forward-slash invariant paths (Windows would otherwise emit backslashes).
            val normalizedOut = stdOut.replace('\\', '/')
            assertThat(normalizedOut).contains("Verdict for spec $apiA:")
            assertThat(normalizedOut).contains("Verdict for spec $apiB:")
            // Both referring specs are incompatible, each annotated at the change in the shared file.
            // The location is a chain that starts at each spec's own ref use-site and ends at the
            // shared external change, so the tail is reached twice — once per referring spec. Source
            // locations are rendered relative to the repo root.
            assertThat(normalizedOut.split("-> common.yaml:10:9)")).hasSize(3)
            assertThat(normalizedOut).contains("(apiA.yaml:")
            assertThat(normalizedOut).contains("(apiB.yaml:")
        }

        private fun productBase(type: String) = """
            openapi: 3.0.0
            info: { title: common, version: "1" }
            paths: {}
            components:
              schemas:
                ProductBase:
                  type: object
                  required: [name]
                  properties:
                    name: { type: $type }
        """.trimIndent()

        private fun referringSpec(title: String) = """
            openapi: 3.0.0
            info: { title: $title, version: "1" }
            paths:
              /products:
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: "./common.yaml#/components/schemas/ProductBase"
                  responses: { "200": { description: ok } }
        """.trimIndent()
    }

}

class MixedResultBackwardCompatibilityCheckHook : BackwardCompatibilityCheckHook {
    override fun check(
        backwardCompatibilityResult: Results,
        centralRepoUrl: String,
        specFilePath: String,
    ): Pair<CompatibilityResult, List<OperationUsageResponse>?> = when (specFilePath) {
        PASSED_SPEC -> CompatibilityResult.PASSED to emptyList()
        FAILED_SPEC -> CompatibilityResult.FAILED to emptyList()
        else -> CompatibilityResult.UNKNOWN to emptyList()
    }

    override fun logStartedMessage(failedSpecs: List<ProcessedSpec>) {
        println("Validating ${failedSpecs.size} failed spec(s) with the backward compatibility check hook...")
    }

    override fun logCompletedMessage() {
        println("Hook validation complete.")
    }

    override fun failedVerdictAndMessage(
        processedSpec: ProcessedSpec,
        strictMode: Boolean,
    ): Pair<CompatibilityResult, String> = when (processedSpec.computedCompatibilityCheckHookResult.first) {
        CompatibilityResult.PASSED ->
            CompatibilityResult.PASSED to "(HOOK: PASSED) The hook declared the failing spec as backward compatible"

        CompatibilityResult.FAILED ->
            CompatibilityResult.FAILED to "(HOOK: FAILED) The hook did not declare the spec as backward compatible"

        CompatibilityResult.UNKNOWN ->
            CompatibilityResult.UNKNOWN to "(HOOK: UNKNOWN) The hook could not determine whether the spec is backward compatible"
    }

    companion object {
        const val PASSED_SPEC = "hook-passed.yaml"
        const val FAILED_SPEC = "hook-failed.yaml"
    }
}

class AlwaysPassingBackwardCompatibilityCheckHook : BackwardCompatibilityCheckHook {
    override fun check(
        backwardCompatibilityResult: Results,
        centralRepoUrl: String,
        specFilePath: String,
    ): Pair<CompatibilityResult, List<OperationUsageResponse>?> = CompatibilityResult.PASSED to emptyList()

    override fun logStartedMessage(failedSpecs: List<ProcessedSpec>) {
        println("Validating ${failedSpecs.size} failed spec(s) with the backward compatibility check hook...")
    }

    override fun logCompletedMessage() {
        println("Hook validation complete.")
    }

    override fun failedVerdictAndMessage(
        processedSpec: ProcessedSpec,
        strictMode: Boolean,
    ): Pair<CompatibilityResult, String> =
        CompatibilityResult.PASSED to "(HOOK: PASSED) The hook declared the failing spec as backward compatible"
}
