package io.specmatic.mock

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.path.NodePath
import com.networknt.schema.path.PathType
import io.specmatic.core.Result
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.JSONObjectValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatNoException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
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
            validFiles = filesUnder(JSON_PASS_DIR).filterNot(::isPublishedSchemaOnly),
            invalidFiles = filesUnder(JSON_FAIL_DIR).filterNot(::isPublishedSchemaOnly)
        )
    }

    @TestFactory
    fun `published JSON schema should accept valid JSON fixtures and reject invalid ones`(): List<DynamicTest> {
        return publishedJsonSchemaValidationTests(
            validFiles = filesUnder(JSON_PASS_DIR),
            invalidFiles = filesUnder(JSON_FAIL_DIR)
        )
    }

    @TestFactory
    fun `published JSON sub-schemas should accept valid values and reject invalid ones`(): List<DynamicTest> {
        val root = File(JSON_SUBSCHEMA_DIR)
        return root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.flatMap { definitionDir ->
            val schema = schemaAt($$"$defs.$${definitionDir.name}")
            val pass = filesUnder(definitionDir.resolve("pass").path).map { file ->
                DynamicTest.dynamicTest("Published ${definitionDir.name} accepts ${file.relativePath()}") {
                    assertThatNoException().isThrownBy {
                        validateAgainstPublishedSubSchema(schema, file)
                    }
                }
            }

            val fail = filesUnder(definitionDir.resolve("fail").path).map { file ->
                DynamicTest.dynamicTest("Published ${definitionDir.name} rejects ${file.relativePath()}") {
                    assertThatThrownBy {
                        validateAgainstPublishedSubSchema(schema, file)
                    }.isInstanceOf(AssertionError::class.java)
                }
            }

            pass + fail
        }.orEmpty()
    }

    @Test
    fun `published schema annotations contain valid examples and snippets`() {
        val schemaNode = publishedJsonSchema.getSchemaNode()
        val definitions = listOf(
            "Retry",
            "Example",
            "Fixture",
            "AnyValue",
            "HttpFixture",
            "FixtureHooks",
            "RequestSchema",
            "MultiPartFile",
            "AnyArrayValue",
            "PartialExample",
            "ResponseSchema",
            "AnyObjectValue",
            "AnyScalarValue",
            "MultiPartContent",
            "FixtureExecuteFor",
            "MultiPartFormData",
            "MatcherExpression",
            "HTTPFixtureRequest",
            "HTTPFixtureResponse",
            "SpecmaticExpression",
            "RequestResponseSchema",
            "PatternTokenExpression",
            "TemplateValueExpression",
            "SpecmaticStringExpression",
            "StringOrSpecmaticExpression",
            "PartialRequestResponseSchema",
            "SubstitutionLookupExpression",
            "SubstitutionCaptureExpression",
            "TemplateValueStringExpression",
            "StringOrTemplateValueExpression",
            "BooleanOrTemplateValueExpression",
            "IntegerOrTemplateValueExpression",
            "SubstitutionOrTemplateOrMatcherExpression",
            "SubstitutionOrTemplateOrMatcherStringExpression",
            "AnyValueWithSubstitutionOrTemplateOrMatcher",
            "StringOrSubstitutionOrTemplateOrMatcherExpression",
            "IntegerOrSubstitutionOrTemplateOrMatcherExpression",
        )

        definitions.forEach { definition ->
            val definitionSchema = schemaAt($$"$defs.$$definition")
            val definitionNode = schemaNode.at($$"/$defs/$$definition")

            definitionNode.path("examples").forEach { example ->
                assertThat(definitionSchema.validate(example))
                    .withFailMessage("Example in $definition should satisfy its definition")
                    .isEmpty()
            }

            definitionNode.path("defaultSnippets").forEach { snippet ->
                assertThat(definitionSchema.validate(snippet.path("body")))
                    .withFailMessage("Snippet ${snippet.path("label").asText()} in $definition should satisfy its definition")
                    .isEmpty()
            }
        }

        val propertyPaths = listOf(
            $$"$defs.Metadata.properties.name",
            $$"$defs.Metadata.properties.transient",
            $$"$defs.Metadata.properties.delay-in-seconds",
            $$"$defs.Metadata.properties.delay-in-milliseconds",
            $$"$defs.Metadata.properties.http-stub-id",
            $$"$defs.HttpFixture.properties.wait",
            $$"$defs.HttpFixture.properties.timeout",
            $$"$defs.Retry.properties.max-attempts",
            $$"$defs.Retry.properties.delay",
            $$"$defs.FixtureExecuteFor.properties.scenarios",
            $$"$defs.HTTPFixtureRequest.properties.baseUrl",
            $$"$defs.HTTPFixtureRequest.properties.method",
            $$"$defs.HTTPFixtureRequest.properties.path",
            $$"$defs.HTTPFixtureRequest.properties.headers",
            $$"$defs.HTTPFixtureRequest.properties.body",
            $$"$defs.HTTPFixtureResponse.properties.status",
            $$"$defs.HTTPFixtureResponse.properties.body",
            $$"$defs.RequestSchema.properties.path",
            $$"$defs.RequestSchema.properties.method",
            $$"$defs.RequestSchema.properties.query",
            $$"$defs.RequestSchema.properties.headers",
            $$"$defs.RequestSchema.properties.body",
            $$"$defs.RequestSchema.properties.form-fields",
            $$"$defs.RequestSchema.properties.multipart-formdata",
            $$"$defs.RequestSchema.properties.bodyRegex",
            $$"$defs.ResponseSchema.properties.status",
            $$"$defs.ResponseSchema.properties.headers",
            $$"$defs.ResponseSchema.properties.body",
            $$"$defs.ResponseSchema.properties.externalisedResponseCommand",
            $$"$defs.MultiPartContent.properties.name",
            $$"$defs.MultiPartContent.properties.content",
            $$"$defs.MultiPartContent.properties.contentType",
            $$"$defs.MultiPartFile.properties.contentEncoding",
            $$"$defs.MultiPartFile.properties.contentType",
            $$"$defs.MultiPartFile.properties.filename",
            $$"$defs.MultiPartFile.properties.name",
        )

        propertyPaths.forEach { propertyPath ->
            val propertyNode = schemaNode.at("/${propertyPath.replace('.', '/')}")
            val propertySchema = schemaAt(propertyPath)

            propertyNode.path("examples").forEach { example ->
                assertThat(propertySchema.validate(example))
                    .withFailMessage("Example at $propertyPath should satisfy its property schema")
                    .isEmpty()
            }

            if (propertyNode.has("default")) {
                assertThat(propertySchema.validate(propertyNode.get("default")))
                    .withFailMessage("Default at $propertyPath should satisfy its property schema")
                    .isEmpty()
            }
        }
    }

    private fun schemaAt(jsonPointer: String): Schema {
        val path = jsonPointer.split('.').fold(NodePath(PathType.JSON_POINTER)) { current, segment ->
            current.append(segment)
        }

        return publishedJsonSchema.getSubSchema(path)
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

    private fun validateAgainstPublishedSubSchema(schema: Schema, file: File) {
        val messages = schema.validate(file.readText(), InputFormat.JSON) { executionContext ->
            executionContext.executionConfig { executionConfig -> executionConfig.formatAssertionsEnabled(true) }
        }

        assertThat(messages)
            .withFailMessage(
                "Expected ${file.relativePath()} to satisfy its sub-schema but got:%n%s",
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

    private fun isPublishedSchemaOnly(file: File): Boolean {
        return file.relativeTo(File(JSON_PASS_DIR).parentFile).invariantSeparatorsPath
            .split('/')
            .contains(PUBLISHED_SCHEMA_ONLY_DIR)
    }

    private fun File.relativePath(): String = relativeTo(File(".")).invariantSeparatorsPath

    companion object {
        private const val PUBLISHED_JSON_SCHEMA_PATH = "src/main/resources/schemas/external_examples.schema.json"
        private const val JSON_PASS_DIR = "src/test/resources/schemas/subset_expected_to_pass"
        private const val JSON_FAIL_DIR = "src/test/resources/schemas/subset_expected_to_fail"
        private const val JSON_SUBSCHEMA_DIR = "src/test/resources/schemas/subschemas"
        private const val PUBLISHED_SCHEMA_ONLY_DIR = "_published_schema_only"
    }
}
