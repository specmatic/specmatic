package integration_tests

import integration_tests.PatternTestCase.Companion.multiVersionCase
import integration_tests.PatternTestCase.Companion.singleVersionCase
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.Base64StringPattern
import io.specmatic.core.pattern.BinaryPattern
import io.specmatic.core.pattern.BooleanPattern
import io.specmatic.core.pattern.DatePattern
import io.specmatic.core.pattern.DateTimePattern
import io.specmatic.core.pattern.EmailPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.UUIDPattern
import io.specmatic.core.pattern.withoutPatternDelimiters
import io.specmatic.core.utilities.toValue
import io.specmatic.core.utilities.yamlMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

enum class OpenApiVersion(val value: String) {
    OAS30("3.0.0"),
    OAS31("3.1.0")
}

data class PatternTestCase(val schema: Map<String, Any?>, val validate: (Pattern) -> Unit) {
    companion object {
        fun singleVersionCase(name: String, openApiVersion: OpenApiVersion, block: Builder.() -> Unit): List<Arguments> {
            val builder = Builder()
            builder.block()
            val testCase = builder.build()
            return listOf(Arguments.of(openApiVersion, Named.of(name, testCase)))
        }

        fun multiVersionCase(name: String, vararg versions: OpenApiVersion, block: Builder.() -> Unit): List<Arguments> {
            val builder = Builder()
            builder.block()
            val testCase = builder.build()
            return versions.map { version -> Arguments.of(version, Named.of(name, testCase)) }
        }
    }

    class Builder {
        var schema: Map<String, Any?> = emptyMap()
        private var validator: ((Pattern) -> Unit)? = null

        fun schema(init: MutableMap<String, Any?>.() -> Unit) {
            schema = mutableMapOf<String, Any?>().apply(init)
        }

        fun validate(block: (Pattern) -> Unit) {
            validator = block
        }

        fun build(): PatternTestCase {
            return PatternTestCase(
                schema = schema,
                validate = validator ?: throw IllegalStateException("Validation block missing")
            )
        }
    }
}

class YamlToPatternTests {
    private fun parseAndExtractPatterns(openApiVersion: OpenApiVersion, cases: Map<String, PatternTestCase>): Map<String, Pattern> {
        val schemasMap = cases.mapValues { (_, case) -> case.schema }
        val root = mapOf(
            "openapi" to openApiVersion.value,
            "info" to mapOf("title" to "Test API", "version" to "1.0.0"),
            "components" to mapOf(
                "schemas" to schemasMap
            )
        )

        val openApiYamlString = yamlMapper.writeValueAsString(root)
        val openApiSpecification = OpenApiSpecification.fromYAML(openApiYamlString, "TEST")
        return openApiSpecification.parseUnreferencedSchemas().mapKeys { withoutPatternDelimiters(it.key) }
    }

    @ParameterizedTest(name = "{index}: [{0}] {1}")
    @MethodSource("stringScenarios", "numberScenarios", "integerScenarios", "formatScenarios", "booleanScenarios")
    fun `should be able to construct pattern from the given yaml schema with accurate constraints`(openApiVersion: OpenApiVersion, case: PatternTestCase) {
        val patterns = parseAndExtractPatterns(openApiVersion, mapOf("TestSchema" to case))
        assertThat(patterns).hasSize(1).containsKey("TestSchema")
        val schemaPattern = patterns.getValue("TestSchema")
        case.validate(schemaPattern)
    }

