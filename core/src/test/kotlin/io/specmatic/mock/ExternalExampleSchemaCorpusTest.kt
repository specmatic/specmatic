package io.specmatic.mock

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.specmatic.core.Result
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.JSONObjectValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatNoException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

class ExternalExampleSchemaCorpusTest {
    private val publishedJsonSchema: Schema by lazy {
        val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        schemaRegistry.getSchema(File(PUBLISHED_JSON_SCHEMA_PATH).readText(), InputFormat.JSON)
    }

    @TestFactory
    fun `internal OpenAPI schema should accept valid JSON fixtures and reject invalid ones`(): List<DynamicTest> {
        return internalSchemaValidationTests(
            validFiles = filesUnder(JSON_PASS_DIR),
            invalidFiles = filesUnder(JSON_FAIL_DIR)
        )
    }

    @TestFactory
    fun `published JSON schema should accept valid JSON fixtures and reject invalid ones`(): List<DynamicTest> {
        return publishedJsonSchemaValidationTests(
            validFiles = filesUnder(JSON_PASS_DIR),
            invalidFiles = filesUnder(JSON_FAIL_DIR)
        )
    }

    private fun internalSchemaValidationTests(validFiles: List<File>, invalidFiles: List<File>): List<DynamicTest> {
        val validTests = validFiles.map { file ->
            DynamicTest.dynamicTest("Internal OpenAPI schema accepts ${file.relativePath()}") {
                assertThat(validateAgainstInternalSchema(file))
                    .withFailMessage("Expected ${file.relativePath()} to pass internal OpenAPI schema validation")
                    .isInstanceOf(Result.Success::class.java)
            }
        }

        val invalidTests = invalidFiles.map { file ->
            DynamicTest.dynamicTest("Internal OpenAPI schema rejects ${file.relativePath()}") {
                assertThat(validateAgainstInternalSchema(file))
                    .withFailMessage("Expected ${file.relativePath()} to fail internal OpenAPI schema validation")
                    .isNotInstanceOf(Result.Success::class.java)
            }
        }

        return validTests + invalidTests
    }

    private fun publishedJsonSchemaValidationTests(validFiles: List<File>, invalidFiles: List<File>): List<DynamicTest> {
        val validTests = validFiles.map { file ->
            DynamicTest.dynamicTest("Published JSON schema accepts ${file.relativePath()}") {
                assertThatNoException().isThrownBy { validateAgainstPublishedJsonSchema(file) }
            }
        }

        val invalidTests = invalidFiles.map { file ->
            DynamicTest.dynamicTest("Published JSON schema rejects ${file.relativePath()}") {
                assertThatThrownBy { validateAgainstPublishedJsonSchema(file) }
                    .isInstanceOf(AssertionError::class.java)
            }
        }

        return validTests + invalidTests
    }

    private fun validateAgainstInternalSchema(file: File): Result {
        val value = try {
            parsedJSON(file.readText())
        } catch (e: Exception) {
            return Result.Failure(e.message ?: "Failed to parse ${file.relativePath()} as JSON")
        }

        val jsonObject = value as? JSONObjectValue ?: return Result.Failure("Expected ${file.relativePath()} to parse into a JSON object")
        return FuzzyExampleJsonValidator.matches(jsonObject)
    }

    private fun validateAgainstPublishedJsonSchema(file: File) {
        val messages = publishedJsonSchema.validate(file.readText(), InputFormat.JSON) { executionContext ->
            executionContext.executionConfig { executionConfig -> executionConfig.formatAssertionsEnabled(true) }
        }

        assertThat(messages)
            .withFailMessage(
                "Expected ${file.relativePath()} to satisfy $PUBLISHED_JSON_SCHEMA_PATH but got:%n%s",
                messages.joinToString(System.lineSeparator()) { it.message }
            )
            .isEmpty()
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
        private const val PUBLISHED_JSON_SCHEMA_PATH = "src/main/resources/schemas/external_examples.schema.json"
        private const val JSON_PASS_DIR = "src/test/resources/schemas/subset_expected_to_pass"
        private const val JSON_FAIL_DIR = "src/test/resources/schemas/subset_expected_to_fail"
    }
}
