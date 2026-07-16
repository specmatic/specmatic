package io.specmatic.mock

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.specmatic.core.utilities.yamlMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatNoException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

class ExternalExampleJSONSchemaCorpusTest {
    private val jsonSchemaValidator: Schema by lazy {
        val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        val schemaContent = File(JSON_SCHEMA_PATH).readText()
        schemaRegistry.getSchema(schemaContent, InputFormat.JSON)
    }

    @TestFactory
    fun `published JSON schema should accept valid JSON fixtures and reject invalid ones`(): List<DynamicTest> {
        return dynamicValidationTests(
            validFiles = filesUnder(JSON_PASS_DIR, ".json"),
            invalidFiles = filesUnder(JSON_FAIL_DIR, ".json"),
            validatorName = "published JSON schema",
            validate = ::validateAgainstPublishedJsonSchema
        )
    }

    private fun dynamicValidationTests(
        validFiles: List<File>,
        invalidFiles: List<File>,
        validatorName: String,
        validate: (File) -> Unit
    ): List<DynamicTest> {
        val validTests = validFiles.map { file ->
            DynamicTest.dynamicTest("$validatorName accepts ${file.relativePath()}") {
                assertThatNoException().isThrownBy { validate(file) }
            }
        }

        val invalidTests = invalidFiles.map { file ->
            DynamicTest.dynamicTest("$validatorName rejects ${file.relativePath()}") {
                assertThatThrownBy { validate(file) }
                    .isInstanceOf(AssertionError::class.java)
            }
        }

        return validTests + invalidTests
    }

    private fun validateAgainstPublishedJsonSchema(file: File) {
        val messages = jsonSchemaValidator.validate(file.readText(), InputFormat.JSON) { executionContext ->
            executionContext.executionConfig { executionConfig -> executionConfig.formatAssertionsEnabled(true) }
        }
        assertThat(messages)
            .withFailMessage(
                "Expected ${file.relativePath()} to satisfy $JSON_SCHEMA_PATH but got:%n%s",
                messages.joinToString(System.lineSeparator()) { it.message }
            )
            .isEmpty()
    }

    private fun filesUnder(directory: String, extension: String): List<File> {
        return File(directory)
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(extension) }
            .sortedBy { it.relativePath() }
            .toList()
    }

    private fun File.relativePath(): String = relativeTo(File(".")).invariantSeparatorsPath

    companion object {
        private const val JSON_SCHEMA_PATH = "src/main/resources/schemas/external_examples.schema.json"
        private const val JSON_PASS_DIR = "src/test/resources/schemas/subset_expected_to_pass"
        private const val JSON_FAIL_DIR = "src/test/resources/schemas/subset_expected_to_fail"
    }
}