    companion object {
        @JvmStatic
        fun stringScenarios(): Stream<Arguments> {
            return listOf(
                multiVersionCase("basic string", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "string")
                        put("example", "TEST")
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(StringPattern::class.java); pattern as StringPattern
                        assertThat(pattern.example).isEqualTo("TEST")
                    }
                },
                multiVersionCase("string with minLength", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "string")
                        put("minLength", 10)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(StringPattern::class.java)
                        assertFailure(pattern.match("123ABC"))
                        assertSuccess(pattern.match("12345ABCDE"))
                    }
                },
                multiVersionCase("string with maxLength", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "string")
                        put("maxLength", 5)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(StringPattern::class.java)
                        assertSuccess(pattern.match("ABCDE"))
                        assertFailure(pattern.match("ABCDEF"))
                    }
                },
                multiVersionCase("string with regex", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "string")
                        put("pattern", "^[A-Za-z]+$")
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(StringPattern::class.java)
                        assertSuccess(pattern.match("HelloWorld"))
                        assertFailure(pattern.match("Hello123"))
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun numberScenarios(): Stream<Arguments> {
            return listOf(
                multiVersionCase("basic number", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "number")
                        put("example", 10.1)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isTrue
                        assertThat(pattern.example).isEqualTo("10.1")
                    }
                },
                multiVersionCase("number with minimum", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "number")
                        put("minimum", 10)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isTrue
                        assertFailure(pattern.match(9.9))
                        assertSuccess(pattern.match(10))
                    }
                },
                multiVersionCase("number with maximum", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "number")
                        put("maximum", 20)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isTrue
                        assertSuccess(pattern.match(20))
                        assertFailure(pattern.match(20.1))
                    }
                },
                singleVersionCase("number with exclusive minimum (boolean)", OpenApiVersion.OAS30) {
                    schema {
                        put("type", "number")
                        put("minimum", 10)
                        put("exclusiveMinimum", true)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isTrue
                        assertFailure(pattern.match(10))
                        // assertSuccess(pattern.match(10.1)) // TODO: SmallInc should be 0.1 for doubles
                        assertSuccess(pattern.match(11))
                    }
                },
                singleVersionCase("number with exclusive maximum (boolean)", OpenApiVersion.OAS30) {
                    schema {
                        put("type", "number")
                        put("maximum", 20)
                        put("exclusiveMaximum", true)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isTrue
                        assertSuccess(pattern.match(19))
                        // assertSuccess(pattern.match(19.9)) // TODO: SmallInc should be 0.1 for doubles
                        assertFailure(pattern.match(20))
                    }
                },
                singleVersionCase("number with exclusive minimum (numeric)", OpenApiVersion.OAS31) {
                    schema {
                        put("type", "number")
                        put("exclusiveMinimum", 10)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isTrue
                        assertFailure(pattern.match(10))
                        // assertSuccess(pattern.match(10.1)) // TODO: SmallInc should be 0.1 for doubles
                        assertSuccess(pattern.match(11))
                    }
                },
                singleVersionCase("number with exclusive maximum (numeric)", OpenApiVersion.OAS31) {
                    schema {
                        put("type", "number")
                        put("exclusiveMaximum", 20)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isTrue
                        assertSuccess(pattern.match(19))
                        // assertSuccess(pattern.match(19.9)) // TODO: SmallInc should be 0.1 for doubles
                        assertFailure(pattern.match(20))
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun integerScenarios(): Stream<Arguments> {
            return listOf(
                multiVersionCase("basic integer", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "integer")
                        put("example", 10)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isFalse
                        assertThat(pattern.example).isEqualTo("10")
                    }
                },
                multiVersionCase("integer with minimum", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "integer")
                        put("minimum", 10)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isFalse
                        assertFailure(pattern.match(9))
                        assertSuccess(pattern.match(10))
                    }
                },
                multiVersionCase("integer with maximum", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "integer")
                        put("maximum", 20)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isFalse
                        assertSuccess(pattern.match(20))
                        assertFailure(pattern.match(21))
                    }
                },
                singleVersionCase("integer with exclusive minimum (boolean)", OpenApiVersion.OAS30) {
                    schema {
                        put("type", "integer")
                        put("minimum", 10)
                        put("exclusiveMinimum", true)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isFalse
                        assertFailure(pattern.match(10))
                        assertSuccess(pattern.match(11))
                    }
                },
                singleVersionCase("integer with exclusive maximum (boolean)", OpenApiVersion.OAS30) {
                    schema {
                        put("type", "integer")
                        put("maximum", 20)
                        put("exclusiveMaximum", true)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isFalse
                        assertSuccess(pattern.match(19))
                        assertFailure(pattern.match(20))
                    }
                },
                singleVersionCase("integer with exclusive minimum (numeric)", OpenApiVersion.OAS31) {
                    schema {
                        put("type", "integer")
                        put("exclusiveMinimum", 10)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isFalse
                        assertFailure(pattern.match(10))
                        assertSuccess(pattern.match(11))
                    }
                },
                singleVersionCase("integer with exclusive maximum (numeric)", OpenApiVersion.OAS31) {
                    schema {
                        put("type", "integer")
                        put("exclusiveMaximum", 20)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(NumberPattern::class.java); pattern as NumberPattern
                        assertThat(pattern.isDoubleFormat).isFalse
                        assertSuccess(pattern.match(19))
                        assertFailure(pattern.match(20))
                    }
                }
            ).flatten().stream()
        }

        @JvmStatic
        fun booleanScenarios(): Stream<Arguments> {
            return listOf(
                multiVersionCase("basic boolean", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "boolean")
                        put("example", true)
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(BooleanPattern::class.java); pattern as BooleanPattern
                        assertThat(pattern.example).isEqualTo("true")
                    }
                },
            ).flatten().stream()
        }

        @JvmStatic
        fun formatScenarios(): Stream<Arguments> {
            return listOf(
                multiVersionCase("email format", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "string")
                        put("format", "email")
                        put("example", "test@example.com")
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(EmailPattern::class.java); pattern as EmailPattern
                        assertThat(pattern.example).isEqualTo("test@example.com")
                    }
                },
                multiVersionCase("password format", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "string")
                        put("format", "password")
                        put("example", "SECRET")
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(StringPattern::class.java); pattern as StringPattern
                        assertThat(pattern.example).isEqualTo("SECRET")
                    }
                },
                multiVersionCase("uuid format", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "string")
                        put("format", "uuid")
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(UUIDPattern::class.java)
                    }
                },
                multiVersionCase("date format", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "string")
                        put("format", "date")
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(DatePattern::class.java)
                    }
                },
                multiVersionCase("date-time format", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "string")
                        put("format", "date-time")
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(DateTimePattern::class.java)
                    }
                },
                multiVersionCase("binary format", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "string")
                        put("format", "binary")
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(BinaryPattern::class.java)
                    }
                },
                multiVersionCase("byte format", OpenApiVersion.OAS30, OpenApiVersion.OAS31) {
                    schema {
                        put("type", "string")
                        put("format", "byte")
                    }
                    validate { pattern ->
                        assertThat(pattern).isInstanceOf(Base64StringPattern::class.java)
                    }
                }
            ).flatten().stream()
        }

        private fun Pattern.match(value: Any, resolver: Resolver = Resolver()): Result {
            val parsedValue = toValue(value)
            return this.matches(parsedValue, resolver)
        }

        private fun assertSuccess(result: Result) {
            if (!result.isSuccess()) fail("Expected success but failed, Report: ${result.reportString()}")
        }

        private fun assertFailure(result: Result) {
            if (result.isSuccess()) fail("Expected failure but succeeded")
        }
    }
}
