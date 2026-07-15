package io.specmatic.mock

import io.specmatic.core.Result
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.time.Duration

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

        val schemaValidation = FuzzyExampleJsonValidator.matches(jsonObject)
        if (schemaValidation !is Result.Success) {
            return schemaValidation
        }

        return validateFixtureDurations(jsonObject)
    }

    private fun validateFixtureDurations(example: JSONObjectValue): Result {
        for (hook in listOf("before", "after")) {
            val fixtures = example.jsonObject[hook] as? JSONArrayValue ?: continue

            fixtures.list.forEachIndexed { index, fixtureValue ->
                val fixture = fixtureValue as? JSONObjectValue ?: return@forEachIndexed

                listOf(
                    fixture.jsonObject["wait"] to "$hook[$index].wait",
                    fixture.jsonObject["timeout"] to "$hook[$index].timeout",
                    (fixture.jsonObject["retry"] as? JSONObjectValue)?.jsonObject?.get("delay") to "$hook[$index].retry.delay"
                ).forEach { (value, path) ->
                    validateDurationField(value, path)?.let { return it }
                }
            }
        }

        return Result.Success()
    }

    private fun validateDurationField(rawValue: io.specmatic.core.value.Value?, path: String): Result.Failure? =
        (rawValue as? StringValue)?.let { stringValue ->
            runCatching { Duration.parse(stringValue.string) }
                .exceptionOrNull()
                ?.let { Result.Failure("Expected $path to be a valid ISO 8601 duration, but got ${stringValue.toStringLiteral()}") }
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
