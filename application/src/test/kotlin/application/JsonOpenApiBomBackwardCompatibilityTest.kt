package application

import application.backwardCompatibility.BackwardCompatibilityCheckCommandV2
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.git.SystemGit
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicensedProduct
import io.specmatic.license.core.SpecmaticFeature
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.stub.isOpenAPI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JsonOpenApiBomBackwardCompatibilityTest {
    @TempDir
    private lateinit var tempDir: File

    @TempDir
    private lateinit var remoteDir: File

    @BeforeEach
    fun setup() {
        runGit(remoteDir, "init", "--bare")
        runGit(tempDir, "init")
        runGit(tempDir, "symbolic-ref", "HEAD", "refs/heads/main")
        runGit(tempDir, "config", "--local", "user.name", "developer")
        runGit(tempDir, "config", "--local", "user.email", "developer@example.com")
        runGit(tempDir, "remote", "add", "origin", remoteDir.absolutePath)
    }

    @Test
    fun `reports a breaking JSON OpenAPI change when the file has no BOM`() {
        val report = runBreakingJsonChange(bom = false)

        assertThat(report.exitCode).isEqualTo(1)
        assertThat(report.stdout).contains("(INCOMPATIBLE)")
        assertThat(report.stdout).contains("Files checked: 1 (Passed: 0, Failed: 1)")
        assertThat(report.stdout).doesNotContain("No specs were changed")
    }

    @Test
    fun `reports a breaking JSON OpenAPI change when the file has a UTF-8 BOM`() {
        val report = runBreakingJsonChange(bom = true)
        val detail = buildString {
            appendLine("exit=${report.exitCode}")
            appendLine("gates=${report.gates}")
            appendLine(report.stdout)
        }

        assertThat(report.stdout).withFailMessage(detail).doesNotContain("No specs were changed")
        assertThat(report.exitCode).withFailMessage(detail).isEqualTo(1)
        assertThat(report.stdout).withFailMessage(detail).contains("(INCOMPATIBLE)")
        assertThat(report.stdout).withFailMessage(detail).contains("Files checked: 1 (Passed: 0, Failed: 1)")
    }

    private fun runBreakingJsonChange(bom: Boolean): ChangeReport {
        val spec = tempDir.resolve("swagger.json")
        spec.writeJson(openApiJson(valueType = "string"), bom)
        runGit(tempDir, "add", "swagger.json")
        runGit(tempDir, "commit", "-m", "Initial JSON OpenAPI spec")
        runGit(tempDir, "push", "origin", "main")
        runGit(tempDir, "checkout", "-b", "breaking-change")
        spec.writeJson(openApiJson(valueType = "integer"), bom)
        runGit(tempDir, "add", "swagger.json")
        runGit(tempDir, "commit", "-m", "Change response value from string to integer")

        val changed = SystemGit(tempDir.absolutePath).getFilesChangedInCurrentBranch("origin/main")
        val gates = classify(spec)
        val (stdout, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
            BackwardCompatibilityCheckCommandV2().apply {
                options.repoDir = tempDir.canonicalPath
                options.baseBranch = "origin/main"
                options.targetPath = "swagger.json"
            }.call()
        }

        val gatesText = "gitChanged=$changed $gates"
        return ChangeReport(
            exitCode = exitCode,
            stdout = stdout,
            gates = gatesText,
        )
    }

    private fun classify(spec: File): String {
        val text = spec.readText()
        val first = text.firstOrNull()
        val jackson = runCatching { ObjectMapper().readValue(text, Map::class.java) }
            .exceptionOrNull()
            ?.let { "${it.javaClass.simpleName}: ${it.message}" }
            ?: "parsed"
        val yaml = runCatching { ObjectMapper(YAMLFactory()).readValue(text, Map::class.java) }
            .exceptionOrNull()
            ?.let { "${it.javaClass.simpleName}: ${it.message}" }
            ?: "parsed"
        val command = BackwardCompatibilityCheckCommandV2()
        val format = with(command) { spec.isValidFileFormat() }
        val validSpec = with(command) { spec.isValidSpec() }
        val parsable = OpenApiSpecification.isParsable(spec.path)
        val loaded = runCatching { OpenApiSpecification.fromFile(spec.path).toFeature().scenarios.size }
            .fold(
                onSuccess = { "scenarios=$it" },
                onFailure = { "${it.javaClass.simpleName}: ${it.message}" },
            )
        val openApiMarker = isOpenAPI(spec.path, logFailure = false)
        val prefix = spec.readBytes().take(3).joinToString(" ") { "%02X".format(it) }
        return "prefix=$prefix first=${first?.let { "U+${it.code.toString(16).uppercase()}" }} " +
            "jackson=$jackson yamlJackson=$yaml isValidFileFormat=$format isValidSpec=$validSpec " +
            "isParsable=$parsable fromFile=$loaded isOpenAPI=$openApiMarker"
    }

    private fun File.writeJson(content: String, bom: Boolean) {
        val encoded = content.toByteArray(Charsets.UTF_8)
        val bytes = if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + encoded else encoded
        writeBytes(bytes)
    }

    private fun openApiJson(valueType: String) = """
        {
          "openapi": "3.0.0",
          "info": { "title": "API", "version": "1.0.0" },
          "paths": {
            "/a": {
              "get": {
                "responses": {
                  "200": {
                    "description": "ok",
                    "content": {
                      "application/json": {
                        "schema": {
                          "type": "object",
                          "required": ["value"],
                          "properties": { "value": { "type": "$valueType" } }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    private fun runGit(repoDir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(repoDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) { "git ${args.joinToString(" ")} failed ($code): $output" }
    }

    private data class ChangeReport(val exitCode: Int, val stdout: String, val gates: String)

    companion object {
        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            LicenseResolver.utilize(
                product = LicensedProduct.OPEN_SOURCE,
                feature = SpecmaticFeature.BACKWARD_COMPATIBILITY_CHECK,
                protocol = listOf(SpecmaticProtocol.HTTP)
            )
        }
    }
}
