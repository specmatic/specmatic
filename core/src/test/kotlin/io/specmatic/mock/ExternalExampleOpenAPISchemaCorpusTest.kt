package io.specmatic.mock

import io.specmatic.core.Result
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.JSONObjectValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

class ExternalExampleOpenAPISchemaCorpusTest {
    @TestFactory
    fun `yaml schema validator should accept valid JSON fixtures and reject invalid ones`(): List<DynamicTest> {
        return dynamicValidationTests(
            validFiles = filesUnder(JSON_PASS_DIR),
            invalidFiles = filesUnder(JSON_FAIL_DIR),
            validate = ::validateAgainstYamlSchema
        )
    }

    private fun dynamicValidationTests(
        validFiles: List<File>,
        invalidFiles: List<File>,
        validate: (File) -> Result
    ): List<DynamicTest> {
        val validTests = validFiles.map { file ->
            DynamicTest.dynamicTest("YAML schema accepts ${file.relativePath()}") {
                assertThat(validate(file))
                    .withFailMessage("Expected ${file.relativePath()} to pass YAML schema validation")
                    .isInstanceOf(Result.Success::class.java)
            }
        }

        val invalidTests = invalidFiles.map { file ->
            DynamicTest.dynamicTest("YAML schema rejects ${file.relativePath()}") {
                assertThat(validate(file))
                    .withFailMessage("Expected ${file.relativePath()} to fail YAML schema validation")
                    .isNotInstanceOf(Result.Success::class.java)
            }
        }

        return validTests + invalidTests
    }

    private fun validateAgainstYamlSchema(file: File): Result {
        val value = try {
            parsedJSON(file.readText())
        } catch (e: Exception) {
            return Result.Failure(e.message ?: "Failed to parse ${file.relativePath()} as JSON")
        }
        val jsonObject = value as? JSONObjectValue
            ?: return Result.Failure("Expected ${file.relativePath()} to parse into a JSON object")

        return FuzzyExampleJsonValidator.matches(jsonObject)
    }

    private fun filesUnder(directory: String): List<File> {
        return File(directory)
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.relativePath() }
            .toList()
    }

    private fun File.relativePath(): String = relativeTo(File(".")).invariantSeparatorsPath

    companion object {
        private const val JSON_PASS_DIR = "src/test/resources/schemas/subset_expected_to_pass"
        private const val JSON_FAIL_DIR = "src/test/resources/schemas/subset_expected_to_fail"
    }
}
