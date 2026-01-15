package integration_tests

import integration_tests.LenientParseTestCase.Companion.multiVersionLenientCase
import integration_tests.LenientParseTestCase.Companion.singleVersionLenientCase
import io.specmatic.conversions.OpenApiLintViolations
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.REASONABLE_STRING_LENGTH
import io.specmatic.core.Feature
import io.specmatic.core.IssueSeverity
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.yamlMapper
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.collections.set

private typealias AnyValueMap = MutableMap<String, Any?>

private fun AnyValueMap.map(key: String, block: AnyValueMap.() -> Unit): AnyValueMap {
    val value = computeIfAbsent(key) { mutableMapOf<String, Any?>() }
    require(value is MutableMap<*, *>) { "$key is not a map" }
    @Suppress("UNCHECKED_CAST")
    val m = value as AnyValueMap
    block(m)
    return m
}

private fun AnyValueMap.list(key: String, block: MutableList<AnyValueMap>.() -> Unit) {
    val value = computeIfAbsent(key) { mutableListOf<AnyValueMap>() }
    require(value is MutableList<*>) { "$key is not a list" }
    @Suppress("UNCHECKED_CAST")
    block(value as MutableList<AnyValueMap>)
}

data class LenientParseTestCase(val openApi: Map<String, Any?>, val checks: List<(Feature) -> Unit>, val asserts: List<RuleViolationAssertion> = emptyList()) {
    companion object {
        fun singleVersionLenientCase(name: String, version: OpenApiVersion, block: Builder.() -> Unit): List<Arguments> {
            val builder = Builder()
            builder.block()
            val testCase = builder.build()
            return listOf(Arguments.of(version, Named.of(name, testCase)))
        }

        fun multiVersionLenientCase(name: String, vararg versions: OpenApiVersion, block: Builder.() -> Unit): List<Arguments> {
            val builder = Builder()
            builder.block()
            val testCase = builder.build()
            return versions.map { Arguments.of(it, Named.of(name, testCase)) }
        }
    }

    class Builder {
        private lateinit var openApi: Map<String, Any?>
        private var asserts: MutableList<RuleViolationAssertion> = mutableListOf()
        private var checks: MutableList<(Feature) -> Unit> = mutableListOf()

        fun openApi(block: OpenApiDsl.() -> Unit) {
            val dsl = OpenApiDsl()
            dsl.block()
            openApi = dsl.build()
        }

        fun assert(path: String? = null, block: RuleViolationAssertion.Builder.() -> Unit) {
            val ruleViolationBuilder = RuleViolationAssertion.Builder(path)
            block(ruleViolationBuilder)
            asserts.add(ruleViolationBuilder.build())
        }

        fun check(block: (Feature) -> Unit) {
            checks.add(block)
        }

        fun build(): LenientParseTestCase = LenientParseTestCase(openApi, checks, asserts)
    }

    class OpenApiDsl {
        private val root: AnyValueMap = mutableMapOf()

        fun openapi(version: String) {
            root["openapi"] = version
        }

        fun info(block: AnyValueMap.() -> Unit) {
            root.map("info", block)
        }

        fun paths(block: PathsDsl.() -> Unit) {
            val paths = root.map("paths") { }
            block(PathsDsl(paths))
        }

        fun components(block: ComponentsDsl.() -> Unit) {
            val components = root.map("components") { }
            block(ComponentsDsl(components))
        }

        fun build(): AnyValueMap = root

        class PathsDsl(private val paths: AnyValueMap) {

            fun path(path: String, block: PathItemDsl.() -> Unit) {
                val item = paths.map(path) { }
                block(PathItemDsl(item))
            }
        }

        class PathItemDsl(private val item: AnyValueMap) {
            fun operation(method: String, block: OperationDsl.() -> Unit) {
                val op = item.map(method) { }
                block(OperationDsl(op))
            }
        }

