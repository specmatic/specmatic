package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.ResiliencyTestsConfig
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.StubConfiguration
import io.specmatic.core.TestConfiguration
import io.specmatic.core.examples.server.ScenarioFilter
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.EmailPattern
import io.specmatic.core.pattern.EnumPattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.NullPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.resolvedHop
import io.specmatic.core.testBackwardCompatibility
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.SCHEMA_EXAMPLE_DEFAULT
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.stub.HttpStub
import io.specmatic.stub.SpecmaticConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal

class OpenApi31Test {
    private val openApi31File = File("src/test/resources/openapi/3_1/mixed/openapi31.yaml")
    private val openApi30File = File("src/test/resources/openapi/3_1/mixed/openapi30.yaml")
    private val openApi31TestFilter = "PATH!='/multiTypeEnumSchema,/contNullValueSchema'"
    private val specmaticConfig = SpecmaticConfig(
        test = TestConfiguration(resiliencyTests = ResiliencyTestsConfig(enable = ResiliencyTestSuite.all)),
        stub = StubConfiguration(generative = true),
    )

    @Test
    fun `should be able to run parse openApi 31 specification into proper internal pattern representations`() {
        val openApiSpecification = OpenApiSpecification.fromFile(openApi31File.canonicalPath)
        val feature = openApiSpecification.toFeature()

        // Multi-Type Schema [string number null]
        val multiTypeSchemaScenario = feature.scenarios.elementAt(0)
        val multiTypeSchemaRequestBody = resolvedHop(multiTypeSchemaScenario.httpRequestPattern.body, multiTypeSchemaScenario.resolver)
        val multiTypeSchemaResponseBody = resolvedHop(multiTypeSchemaScenario.httpResponsePattern.body, multiTypeSchemaScenario.resolver)
        val multiTypeKeySchema = (multiTypeSchemaRequestBody as JSONObjectPattern).pattern.getValue("key")

        assertThat(multiTypeSchemaRequestBody).isEqualTo(multiTypeSchemaResponseBody)
        assertThat(multiTypeKeySchema).isInstanceOf(AnyPattern::class.java); multiTypeKeySchema as AnyPattern
        assertThat(multiTypeKeySchema.pattern).containsExactlyInAnyOrder(StringPattern(), NumberPattern(), NullPattern)

        // Multi-Type Enum Schema [string number null] with [ABC, 1234, null]
        val multiTypeEnumSchemaScenario = feature.scenarios.elementAt(2)
        val multiTypeEnumSchemaRequestBody = resolvedHop(multiTypeEnumSchemaScenario.httpRequestPattern.body, multiTypeEnumSchemaScenario.resolver)
        val multiTypeEnumSchemaResponseBody = resolvedHop(multiTypeEnumSchemaScenario.httpResponsePattern.body, multiTypeEnumSchemaScenario.resolver)
        val multiTypeEnumKeySchema = (multiTypeEnumSchemaRequestBody as JSONObjectPattern).pattern.getValue("key")

        assertThat(multiTypeEnumSchemaRequestBody).isEqualTo(multiTypeEnumSchemaResponseBody)
        assertThat(multiTypeEnumKeySchema).isInstanceOf(EnumPattern::class.java); multiTypeEnumKeySchema as EnumPattern
        assertThat(multiTypeEnumKeySchema.multiType).isTrue
        assertThat(multiTypeEnumKeySchema.nullable).isTrue
        assertThat(multiTypeEnumKeySchema.pattern.pattern).containsExactlyInAnyOrder(
            ExactValuePattern(StringValue("ABCD"), isConst = true),
            ExactValuePattern(NumberValue(1234), isConst = true),
            ExactValuePattern(NullValue, isConst = true)
        )

        // Ref merged schema overrides format[email] and example
        val unifiedRefScenario = feature.scenarios.elementAt(4)
        val unifiedRefRequestBody = resolvedHop(unifiedRefScenario.httpRequestPattern.body, unifiedRefScenario.resolver)
        val unifiedRefResponseBody = resolvedHop(unifiedRefScenario.httpResponsePattern.body, unifiedRefScenario.resolver)
        val unifiedRefKeySchema = resolvedHop((unifiedRefRequestBody as JSONObjectPattern).pattern.getValue("key"), unifiedRefScenario.resolver)

        assertThat(unifiedRefRequestBody).isEqualTo(unifiedRefResponseBody)
        assertThat(unifiedRefKeySchema).isInstanceOf(EmailPattern::class.java); unifiedRefKeySchema as EmailPattern
        assertThat(unifiedRefKeySchema.example).isEqualTo("Specmatic@mail.com")

        // ExclusiveMinimum set to 0, ExclusiveMaximum set to 100
        val exclusiveMinMaxScenario = feature.scenarios.elementAt(6)
        val exclusiveMinMaxRequestBody = resolvedHop(exclusiveMinMaxScenario.httpRequestPattern.body, exclusiveMinMaxScenario.resolver)
        val exclusiveMinMaxResponseBody = resolvedHop(exclusiveMinMaxScenario.httpResponsePattern.body, exclusiveMinMaxScenario.resolver)
        val exclusiveMinMaxKeySchema = (exclusiveMinMaxRequestBody as JSONObjectPattern).pattern.getValue("key")

        assertThat(exclusiveMinMaxRequestBody).isEqualTo(exclusiveMinMaxResponseBody)
        assertThat(exclusiveMinMaxKeySchema).isInstanceOf(NumberPattern::class.java); exclusiveMinMaxKeySchema as NumberPattern
        assertThat(exclusiveMinMaxKeySchema.minimum).isEqualTo(BigDecimal(0))
        assertThat(exclusiveMinMaxKeySchema.exclusiveMinimum).isTrue
        assertThat(exclusiveMinMaxKeySchema.maximum).isEqualTo(BigDecimal(100))
        assertThat(exclusiveMinMaxKeySchema.exclusiveMaximum).isTrue

        // Const value set to number 100
        val constScenario = feature.scenarios.elementAt(8)
        val constRequestBody = resolvedHop(constScenario.httpRequestPattern.body, constScenario.resolver)
        val constResponseBody = resolvedHop(constScenario.httpResponsePattern.body, constScenario.resolver)
        val constKeySchema = (constRequestBody as JSONObjectPattern).pattern.getValue("key")

        assertThat(constRequestBody).isEqualTo(constResponseBody)
        assertThat(constKeySchema).isInstanceOf(ExactValuePattern::class.java); constKeySchema as ExactValuePattern
        assertThat(constKeySchema.pattern).isEqualTo(NumberValue(100))
        assertThat(constKeySchema.isConst).isTrue

        // Const value set to null
        val nullConstScenario = feature.scenarios.elementAt(10)
        val nullConstRequestBody = resolvedHop(nullConstScenario.httpRequestPattern.body, nullConstScenario.resolver)
        val nullConstResponseBody = resolvedHop(nullConstScenario.httpResponsePattern.body, nullConstScenario.resolver)
        val nullConstKeySchema = (nullConstRequestBody as JSONObjectPattern).pattern.getValue("key")

        assertThat(nullConstRequestBody).isEqualTo(nullConstResponseBody)
        assertThat(nullConstKeySchema).isInstanceOf(ExactValuePattern::class.java); nullConstKeySchema as ExactValuePattern
        assertThat(nullConstKeySchema.pattern).isEqualTo(NullValue)
        assertThat(nullConstKeySchema.isConst).isTrue
    }

