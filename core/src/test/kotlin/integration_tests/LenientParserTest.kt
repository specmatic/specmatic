package integration_tests

import integration_tests.LenientParseTestCase.Companion.multiVersionLenientCase
import integration_tests.LenientParseTestCase.Companion.singleVersionLenientCase
import io.specmatic.conversions.OpenApiLintViolations
import io.specmatic.conversions.SchemaLintViolations
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
    @MethodSource("securitySchemeTestCases")
    fun `security scheme test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("requestBodyTestCases")
    fun `request body test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("mediaTypeTestCases")
    fun `media type test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("responseHeaderTestCases")
    fun `response header test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("responseTestCases")
    fun `response content test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("numberSchemaTestCases")
    fun `number schema constraint test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("stringSchemaTestCases")
    fun `string schema constraint test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("enumSchemaTestCases")
    fun `enum schema constraint test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("objectSchemaTestCases")
    fun `object schema test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("arraySchemaTestCases")
    fun `array schema test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("allOfSchemaTestCases")
    fun `allOf schema test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("oneOfSchemaTestCases")
    fun `oneOf schema test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("anyOfSchemaTestCases")
    fun `anyOf schema test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("refTestCases")
    fun `ref test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

    @ParameterizedTest
    @MethodSource("schemaTestCases")
    fun `schema test cases`(version: OpenApiVersion, case: LenientParseTestCase, info: TestInfo) = runLenientCase(version, case)

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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test/{id}.get.parameters[-1]") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toContainViolation(OpenApiLintViolations.PATH_PARAMETER_MISSING)
                        toMatchText("The path parameter named \"id\" was declared, but no path parameter definition for \"id\" was found. Please add a definition for \"id\" to the spec.")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test/{id}.get.parameters[0]") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("Parameter has no schema defined, defaulting to empty schema")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test/{id}.get.parameters[0].schema.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("Limiting the minLength for now to the more practical 4MB")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongString.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("Limiting the minLength for now to the more practical 4MB")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0]") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("Parameter has no schema defined, defaulting to empty schema")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].schema.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("Limiting the minLength for now to the more practical 4MB")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongQueryString.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("Limiting the minLength for now to the more practical 4MB")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("Array Parameter has no items schema defined, defaulting to empty schema")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("components.schemas.IdArray") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("No items schema defined for array schema defaulting to empty schema")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.UNSUPPORTED_FEATURE)
                        toMatchText("Specmatic does not currently support query parameters serialized as objects. Specmatic will ignore this query parameter. Please reach out to the Specmatic team if you need support for this feature.")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.UNSUPPORTED_FEATURE)
                        toMatchText("Specmatic does not currently support query parameters serialized as objects. Specmatic will ignore this query parameter. Please reach out to the Specmatic team if you need support for this feature.")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0]") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("Parameter has no schema defined, defaulting to empty schema")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].schema") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("Array Parameter has no items schema defined, defaulting to empty schema")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("components.schemas.HeaderIdArray") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("No items schema defined for array schema defaulting to empty schema")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(0) }
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(0) }
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].schema.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("Limiting the minLength for now to the more practical 4MB")
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongHeaderString.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("Limiting the minLength for now to the more practical 4MB")
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun securitySchemeTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "missing global security scheme reference", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            securitySchemes {
                                scheme("validScheme") {
                                    put("type", "http")
                                    put("scheme", "bearer")
                                }
                            }
                        }
                        security {
                            requirement("missingScheme")
                        }
                        paths {
                            path("/test") {
                                operation("get") {}
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("security[0].missingScheme") {
                        toContainViolation(OpenApiLintViolations.SECURITY_SCHEME_MISSING)
                        toMatchText("Security scheme named \"missingScheme\" was used, but no such security scheme has been defined in the spec. Either drop the security scheme, or add a definition to the spec.")
                    }
                },
                multiVersionLenientCase(name = "missing operation security scheme reference", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            securitySchemes {
                                scheme("validScheme") {
                                    put("type", "http")
                                    put("scheme", "bearer")
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    security {
                                        requirement("missingScheme")
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.security[0].missingScheme") {
                        toContainViolation(OpenApiLintViolations.SECURITY_SCHEME_MISSING)
                    }
                },

                multiVersionLenientCase(name = "unsupported global security scheme type", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            securitySchemes {
                                scheme("digestAuth") {
                                    put("type", "http")
                                    put("scheme", "digest")
                                }
                            }
                        }
                        security {
                            requirement("digestAuth")
                        }
                        paths {
                            path("/test") {
                                operation("get") {}
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(2); totalViolations(2) }
                    assert("components.securitySchemes.digestAuth") {
                        toContainViolation(OpenApiLintViolations.UNSUPPORTED_FEATURE)
                    }
                    assert("security[0].digestAuth") {
                        toContainViolation(OpenApiLintViolations.SECURITY_SCHEME_MISSING)
                    }
                },
                multiVersionLenientCase(name = "unsupported operation security scheme type", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            securitySchemes {
                                scheme("digestAuth") {
                                    put("type", "http")
                                    put("scheme", "digest")
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    security {
                                        requirement("digestAuth")
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(2); totalViolations(2) }
                    assert("components.securitySchemes.digestAuth") {
                        toContainViolation(OpenApiLintViolations.UNSUPPORTED_FEATURE)
                    }
                    assert("paths./test.get.security[0].digestAuth") {
                        toContainViolation(OpenApiLintViolations.SECURITY_SCHEME_MISSING)
                    }
                },

                multiVersionLenientCase(name = "api key header parameter duplication", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            securitySchemes {
                                scheme("apiKeyAuth") {
                                    put("type", "apiKey")
                                    put("in", "header")
                                    put("name", "X-API-KEY")
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    security {
                                        requirement("apiKeyAuth")
                                    }
                                    parameter {
                                        put("name", "X-API-KEY")
                                        put("in", "header")
                                        put("schema", mapOf("type" to "string"))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].name") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED)
                    }
                },
                multiVersionLenientCase(name = "api key query parameter duplication", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            securitySchemes {
                                scheme("apiKeyAuth") {
                                    put("type", "apiKey")
                                    put("in", "query")
                                    put("name", "token")
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    security {
                                        requirement("apiKeyAuth")
                                    }
                                    parameter {
                                        put("name", "token")
                                        put("in", "query")
                                        put("schema", mapOf("type" to "string"))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.parameters[0].name") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED)
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun requestBodyTestCases(): Stream<Arguments> {
            return listOf(
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.post.requestBody.\$ref") {
                        toContainText("Please add the missing requestBody named \"RefedOutBody\"")
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.post.requestBody.content.application/json.schema.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongString.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongString.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun mediaTypeTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {
                                        content {
                                            mediaType("application/json") {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.post.requestBody.content.application/json") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toMatchText("No schema property defined under mediaType application/json, defaulting to free-form object.")
                    }
                },

                multiVersionLenientCase(name = "form-urlencoded has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {
                                        content {
                                            mediaType("application/x-www-form-urlencoded") {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.post.requestBody.content.application/x-www-form-urlencoded") {
                        toMatchText("No schema found. Please add the missing schema.")
                    }
                },
                multiVersionLenientCase(name = "form-urlencoded schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {
                                        content {
                                            mediaType("application/x-www-form-urlencoded") {
                                                schema {
                                                    put("type", "object")
                                                    put("properties", mapOf("name" to mapOf("type" to "string", "maxLength" to REASONABLE_STRING_LENGTH.plus(10))))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.post.requestBody.content.application/x-www-form-urlencoded.properties.name.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
                multiVersionLenientCase(name = "form-urlencoded refed schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {
                                        content {
                                            mediaType("application/x-www-form-urlencoded") {
                                                schemaRef("TooLongFormField")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        components {
                            schemas {
                                schema("TooLongFormField") {
                                    put("type", "object")
                                    put("properties", mapOf("name" to mapOf("type" to "string", "maxLength" to REASONABLE_STRING_LENGTH.plus(10))))
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongFormField.properties.name.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
                multiVersionLenientCase(name = "form-urlencoded schema refed property has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {
                                        content {
                                            mediaType("application/x-www-form-urlencoded") {
                                                schemaRef("LevelOne")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        components {
                            schemas {
                                schema("LevelOne") {
                                    put("type", "object")
                                    put("properties", mapOf("name" to mapOf("\$ref" to "#/components/schemas/LevelTwo")))
                                }
                                schema("LevelTwo") {
                                    put("type", "string")
                                    put("maxLength", REASONABLE_STRING_LENGTH.plus(10))
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.LevelTwo.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },

                multiVersionLenientCase(name = "multipart has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {
                                        content {
                                            mediaType("multipart/form-data") {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.post.requestBody.content.multipart/form-data") {
                        toMatchText("No schema found. Please add the missing schema.")
                    }
                },
                multiVersionLenientCase(name = "multipart schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {
                                        content {
                                            mediaType("multipart/form-data") {
                                                schema {
                                                    put("type", "object")
                                                    put("properties", mapOf("comment" to mapOf("type" to "string", "maxLength" to REASONABLE_STRING_LENGTH.plus(10))))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.post.requestBody.content.multipart/form-data.properties.comment.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
                multiVersionLenientCase(name = "multipart refed schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {
                                        content {
                                            mediaType("multipart/form-data") {
                                                schemaRef("MultipartPayload")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        components {
                            schemas {
                                schema("MultipartPayload") {
                                    put("type", "object")
                                    put("properties", mapOf("comment" to mapOf("type" to "string", "maxLength" to REASONABLE_STRING_LENGTH.plus(10))))
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.MultipartPayload.properties.comment.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
                multiVersionLenientCase(name = "multipart refed schemas refed property has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    requestBody {
                                        content {
                                            mediaType("multipart/form-data") {
                                                schemaRef("MultipartPayload")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        components {
                            schemas {
                                schema("MultipartPayload") {
                                    put("type", "object")
                                    put("properties", mapOf("comment" to mapOf("\$ref" to "#/components/schemas/CommentSchema")))
                                }
                                schema("CommentSchema") {
                                    put("type", "string")
                                    put("maxLength", REASONABLE_STRING_LENGTH.plus(10))
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.CommentSchema.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },

                multiVersionLenientCase(name = "media type is overriden in request", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    parameter {
                                        parameter {
                                            put("name", "Content-Type")
                                            put("in", "header")
                                            put("schema", mapOf("type" to "string"))
                                        }
                                    }
                                    requestBody {
                                        content {
                                            mediaType("application/json") { schema {} }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.post.parameters[0]") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.MEDIA_TYPE_OVERRIDDEN)
                        toMatchText("Content-Type should not be declared as a header per OAS standards")
                    }
                },
                multiVersionLenientCase(name = "media type is overriden in request case insensitive", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("post") {
                                    parameter {
                                        parameter {
                                            put("name", "CoNtEnT-TyPe")
                                            put("in", "header")
                                            put("schema", mapOf("type" to "string"))
                                            put("required", true)
                                        }
                                    }
                                    requestBody {
                                        content {
                                            mediaType("application/json") { schema {} }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.post.parameters[0]") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.MEDIA_TYPE_OVERRIDDEN)
                        toMatchText("Content-Type should not be declared as a header per OAS standards")
                    }
                },

                multiVersionLenientCase(name = "media type is overriden in response", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
                                        content {
                                            mediaType("application/json") { schema {} }
                                        }
                                        header("Content-Type") {
                                            put("schema", mapOf("type" to "string"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.headers.Content-Type") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.MEDIA_TYPE_OVERRIDDEN)
                        toMatchText("Content-Type should not be declared as a header per OAS standards")
                    }
                },
                multiVersionLenientCase(name = "media type is overriden in response case insensitive", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
                                        content {
                                            mediaType("application/json") { schema {} }
                                        }
                                        header("CoNtEnT-TyPe") {
                                            put("schema", mapOf("type" to "string"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.headers.CoNtEnT-TyPe") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.MEDIA_TYPE_OVERRIDDEN)
                        toMatchText("Content-Type should not be declared as a header per OAS standards")
                    }
                },
                multiVersionLenientCase(name = "media type is overriden in response ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
                                        content {
                                            mediaType("application/json") { schema {} }
                                        }
                                        headerRef("Content-Type", "Content-Type-Ref")
                                    }
                                }
                            }
                            components {
                                headers {
                                    header("Content-Type-Ref") {
                                        put("schema", mapOf("type" to "string"))
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.headers.Content-Type-Ref") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.MEDIA_TYPE_OVERRIDDEN)
                        toMatchText("Content-Type should not be declared as a header per OAS standards")
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun responseHeaderTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
                                        header("X-Request-Id", block = {})
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.get.responses.200.headers.X-Request-Id") {
                        toMatchText("No schema found. Please add the missing schema.")
                    }
                },
                multiVersionLenientCase(name = "array with no items schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
                                        header("X-Ids") {
                                            put("schema", mapOf("type" to "array"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.get.responses.200.headers.X-Ids.schema") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("No items schema defined for array schema defaulting to empty schema")
                    }
                },

                multiVersionLenientCase(name = "refed header has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
                                        headerRef("X-Request-Id", "HeaderId")
                                    }
                                }
                            }
                        }
                        components {
                            headers {
                                header("HeaderId") { }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("components.headers.HeaderId") {
                        toMatchText("No schema found. Please add the missing schema.")
                    }
                },
                multiVersionLenientCase(name = "refed header array no items schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
                                        headerRef("X-Ids", "HeaderIdArray")
                                    }
                                }
                            }
                        }
                        components {
                            headers {
                                header("HeaderIdArray") {
                                    put("schema", mapOf("type" to "array"))
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("components.headers.HeaderIdArray.schema") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toMatchText("No items schema defined for array schema defaulting to empty schema")
                    }
                },

                multiVersionLenientCase(name = "schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
                                        header("X-Request-Id") {
                                            put("schema", mapOf("type" to "string", "minLength" to REASONABLE_STRING_LENGTH.plus(10)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.headers.X-Request-Id.schema.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                        toContainText("Limiting the minLength for now to the more practical 4MB")
                    }
                },
                multiVersionLenientCase(name = "refed header schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
                                        headerRef("X-Request-Id", "TooLongHeader")
                                    }
                                }
                            }
                        }
                        components {
                            headers {
                                header("TooLongHeader") {
                                    put("schema", mapOf("type" to "string", "minLength" to REASONABLE_STRING_LENGTH.plus(10)))
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.headers.TooLongHeader.schema.minLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun responseTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "status code is not a valid integer", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("foo") {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "string")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.foo") {
                        toContainViolation(OpenApiLintViolations.INVALID_OPERATION_STATUS)
                        toMatchText("The response status code must be a valid integer, or the string value \"default\", but was \"foo\". Please use a valid status code or remove the status code section.")
                    }
                },
                multiVersionLenientCase(name = "invalid reference to a response", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    responseRef("200", "MissingResponse")
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.\$ref") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                        toContainText("Please add the missing response named \"MissingResponse\"")
                    }
                },
                multiVersionLenientCase(name = "media type has no schema", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
                                        content {
                                            mediaType("application/json") { }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.get.responses.200.content.application/json") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toMatchText(" No schema property defined under mediaType application/json, defaulting to free-form object.")
                    }
                },
                multiVersionLenientCase(name = "schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
                multiVersionLenientCase(name = "refed schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response("200") {
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongString.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
                multiVersionLenientCase(name = "reference to a response content schema has issue", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    responseRef("200", "RefedResponse")
                                }
                            }
                        }
                        components {
                            responses {
                                response("RefedResponse") {
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
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongString.maxLength") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(SchemaLintViolations.LENGTH_EXCEEDS_LIMIT)
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun numberSchemaTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "number schema maximum less than minimum (inline)", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "number")
                                                    put("minimum", 10)
                                                    put("maximum", 5)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                        toMatchText("maximum 5 should have been greater than minimum 10. Please make sure that maximum and minimum are not in conflict.")
                    }
                },
                multiVersionLenientCase(name = "number schema maximum less than minimum (ref)", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadNumber") {
                                    put("type", "number")
                                    put("minimum", 10)
                                    put("maximum", 5)
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("BadNumber")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadNumber.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                        toMatchText("maximum 5 should have been greater than minimum 10. Please make sure that maximum and minimum are not in conflict.")
                    }
                },

                singleVersionLenientCase(name = "number schema exclusive bounds collapse (inline)", version = OpenApiVersion.OAS30) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "number")
                                                    put("minimum", 5)
                                                    put("maximum", 6)
                                                    put("exclusiveMinimum", true)
                                                    put("exclusiveMaximum", true)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                        toMatchText("maximum 5 should have been greater than minimum 6. Please make sure that maximum and minimum are not in conflict.")
                    }
                },
                singleVersionLenientCase(name = "number schema exclusive bounds collapse (ref)", version = OpenApiVersion.OAS30) {
                    openApi {
                        components {
                            schemas {
                                schema("BadNumber") {
                                    put("type", "number")
                                    put("minimum", 5)
                                    put("maximum", 6)
                                    put("exclusiveMinimum", true)
                                    put("exclusiveMaximum", true)
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("BadNumber")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadNumber.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                        toMatchText("maximum 5 should have been greater than minimum 6. Please make sure that maximum and minimum are not in conflict.")
                    }
                },

                singleVersionLenientCase(name = "number schema exclusive bounds collapse (inline)", version = OpenApiVersion.OAS31) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "number")
                                                    put("exclusiveMinimum", 6)
                                                    put("exclusiveMaximum", 5)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.exclusiveMaximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                        toMatchText("exclusiveMaximum 4 should have been greater than exclusiveMinimum 7. Please make sure that exclusiveMaximum and exclusiveMinimum are not in conflict.")
                    }
                },
                singleVersionLenientCase(name = "number schema exclusive bounds collapse (ref)", version = OpenApiVersion.OAS31) {
                    openApi {
                        components {
                            schemas {
                                schema("BadNumber") {
                                    put("type", "number")
                                    put("exclusiveMinimum", 6)
                                    put("exclusiveMaximum", 5)
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("BadNumber")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadNumber.exclusiveMaximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                        toMatchText("exclusiveMaximum 4 should have been greater than exclusiveMinimum 7. Please make sure that exclusiveMaximum and exclusiveMinimum are not in conflict.")
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun stringSchemaTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "inline string minLength negative", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "string")
                                                    put("minLength", -5)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.minLength") {
                        toContainViolation(SchemaLintViolations.INVALID_MIN_LENGTH)
                        toMatchText("minLength should never be less than 0, but it is -5. Please use a positive minLength, or drop the constraint.")
                    }
                },
                multiVersionLenientCase(name = "ref string minLength negative", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("NegativeMinLength") {
                                    put("type", "string")
                                    put("minLength", -3)
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("NegativeMinLength")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.NegativeMinLength.minLength") {
                        toContainViolation(SchemaLintViolations.INVALID_MIN_LENGTH)
                        toMatchText("minLength should never be less than 0, but it is -3. Please use a positive minLength, or drop the constraint.")
                    }
                },

                multiVersionLenientCase(name = "inline string minLength > maxLength", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "string")
                                                    put("minLength", 10)
                                                    put("maxLength", 5)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.maxLength") {
                        toContainViolation(SchemaLintViolations.INVALID_MAX_LENGTH)
                        toMatchText("maxLength 5 should have been greater than minLength 10. Please make sure that maxLength and minLength are not in conflict.")
                    }
                },
                multiVersionLenientCase(name = "ref string minLength > maxLength", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadString") {
                                    put("type", "string")
                                    put("minLength", 10)
                                    put("maxLength", 5)
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("BadString")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadString.maxLength") {
                        toContainViolation(SchemaLintViolations.INVALID_MAX_LENGTH)
                        toMatchText("maxLength 5 should have been greater than minLength 10. Please make sure that maxLength and minLength are not in conflict.")
                    }
                },

                multiVersionLenientCase(name = "inline string invalid regex", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "string")
                                                    put("pattern", "*abc")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.get.responses.200.content.application/json.schema.pattern") {
                        toContainText("Invalid Regex format")
                    }
                },
                multiVersionLenientCase(name = "ref string invalid regex", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadRegex") {
                                    put("type", "string")
                                    put("pattern", "[abc")
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("BadRegex")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("components.schemas.BadRegex.pattern") {
                        toContainText("Invalid Regex format")
                    }
                },

                multiVersionLenientCase(name = "inline string regex shorter than minLength", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "string")
                                                    put("minLength", 5)
                                                    put("pattern", "a{1,3}")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.pattern") {
                        toContainViolation(SchemaLintViolations.PATTERN_LENGTH_INCOMPATIBLE)
                        toContainText("The regex pattern \"a{1,3}\" is incompatible with minLength 5")
                    }
                },
                multiVersionLenientCase(name = "ref string regex longer than maxLength", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("TooLongString") {
                                    put("type", "string")
                                    put("maxLength", 4)
                                    put("pattern", "a{10,20}")
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("TooLongString")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.TooLongString.pattern") {
                        toContainViolation(SchemaLintViolations.PATTERN_LENGTH_INCOMPATIBLE)
                        toContainText("The regex pattern \"a{10,20}\" is incompatible with maxLength 4")
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun enumSchemaTestCases(): Stream<Arguments> {
            return listOf(
                singleVersionLenientCase(name = "marked nullable but missing null value", OpenApiVersion.OAS30) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "string")
                                                    put("nullable", true)
                                                    put("enum", listOf("A", "B"))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.get.responses.200.content.application/json.schema.enum[-1]") {
                        toContainText("Enum values must contain null if the enum is marked nullable")
                    }
                },
                singleVersionLenientCase(name = "marked nullable but missing null value", OpenApiVersion.OAS31) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", listOf("string", "null"))
                                                    put("enum", listOf("A", "B"))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.get.responses.200.content.application/json.schema.enum[-1]") {
                        toContainText("Enum values must contain null if the enum is marked nullable")
                    }
                },

                singleVersionLenientCase(name = "not nullable but contains null value", OpenApiVersion.OAS30) {
                    openApi {
                        components {
                            schemas {
                                schema("BadEnum") {
                                    put("type", "string")
                                    put("nullable", false)
                                    put("enum", listOf("A", null, "B"))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("BadEnum")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert("components.schemas.BadEnum.enum[1]") {
                        toContainText("Enum values cannot contain null if the enum is not nullable")
                    }
                },
                singleVersionLenientCase(name = "not nullable but contains null value", OpenApiVersion.OAS31) {
                    openApi {
                        components {
                            schemas {
                                schema("BadEnum") {
                                    put("type", "string")
                                    put("enum", listOf("A", null, "B"))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("BadEnum")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert("components.schemas.BadEnum.enum[1]") {
                        toContainText("Enum values cannot contain null if the enum is not nullable")
                    }
                },

                singleVersionLenientCase(name = "value does not match declared schema type", OpenApiVersion.OAS30) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "integer")
                                                    put("enum", listOf(1, "two", 3))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(2); totalViolations(0) }
                    assert("paths./test.get.responses.200.content.application/json.schema.enum") {
                        // OpenApi 3.0 Parser parser implicitly coerces
                        toContainText(" Failed to parse enum. One or more enum values were parsed as null")
                    }
                    assert("paths./test.get.responses.200.content.application/json.schema.enum[1]") {
                        toMatchText("Enum values cannot contain null if the enum is not nullable, ignoring null value")
                    }
                },
                singleVersionLenientCase(name = "value does not match declared schema type", OpenApiVersion.OAS31) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "integer")
                                                    put("enum", listOf(1, "two", 3))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.get.responses.200.content.application/json.schema.enum[1]") {
                        toContainText("Enum value \"two\" does not match the declared enum schema, ignoring this value")
                    }
                },

                singleVersionLenientCase(name = "only null enum value with nullable false", OpenApiVersion.OAS30) {
                    openApi {
                        components {
                            schemas {
                                schema("OnlyNullEnum") {
                                    put("type", "string")
                                    put("nullable", false)
                                    put("enum", listOf(null))
                                }
                            }
                        }
                    }
                    assert("components.schemas.OnlyNullEnum.enum") {
                        toContainText("Only nullable enums can contain null, converting the enum to be nullable")
                    }
                },
                singleVersionLenientCase(name = "only null enum value with nullable false", OpenApiVersion.OAS31) {
                    openApi {
                        components {
                            schemas {
                                schema("OnlyNullEnum") {
                                    put("type", "string")
                                    put("enum", listOf(null))
                                }
                            }
                        }
                    }
                    assert("components.schemas.OnlyNullEnum.enum") {
                        toContainText("Only nullable enums can contain null, converting the enum to be nullable")
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun objectSchemaTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "property schema has invalid bounds", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "object")
                                                    put("properties", mapOf("age" to mapOf("type" to "integer", "minimum" to 10, "maximum" to 5)))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.properties.age.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                        toMatchText("maximum 5 should have been greater than minimum 10. Please make sure that maximum and minimum are not in conflict.")
                    }
                },
                multiVersionLenientCase(name = "property schema has invalid bounds (ref)", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadObject") {
                                    put("type", "object")
                                    put("properties", mapOf("age" to mapOf("type" to "integer", "minimum" to 10, "maximum" to 5)))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("BadObject")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadObject.properties.age.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                },
                multiVersionLenientCase(name = "property schema has invalid bounds (property ref)", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadNumber") {
                                    put("type", "integer")
                                    put("minimum", 10)
                                    put("maximum", 5)
                                }
                                schema("ObjectWithRefProperty") {
                                    put("type", "object")
                                    put("properties", mapOf("age" to mapOf("\$ref" to "#/components/schemas/BadNumber")))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("ObjectWithRefProperty")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadNumber.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                        toMatchText("maximum 5 should have been greater than minimum 10. Please make sure that maximum and minimum are not in conflict.")
                    }
                },
                multiVersionLenientCase(name = "required property missing from properties", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "object")
                                                    put("required", listOf("id"))
                                                    put("properties", emptyMap<String, Any>())
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.get.responses.200.content.application/json.schema.required") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainText("Required property \"id\" is not defined in properties, ignoring this requirement")
                    }
                },
                multiVersionLenientCase(name = "additionalProperties schema has invalid bounds (inline)", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "object")
                                                    put("additionalProperties", mapOf("type" to "number", "minimum" to 10, "maximum" to 5))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.additionalProperties.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                },
                multiVersionLenientCase(name = "additionalProperties schema has invalid bounds (object ref)", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadObject") {
                                    put("type", "object")
                                    put("additionalProperties", mapOf("type" to "number", "minimum" to 10, "maximum" to 5))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("BadObject")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadObject.additionalProperties.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                },
                multiVersionLenientCase(name = "additionalProperties schema has invalid bounds (ref)", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadNumber") {
                                    put("type", "number")
                                    put("minimum", 10)
                                    put("maximum", 5)
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "object")
                                                    put("additionalProperties", mapOf("\$ref" to "#/components/schemas/BadNumber"))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadNumber.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                        toMatchText("maximum 5 should have been greater than minimum 10. Please make sure that maximum and minimum are not in conflict.")
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun arraySchemaTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "array schema has no items", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "array")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(0) }
                    assert("paths./test.get.responses.200.content.application/json.schema") {
                        toHaveSeverity(IssueSeverity.ERROR)
                        toContainText("No items schema defined for array schema defaulting to empty schema")
                    }
                },
                multiVersionLenientCase(name = "array schema ref with invalid items", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadArray") {
                                    put("type", "array")
                                    put("items", mapOf("type" to "integer", "minimum" to 10, "maximum" to 5))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("BadArray")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadArray.items.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                },
                multiVersionLenientCase(name = "array items schema has invalid bounds", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "array")
                                                    put("items", mapOf("type" to "integer", "minimum" to 10, "maximum" to 5))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.items.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                        toMatchText("maximum 5 should have been greater than minimum 10. Please make sure that maximum and minimum are not in conflict.")
                    }
                },
                multiVersionLenientCase(name = "array items schema has invalid bounds (ref)", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadNumber") {
                                    put("type", "integer")
                                    put("minimum", 10)
                                    put("maximum", 5)
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "array")
                                                    put("items", mapOf("\$ref" to "#/components/schemas/BadNumber"))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadNumber.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun allOfSchemaTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "element has invalid bounds", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("allOf", listOf(
                                                        mapOf(
                                                            "type" to "object",
                                                            "properties" to mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5))
                                                        )
                                                    ))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.allOf[0].properties.value.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                        toMatchText("maximum 5 should have been greater than minimum 10. Please make sure that maximum and minimum are not in conflict.")
                    }
                },
                multiVersionLenientCase(name = "ref element schema has invalid bounds", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadNumber") {
                                    put("type", "object")
                                    put("properties", mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5)))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("allOf", listOf(mapOf("\$ref" to "#/components/schemas/BadNumber")))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadNumber.properties.value.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                },
                multiVersionLenientCase(name = "allOf inside referenced schema has invalid bounds", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadAllOf") {
                                    put("allOf", listOf(
                                        mapOf(
                                            "type" to "object",
                                            "properties" to mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5))
                                        )
                                    ))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("BadAllOf")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadAllOf.allOf[0].properties.value.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                },

                multiVersionLenientCase(name = "deep allOf chain has invalid bounds", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("Level1") {
                                    put("allOf", listOf(mapOf("\$ref" to "#/components/schemas/Level2")))
                                }
                                schema("Level2") {
                                    put("allOf", listOf(
                                        mapOf(
                                            "type" to "object",
                                            "properties" to mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5))
                                        )
                                    ))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("Level1")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.Level2.allOf[0].properties.value.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                },
                multiVersionLenientCase(name = "allOf with inline and ref schemas", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadObject") {
                                    put("type", "object")
                                    put("properties", mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5)))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("allOf", listOf(
                                                        mapOf("type" to "object"),
                                                        mapOf("\$ref" to "#/components/schemas/BadObject")
                                                    ))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadObject.properties.value.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                },

                multiVersionLenientCase(name = "allOf element contains oneOf with invalid bounds", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("allOf", listOf(
                                                        mapOf(
                                                            "oneOf" to listOf(mapOf(
                                                                "type" to "object",
                                                                "properties" to mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5))
                                                            ))
                                                        )
                                                    ))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.allOf[0].oneOf[0].properties.value.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                },
                multiVersionLenientCase(name = "allOf element contains oneOf ref with invalid bounds", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadObject") {
                                    put("type", "object")
                                    put("properties", mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5)))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("allOf", listOf(mapOf("oneOf" to listOf(mapOf("\$ref" to "#/components/schemas/BadObject")))))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadObject.properties.value.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun oneOfSchemaTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "oneOf element has invalid bounds", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("oneOf", listOf(
                                                        mapOf(
                                                            "type" to "object",
                                                            "properties" to mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5))
                                                        )
                                                    ))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.oneOf[0].properties.value.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                },
                multiVersionLenientCase(name = "oneOf element schema has invalid bounds (ref)", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadObject") {
                                    put("type", "object")
                                    put("properties", mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5)))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("oneOf", listOf(mapOf("\$ref" to "#/components/schemas/BadObject")))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadObject.properties.value.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                },
                multiVersionLenientCase(name = "oneOf with mixed valid and invalid schemas", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("oneOf", listOf(
                                                        mapOf(
                                                            "type" to "object",
                                                            "properties" to mapOf("ok" to mapOf("type" to "string"))
                                                        ),
                                                        mapOf(
                                                            "type" to "object",
                                                            "properties" to mapOf("bad" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5))
                                                        )
                                                    ))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.oneOf[1].properties.bad.maximum") {
                        toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun anyOfSchemaTestCases(): Stream<Arguments> {
             return listOf(
                 multiVersionLenientCase(name = "anyOf element has invalid bounds", *OpenApiVersion.allVersions()) {
                     openApi {
                         paths {
                             path("/test") {
                                 operation("get") {
                                     response(200) {
                                         content {
                                             mediaType("application/json") {
                                                 schema {
                                                     put("anyOf", listOf(
                                                         mapOf(
                                                             "type" to "object",
                                                             "properties" to mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5))
                                                         )
                                                     ))
                                                 }
                                             }
                                         }
                                     }
                                 }
                             }
                         }
                     }
                     assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                     assert("paths./test.get.responses.200.content.application/json.schema.anyOf[0].properties.value.maximum") {
                         toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                     }
                 },
                 multiVersionLenientCase(name = "anyOf element schema has invalid bounds (ref)", *OpenApiVersion.allVersions()) {
                     openApi {
                         components {
                             schemas {
                                 schema("BadObject") {
                                     put("type", "object")
                                     put("properties", mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5)))
                                 }
                             }
                         }
                         paths {
                             path("/test") {
                                 operation("get") {
                                     response(200) {
                                         content {
                                             mediaType("application/json") {
                                                 schema {
                                                     put("anyOf", listOf(mapOf("\$ref" to "#/components/schemas/BadObject")))
                                                 }
                                             }
                                         }
                                     }
                                 }
                             }
                         }
                     }
                     assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                     assert("components.schemas.BadObject.properties.value.maximum") {
                         toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                     }
                 },
                 multiVersionLenientCase(name = "anyOf with mixed valid and invalid schemas", *OpenApiVersion.allVersions()) {
                     openApi {
                         paths {
                             path("/test") {
                                 operation("get") {
                                     response(200) {
                                         content {
                                             mediaType("application/json") {
                                                 schema {
                                                     put("anyOf", listOf(
                                                         mapOf(
                                                             "type" to "object",
                                                             "properties" to mapOf("ok" to mapOf("type" to "string"))
                                                         ),
                                                         mapOf(
                                                             "type" to "object",
                                                             "properties" to mapOf("bad" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5))
                                                         )
                                                     ))
                                                 }
                                             }
                                         }
                                     }
                                 }
                             }
                         }
                     }
                     assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                     assert("paths./test.get.responses.200.content.application/json.schema.anyOf[1].properties.bad.maximum") {
                         toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                     }
                 },
                 multiVersionLenientCase(name = "anyOf inside referenced schema has invalid bounds", *OpenApiVersion.allVersions()) {
                     openApi {
                         components {
                             schemas {
                                 schema("BadAnyOf") {
                                     put("anyOf", listOf(
                                         mapOf(
                                             "type" to "object",
                                             "properties" to mapOf("value" to mapOf("type" to "number", "minimum" to 10, "maximum" to 5))
                                         )
                                     ))
                                 }
                             }
                         }
                         paths {
                             path("/test") {
                                 operation("get") {
                                     response(200) {
                                         content {
                                             mediaType("application/json") {
                                                 schemaRef("BadAnyOf")
                                             }
                                         }
                                     }
                                 }
                             }
                         }
                     }
                     assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                     assert("components.schemas.BadAnyOf.anyOf[0].properties.value.maximum") {
                         toContainViolation(SchemaLintViolations.INVALID_NUMERIC_BOUNDS)
                     }
                 }
             ).flatten().stream()
        }

        @JvmStatic
        fun refTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "schema has invalid ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("\$ref", "#/components/schemas/DoesNotExist")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.\$ref") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                    }
                },
                multiVersionLenientCase(name = "component schema has invalid ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("BadSchema") {
                                    put("\$ref", "#/components/schemas/Missing")
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.BadSchema.\$ref") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                    }
                },
                multiVersionLenientCase(name = "property schema has invalid ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "object")
                                                    put("properties", mapOf(
                                                        "age" to mapOf("\$ref" to "#/components/schemas/Nope")))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.properties.age.\$ref") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                    }
                },
                multiVersionLenientCase(name = "array items schema has invalid ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "array")
                                                    put("items", mapOf("\$ref" to "#/components/schemas/MissingItem"))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1);totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.items.\$ref") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                    }
                },
                multiVersionLenientCase(name = "oneOf element has invalid ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("oneOf", listOf(mapOf("\$ref" to "#/components/schemas/MissingOne")))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(2);totalViolations(2) }
                    assert("paths./test.get.responses.200.content.application/json.schema.oneOf[0].\$ref") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                    }
                    assert("paths./test.get.responses.200.content.application/json.schema.discriminator.mapping.MissingOne") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                    }
                },
                multiVersionLenientCase(name = "anyOf element has invalid ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("anyOf", listOf(mapOf("\$ref" to "#/components/schemas/MissingAny")))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(2);totalViolations(2) }
                    assert("paths./test.get.responses.200.content.application/json.schema.anyOf[0].\$ref") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                    }
                    assert("paths./test.get.responses.200.content.application/json.schema.discriminator.mapping.MissingAny") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                    }
                },
                multiVersionLenientCase(name = "allOf element has invalid ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("allOf", listOf(mapOf("\$ref" to "#/components/schemas/MissingAll")))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1);totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.allOf[0].\$ref") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                    }
                },
                multiVersionLenientCase(name = "additionalProperties schema has invalid ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("type", "object")
                                                    put("additionalProperties", mapOf("\$ref" to "#/components/schemas/MissingAP"))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1);totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.additionalProperties.\$ref") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                    }
                },
                multiVersionLenientCase(name = "oneOf discriminator mapping has invalid ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("oneOf", listOf(mapOf("type" to "object")))
                                                    put("discriminator", mapOf(
                                                        "propertyName" to "kind",
                                                        "mapping" to mapOf("X" to "#/components/schemas/NoSuchSchema")
                                                    ))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.discriminator.mapping.X") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                        toContainText("Please add the missing schema named \"NoSuchSchema\"")
                    }
                },
                multiVersionLenientCase(name = "oneOf discriminator mapping has invalid ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schema {
                                                    put("oneOf", listOf(mapOf("type" to "object")))
                                                    put(
                                                        "discriminator",
                                                        mapOf(
                                                            "propertyName" to "kind",
                                                            "mapping" to mapOf("X" to "#/components/schemas/NoSuchSchema")
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1);totalViolations(1) }
                    assert("paths./test.get.responses.200.content.application/json.schema.discriminator.mapping.X") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                        toContainText("Please add the missing schema named \"NoSuchSchema\"")
                    }
                },
                multiVersionLenientCase(name = "deep allOf discriminator mapping has invalid ref", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("Inner") {
                                    put("allOf", listOf(mapOf("type" to "object")))
                                    put("discriminator", mapOf("propertyName" to "kind", "mapping" to mapOf("BAD" to "#/components/schemas/MissingAllOf")))
                                }
                                schema("Base") {
                                    put("allOf", listOf(mapOf("\$ref" to "#/components/schemas/Inner")))
                                }
                            }
                        }
                        paths {
                            path("/test") {
                                operation("get") {
                                    response(200) {
                                        content {
                                            mediaType("application/json") {
                                                schemaRef("Base")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(2);totalViolations(2) }
                    assert("components.schemas.Inner.discriminator.mapping.BAD") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                        toContainText("Please add the missing schema named \"MissingAllOf\"")
                    }
                    assert("components.schemas.Inner.\$ref") {
                        toContainViolation(OpenApiLintViolations.UNRESOLVED_REFERENCE)
                        toContainText("Please add the missing schema named \"MissingAllOf\".")
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun schemaTestCases(): Stream<Arguments> {
            return listOf(
                multiVersionLenientCase(name = "schema is empty should have no issues", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("UnknownSchema") {}
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(0); totalViolations(0) }
                },
                multiVersionLenientCase(name = "schema is of unknown type", *OpenApiVersion.allVersions()) {
                    openApi {
                        components {
                            schemas {
                                schema("UnknownSchema") {
                                    put("type", "unknownType")
                                }
                            }
                        }
                    }
                    assert(RuleViolationAssertion.ALL_ISSUES) { totalIssues(1); totalViolations(1) }
                    assert("components.schemas.UnknownSchema") {
                        toHaveSeverity(IssueSeverity.WARNING)
                        toContainViolation(OpenApiLintViolations.SCHEMA_UNCLEAR)
                    }
                },
            ).flatten().stream()
        }

        @Suppress("UnusedReceiverParameter") // Use this to skip certain test cases if needed
        private fun List<Arguments>.skip(): List<Arguments> = emptyList()
    }
}

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

private fun MutableList<AnyValueMap>.requirement(vararg schemes: String) {
    val map = mutableMapOf<String, Any?>()
    schemes.forEach { map[it] = emptyList<String>() }
    add(map)
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

        fun security(block: MutableList<AnyValueMap>.() -> Unit) {
            val list = mutableListOf<AnyValueMap>()
            block(list)
            root["security"] = list
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

            fun response(status: Any, block: ResponseDsl.() -> Unit) {
                op.map("responses") {
                    val resp = mutableMapOf<String, Any?>()
                    block(ResponseDsl(resp))
                    put(status.toString(), resp)
                }
            }

            fun responseRef(status: Any, name: String) {
                op.map("responses") {
                    val resp = mapOf("\$ref" to "#/components/responses/$name")
                    put(status.toString(), resp)
                }
            }

            fun security(block: MutableList<AnyValueMap>.() -> Unit) {
                val list = mutableListOf<AnyValueMap>()
                block(list)
                op["security"] = list
            }
        }

        class ResponseDsl(private val response: AnyValueMap) {
            fun header(name: String, block: AnyValueMap.() -> Unit) {
                val headers = response.map("headers") { }
                headers[name] = mutableMapOf<String, Any?>().apply(block)
            }

            fun headerRef(name: String, refName: String) {
                val headers = response.map("headers") { }
                headers[name] = mapOf("\$ref" to "#/components/headers/$refName")
            }

            fun content(block: ContentDsl.() -> Unit) {
                val content = response.map("content") { }
                block(ContentDsl(content))
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

            fun securitySchemes(block: SecuritySchemeDsl.() -> Unit) {
                val schemas = components.map("securitySchemes") { }
                block(SecuritySchemeDsl(schemas))
            }

            fun requestBodies(block: RequestBodiesDsl.() -> Unit) {
                val bodies = components.map("requestBodies") { }
                block(RequestBodiesDsl(bodies))
            }

            fun headers(block: HeaderDsl.() -> Unit) {
                val headers = components.map("headers") { }
                block(HeaderDsl(headers))
            }

            fun responses(block: ResponsesDsl.() -> Unit) {
                val responses = components.map("responses") { }
                block(ResponsesDsl(responses))
            }
        }

        class SchemasDsl(private val schemas: AnyValueMap) {
            fun schema(name: String, block: AnyValueMap.() -> Unit) {
                schemas[name] = mutableMapOf<String, Any?>().apply(block)
            }
        }

        class SecuritySchemeDsl(private val schemas: AnyValueMap) {
            fun scheme(name: String, block: AnyValueMap.() -> Unit) {
                schemas[name] = mutableMapOf<String, Any?>().apply(block)
            }
        }

        class HeaderDsl(private val headers: AnyValueMap) {
            fun header(name: String, block: AnyValueMap.() -> Unit) {
                headers[name] = mutableMapOf<String, Any?>().apply(block)
            }
        }

        class RequestBodiesDsl(private val bodies: AnyValueMap) {
            fun requestBody(name: String, block: RequestBodyDsl.() -> Unit) {
                val body = mutableMapOf<String, Any?>()
                block(RequestBodyDsl(body))
                bodies[name] = body
            }
        }

        class ResponsesDsl(private val responses: AnyValueMap) {
            fun response(name: String, block: ResponseDsl.() -> Unit) {
                val response = mutableMapOf<String, Any?>()
                block(ResponseDsl(response))
                responses[name] = response
            }
        }
    }
}
