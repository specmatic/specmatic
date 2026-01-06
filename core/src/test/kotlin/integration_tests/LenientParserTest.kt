package integration_tests

import integration_tests.LenientParseTestCase.Companion.multiVersionLenientCase
import io.specmatic.conversions.OpenApiLintViolations
import io.specmatic.conversions.OpenApiSpecification
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

            fun requestBody(block: AnyValueMap.() -> Unit) {
                op.map("requestBody", block)
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

        class ComponentsDsl(private val components: AnyValueMap) {
            fun schemas(block: SchemasDsl.() -> Unit) {
                val schemas = components.map("schemas") { }
                block(SchemasDsl(schemas))
            }

            fun securitySchemes(block: AnyValueMap.() -> Unit) {
                components.map("securitySchemes", block)
            }
        }

        class SchemasDsl(private val schemas: AnyValueMap) {
            fun schema(name: String, block: AnyValueMap.() -> Unit) {
                schemas[name] = mutableMapOf<String, Any?>().apply(block)
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

        print("paths:\n\t- ${issues.joinToString(separator = "\n\t- ", transform = { it.breadCrumb })}")
        case.asserts.forEach { it.assertViolation(result) }
        case.checks.forEach { it.invoke(feature) }
    }

    @ParameterizedTest
    @MethodSource("pathParameterTestCases")
    fun `path parameter test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("queryParameterTestCases")
    fun `query parameter test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    companion object {
        @JvmStatic
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
                    assert("paths./test/{id}.get.parameters[0]") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("Parameter has no schema defined, defaulting to empty schema")
                    }
                    assert("paths./test/{id}.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                        toMatchText("Schema is unclear, defaulting to non-null json schema")
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
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
                    assert("paths./test.get.parameters[0]") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("Parameter has no schema defined, defaulting to empty schema")
                    }
                    assert("paths./test.get.parameters[0].schema") {
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
                                        put("name", "ids")
                                        put("in", "query")
                                        put("schema", mapOf("type" to "array"))
                                    }
                                }
                            }
                        }
                    }
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
                    assert("paths./test.get.parameters[0].schema.items") {
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
                    assert("paths./test.get.parameters[0]") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.UNSUPPORTED_FEATURE)
                        toMatchText("Query parameter filter is an object, and not yet supported")
                    }
                },
            ).flatten().stream()
        }

        @Suppress("UnusedReceiverParameter") // Use this to skip certain test cases if needed
        private fun List<Arguments>.skip(): List<Arguments> = emptyList()
    }
}