        class OperationDsl(private val op: AnyValueMap) {
            fun parameter(block: AnyValueMap.() -> Unit) {
                op.list("parameters") {
                    add(mutableMapOf<String, Any?>().apply(block))
                }
            }

            fun requestBody(block: RequestBodyDsl.() -> Unit) {
                val body = mutableMapOf<String, Any?>()
                block(RequestBodyDsl(body))
                op["requestBody"] = body
            }

            fun requestBodyRef(name: String) {
                op["requestBody"] = mapOf("\$ref" to "#/components/requestBodies/$name")
            }

            fun response(status: Any, block: AnyValueMap.() -> Unit) {
                op.map("responses") {
                    put(status.toString(), mutableMapOf<String, Any?>().apply(block))
                }
            }

            fun security(block: MutableList<AnyValueMap>.() -> Unit) {
                val list = mutableListOf<AnyValueMap>()
                block(list)
                op["security"] = list
            }
        }

        class RequestBodyDsl(private val body: AnyValueMap) {
            fun required(value: Boolean = true) {
                body["required"] = value
            }

            fun content(block: ContentDsl.() -> Unit) {
                val content = body.map("content") { }
                block(ContentDsl(content))
            }
        }

        class ContentDsl(private val content: AnyValueMap) {
            fun mediaType(type: String, block: MediaTypeDsl.() -> Unit) {
                val media = content.map(type) { }
                block(MediaTypeDsl(media))
            }
        }

        class MediaTypeDsl(private val media: AnyValueMap) {
            fun schema(block: AnyValueMap.() -> Unit) {
                media["schema"] = mutableMapOf<String, Any?>().apply(block)
            }

            fun schemaRef(name: String) {
                media["schema"] = mapOf("\$ref" to "#/components/schemas/$name")
            }
        }

        class ComponentsDsl(private val components: AnyValueMap) {
            fun schemas(block: SchemasDsl.() -> Unit) {
                val schemas = components.map("schemas") { }
                block(SchemasDsl(schemas))
            }

            fun securitySchemes(block: AnyValueMap.() -> Unit) {
                components.map("securitySchemes", block)
            }

            fun requestBodies(block: RequestBodiesDsl.() -> Unit) {
                val bodies = components.map("requestBodies") { }
                block(RequestBodiesDsl(bodies))
            }
        }

        class SchemasDsl(private val schemas: AnyValueMap) {
            fun schema(name: String, block: AnyValueMap.() -> Unit) {
                schemas[name] = mutableMapOf<String, Any?>().apply(block)
            }
        }

        class RequestBodiesDsl(private val bodies: AnyValueMap) {
            fun requestBody(name: String, block: RequestBodyDsl.() -> Unit) {
                val body = mutableMapOf<String, Any?>()
                block(RequestBodyDsl(body))
                bodies[name] = body
            }
        }
    }
}

class LenientParserTest {
    private fun runLenientCase(version: OpenApiVersion, case: LenientParseTestCase) {
        val root = linkedMapOf("openapi" to version.value, "info" to mapOf("title" to "Test", "version" to "1.0")) + case.openApi.filterKeys { it != "info" && it != "openapi" }
        val openApiYamlString = yamlMapper.writeValueAsString(root)
        logger.log(openApiYamlString)
        logger.boundary()

        val specification = OpenApiSpecification.fromYAML(openApiYamlString, "TEST")
        val (feature, result) = assertDoesNotThrow { specification.toFeatureLenient() }
        val issues = result.toIssues()

        println("paths:\n\t- ${issues.joinToString(separator = "\n\t- ", transform = { it.breadCrumb })}\n")
        println("report:\n\n${result.reportString()}")
        case.asserts.forEach { it.assertViolation(result) }
        case.checks.forEach { it.invoke(feature) }
    }