    @Test
    fun `should be able to run a loop-test using an openapi 31 specification with no failures`() {
        val openApiSpecification = OpenApiSpecification.fromFile(openApi31File.canonicalPath)
        val feature = Flags.using(SCHEMA_EXAMPLE_DEFAULT to "true") {
            openApiSpecification.toFeature().copy(specmaticConfig = specmaticConfig)
        }

        val results = HttpStub(
            features = listOf(feature),
            specmaticConfigSource = SpecmaticConfigSource.fromConfig(specmaticConfig)
        ).use { stub ->
            feature.executeTests(stub.client)
        }.results

        assertThat(results.size).isEqualTo(32)
        assertThat(Result.fromResults(results))
            .withFailMessage { Result.fromResults(results).reportString() }
            .isInstanceOf(Result.Success::class.java)

    }

    @Test
    fun `openapi 30 specification should be able to test a similar 31 stub`() {
        val openApi31Specification = OpenApiSpecification.fromFile(openApi31File.canonicalPath)
        val openApi30Specification = OpenApiSpecification.fromFile(openApi30File.canonicalPath)

        val results = HttpStub(
            features = listOf(openApi31Specification.toFeature()),
            specmaticConfigSource = SpecmaticConfigSource.fromConfig(specmaticConfig)
        ).use { stub ->
            openApi30Specification.toFeature().copy(specmaticConfig = specmaticConfig).executeTests(stub.client)
        }.results

        assertThat(results.size).isEqualTo(22)
        assertThat(Result.fromResults(results))
            .withFailMessage { Result.fromResults(results).reportString() }
            .isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `openapi 31 specification should be able to test a similar 30 stub`() {
        val openApi31Specification = OpenApiSpecification.fromFile(openApi31File.canonicalPath)
        val openApi30Specification = OpenApiSpecification.fromFile(openApi30File.canonicalPath)
        val filtered31Feature = ScenarioFilter(filterClauses = openApi31TestFilter).filter(openApi31Specification.toFeature())

        val results = HttpStub(
            features = listOf(openApi30Specification.toFeature()),
            specmaticConfigSource = SpecmaticConfigSource.fromConfig(specmaticConfig)
        ).use { stub ->
            filtered31Feature.copy(specmaticConfig = specmaticConfig).executeTests(stub.client)
        }.results

        assertThat(results.size).isEqualTo(22)
        assertThat(Result.fromResults(results))
            .withFailMessage { Result.fromResults(results).reportString() }
            .isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `openapi 31 should be regarded as backward compatible with a similar 30 and vice-versa`() {
        val openApi31Specification = OpenApiSpecification.fromFile(openApi31File.canonicalPath)
        val openApi30Specification = OpenApiSpecification.fromFile(openApi30File.canonicalPath)

        val openApi30Feature = openApi30Specification.toFeature()
        val openApi31Feature = openApi31Specification.toFeature()
        val filtered31Feature = ScenarioFilter(filterClauses = openApi31TestFilter).filter(openApi31Feature)

        val openApi30To31 = testBackwardCompatibility(openApi30Feature, filtered31Feature)
        assertThat(openApi30To31.results).hasSize(12)
        assertThat(Result.fromResults(openApi30To31.results))
            .withFailMessage { Result.fromResults(openApi30To31.results).reportString() }
            .isInstanceOf(Result.Success::class.java)

        val openApi31To30 = testBackwardCompatibility(filtered31Feature, openApi30Feature)
        assertThat(openApi31To30.results).hasSize(12)
        assertThat(Result.fromResults(openApi31To30.results))
            .withFailMessage { Result.fromResults(openApi31To30.results).reportString() }
            .isInstanceOf(Result.Success::class.java)
    }
}