    @ParameterizedTest
    @MethodSource("pathParameterTestCases")
    fun `path parameter test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("queryParameterTestCases")
    fun `query parameter test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("headerParameterTestCases")
    fun `header parameter test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("requestBodyTestCases")
    fun `request body test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    companion object {
        @JvmStatic // DONE
        fun pathParameterTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "missing", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test/{id}") {
                                operation("get") {
                                    parameter {
                                        put("name", "queryKey")
                                        put("in", "query")
                                        put("schema", mapOf("type" to "string"))
                                        put("required", true)
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(2); totalViolations(2) }
                    assert("paths./test/{id}.get.parameters[-1]") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toContainViolation(OpenApiLintViolations.PATH_PARAMETER_MISSING)
                        toMatchText("Expected path parameter with name id is missing, defaulting to empty schema")
                    }
                    assert("paths./test/{id}.get.parameters[-1].schema") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },

                multiVersionLenientCase(name = "has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test/{id}") {
                                operation("get") {
                                    parameter {
                                        put("name", "id")
                                        put("in", "path")
                                        put("required", true)
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(2); totalViolations(2) }
                    assert("paths./test/{id}.get.parameters[0]") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toContainViolation(OpenApiLintViolations.INVALID_PARAMETER_DEFINITION)
                        toMatchText("Parameter has no schema defined, defaulting to empty schema")
                    }
                    assert("paths./test/{id}.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },
                multiVersionLenientCase(name = "refed out and has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test/{id}") {
                                operation("get") {
                                    parameter {
                                        put("name", "id")
                                        put("in", "path")
                                        put("required", true)
                                        put("schema", mapOf("\$ref" to "#/components/schemas/PathItem"))
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("PathItem", block = {})
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.PathItem") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },

                multiVersionLenientCase(name = "schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test/{id}") {
                                operation("get") {
                                    parameter {
                                        put("name", "id")
                                        put("in", "path")
                                        put("schema", mapOf("type" to "string", "minLength" to REASONABLE_STRING_LENGTH.plus(10)))
                                        put("required", true)
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test/{id}.get.parameters[0].schema.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("We will use a more reasonable minLength of 4MB")
                    }
                },
                multiVersionLenientCase(name = "refed out schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test/{id}") {
                                operation("get") {
                                    parameter {
                                        put("name", "id")
                                        put("in", "path")
                                        put("schema", mapOf("\$ref" to "#/components/schemas/TooLongString"))
                                        put("required", true)
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("TooLongString") {
                                        put("type", "string")
                                        put("minLength", REASONABLE_STRING_LENGTH.plus(10))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongString.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("We will use a more reasonable minLength of 4MB")
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic // DONE
        fun queryParameterTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "q")
                                        put("in", "query")
                                        put("required", true)
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(2); totalViolations(2) }
                    assert("paths./test.get.parameters[0]") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toContainViolation(OpenApiLintViolations.INVALID_PARAMETER_DEFINITION)
                        toMatchText("Parameter has no schema defined, defaulting to empty schema")
                    }
                    assert("paths./test.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },
                multiVersionLenientCase(name = "refed out and has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "q")
                                        put("in", "query")
                                        put("required", true)
                                        put("schema", mapOf("\$ref" to "#/components/schemas/QueryItem"))
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("QueryItem", block = {})
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.QueryItem") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },

                multiVersionLenientCase(name = "schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "q")
                                        put("in", "query")
                                        put("schema", mapOf("type" to "string", "minLength" to REASONABLE_STRING_LENGTH.plus(10)))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].schema.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("We will use a more reasonable minLength of 4MB")
                    }
                },
                multiVersionLenientCase(name = "refed out schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "q")
                                        put("in", "query")
                                        put("required", true)
                                        put("schema", mapOf("\$ref" to "#/components/schemas/TooLongQueryString"))
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("TooLongQueryString") {
                                        put("type", "string")
                                        put("minLength", REASONABLE_STRING_LENGTH.plus(10))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongQueryString.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("We will use a more reasonable minLength of 4MB")
                    }
                },

                multiVersionLenientCase(name = "array with no items schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "ids")
                                        put("in", "query")
                                        put("schema", mapOf("type" to "array"))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(2); totalViolations(2) }
                    assert("paths./test.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("Array Parameter has no items schema defined, defaulting to empty schema")
                    }
                    assert("paths./test.get.parameters[0].schema.items") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },
                multiVersionLenientCase(name = "refed out array with no items schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "ids")
                                        put("in", "query")
                                        put("schema", mapOf("\$ref" to "#/components/schemas/IdArray"))
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("IdArray") {
                                        put("type", "array")
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(2); totalViolations(1) }
                    assert("components.schemas.IdArray") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("No items schema defined for array schema defaulting to empty schema")
                    }
                    assert("components.schemas.IdArray.items") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },

                multiVersionLenientCase(name = "array with empty items schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "ids")
                                        put("in", "query")
                                        put("schema", mapOf("type" to "array", "items" to emptyMap<String, Any>()))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].schema.items") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },
                multiVersionLenientCase(name = "refed out array with empty items schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "ids")
                                        put("in", "query")
                                        put("schema", mapOf("\$ref" to "#/components/schemas/IdArray"))
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("IdArray") {
                                        put("type", "array")
                                        put("items", emptyMap<String, Any>())
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.IdArray.items") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },

                multiVersionLenientCase(name = "object schema not supported", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "filter")
                                        put("in", "query")
                                        put("schema", mapOf("type" to "object"))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.UNSUPPORTED_FEATURE)
                        toMatchText("Query parameter filter is an object, and not yet supported")
                    }
                },
                multiVersionLenientCase(name = "refed out object schema not supported", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "filter")
                                        put("in", "query")
                                        put("schema", mapOf("\$ref" to "#/components/schemas/FilterObject"))
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("FilterObject") {
                                        put("type", "object")
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.UNSUPPORTED_FEATURE)
                        toMatchText("Query parameter filter is an object, and not yet supported")
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun headerParameterTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "X-Request-Id")
                                        put("in", "header")
                                        put("required", true)
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(2); totalViolations(2) }
                    assert("paths./test.get.parameters[0]") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toContainViolation(OpenApiLintViolations.INVALID_PARAMETER_DEFINITION)
                        toMatchText("Parameter has no schema defined, defaulting to empty schema")
                    }
                    assert("paths./test.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },
                multiVersionLenientCase(name = "refed out and has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "X-Request-Id")
                                        put("in", "header")
                                        put("required", true)
                                        put("schema", mapOf("\$ref" to "#/components/schemas/HeaderId"))
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("HeaderId", block = {})
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.HeaderId") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },

                multiVersionLenientCase(name = "array with no items schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "X-Ids")
                                        put("in", "header")
                                        put("schema", mapOf("type" to "array"))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(2); totalViolations(2) }
                    assert("paths./test.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toContainViolation(OpenApiLintViolations.INVALID_PARAMETER_DEFINITION)
                        toMatchText("Array Parameter has no items schema defined, defaulting to empty schema")
                    }
                    assert("paths./test.get.parameters[0].schema.items") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },
                multiVersionLenientCase(name = "refed out array with no items schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "X-Ids")
                                        put("in", "header")
                                        put("schema", mapOf("\$ref" to "#/components/schemas/HeaderIdArray"))
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("HeaderIdArray") {
                                        put("type", "array")
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(2); totalViolations(1) }
                    assert("components.schemas.HeaderIdArray") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("No items schema defined for array schema defaulting to empty schema")
                    }
                    assert("components.schemas.HeaderIdArray.items") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },

                multiVersionLenientCase(name = "array with empty items schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "X-Ids")
                                        put("in", "header")
                                        put("schema", mapOf("type" to "array", "items" to emptyMap<String, Any>()))
                                    }
                                }
                            }
                        }
                    }
                    assert("paths./test.get.parameters[0].schema.items") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },
                multiVersionLenientCase(name = "refed out array with empty items schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "X-Ids")
                                        put("in", "header")
                                        put("schema", mapOf("\$ref" to "#/components/schemas/HeaderIdArray"))
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("HeaderIdArray") {
                                        put("type", "array")
                                        put("items", emptyMap<String, Any>())
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.HeaderIdArray.items") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },

                multiVersionLenientCase(name = "object schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "X-Filter")
                                        put("in", "header")
                                        put("schema", mapOf("type" to "object"))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(0) }
                },
                multiVersionLenientCase(name = "refed out object schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "X-Filter")
                                        put("in", "header")
                                        put("schema", mapOf("\$ref" to "#/components/schemas/HeaderFilter"))
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("HeaderFilter") {
                                        put("type", "object")
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(0) }
                },

                multiVersionLenientCase(name = "schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "X-Request-Id")
                                        put("in", "header")
                                        put("schema", mapOf("type" to "string", "minLength" to REASONABLE_STRING_LENGTH.plus(10)))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].schema.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("We will use a more reasonable minLength of 4MB")
                    }
                },
                multiVersionLenientCase(name = "refed out schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    parameter {
                                        put("name", "X-Request-Id")
                                        put("in", "header")
                                        put("required", true)
                                        put("schema", mapOf("\$ref" to "#/components/schemas/TooLongHeaderString"))
                                    }
                                }
                            }
                            components {
                                schemas {
                                    schema("TooLongHeaderString") {
                                        put("type", "string")
                                        put("minLength", REASONABLE_STRING_LENGTH.plus(10))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongHeaderString.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("We will use a more reasonable minLength of 4MB")
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun requestBodyTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "has no content", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {}
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.post.requestBody") {
                        toMatchText("requestBody doesn't contain the content map, defaulting to no body")
                    }
                },
                singleVersionLenientCase(name = "reference to a request body has no content", OpenApiVersion.OAS31) {
                    // TODO: OAS 3.0 Parser has issues parsing ref requestBodies when ref has no contenet and mediaType
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBodyRef("RefedOutBody")
                                }
                            }
                        }
                        components {
                            requestBodies {
                                requestBody("RefedOutBody") {}
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("components.requestBodies.RefedOutBody") {
                        toMatchText("requestBody doesn't contain the content map, defaulting to no body")
                    }
                },
                multiVersionLenientCase(name = "invalid reference to a request body", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBodyRef("RefedOutBody")
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(2); totalViolations(0) }
                    assert("paths./test.post.requestBody.\$ref") {
                        toMatchText("Failed to resolve reference to requestBodies RefedOutBody, defaulting to empty requestBody")
                    }
                    assert("paths./test.post.requestBody") {
                        toMatchText("requestBody doesn't contain the content map, defaulting to no body")
                    }
                },

                multiVersionLenientCase(name = "schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "string")
                                                    put("maxLength", REASONABLE_STRING_LENGTH.plus(10))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.post.requestBody.content.application/json.schema.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
                multiVersionLenientCase(name = "refed schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("TooLongString")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        components {
                            schemas {
                                schema("TooLongString") {
                                    put("type", "string")
                                    put("maxLength", REASONABLE_STRING_LENGTH.plus(10))
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongString.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
                multiVersionLenientCase(name = "reference to a request body's schema has issues", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBodyRef("RefedOutBody")
                                }
                            }
                        }
                        components {
                            requestBodies {
                                requestBody("RefedOutBody") {
                                    content {
                                        mediaType("application/json") {
                                            schemaRef("TooLongString")
                                        }
                                    }
                                }
                            }
                            schemas {
                                schema("TooLongString") {
                                    put("type", "string")
                                    put("maxLength", REASONABLE_STRING_LENGTH.plus(10))
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.Companion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongString.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
            ).flatten().stream()
        }

        @Suppress("UnusedReceiverParameter") // Use this to skip certain test cases if needed
        private fun List<Arguments>.skip(): List<Arguments> = emptyList()
    }
}
