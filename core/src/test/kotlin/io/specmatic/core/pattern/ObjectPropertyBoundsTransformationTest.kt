package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.AttributeSelectionPattern
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.QueryParameters
import io.specmatic.core.Scenario
import io.specmatic.core.substitution.SubstitutionImpl
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

internal class ObjectPropertyBoundsTransformationTest {
    @Test
    fun `attribute selection generates the requested bounded response fields through Scenario`() {
        val response = JSONObjectPattern(
            pattern = mapOf("id?" to StringPattern(), "name?" to StringPattern()), minProperties = 2, maxProperties = 2
        )
        val scenario = Scenario(
            "attribute selection", HttpRequestPattern(), HttpResponsePattern(status = 200, body = response),
            attributeSelectionPattern = AttributeSelectionPattern(defaultFields = emptyList(), queryParamKey = "fields"),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )

        val projected = scenario.newBasedOnAttributeSelectionFields(QueryParameters(mapOf("fields" to "id")))
        val generated = projected.httpResponsePattern.generateResponseWithAll(projected.resolver)

        assertThat((generated.body as JSONObjectValue).jsonObject.keys).containsExactly("id")
        assertThat(projected.httpResponsePattern.matchesResponse(generated, projected.resolver).isSuccess()).isTrue()
    }

    @Test
    fun `minimum completion preserves existing additional property names and values`() {
        val original = JSONObjectPattern(
            minProperties = 2, maxProperties = 2,
            additionalProperties = AdditionalProperties.PatternConstrained(StringPattern())
        )
        val resolver = Resolver()

        val fixed = original.fixValue(parsedJSONObject("""{"additionalProperty1":"keep"}"""), resolver) as JSONObjectValue

        assertThat(fixed.jsonObject).hasSize(2)
        assertThat(fixed.jsonObject.getValue("additionalProperty1")).isEqualTo(StringValue("keep"))
        assertThat(original.matches(fixed, resolver).isSuccess()).isTrue()
    }

    @Test
    fun `whole object substitution returns the resolved empty object`() {
        val original = JSONObjectPattern(minProperties = 0, maxProperties = 0, additionalProperties = AdditionalProperties.FreeForm)
        val resolver = Resolver()
        val expression = StringValue("$(payload)")
        val substitution = SubstitutionImpl.empty(strictMode = true)
            .upsertStoreUsing(StringValue("(payload:string)"), JSONObjectValue(), resolver)

        val resolved = original.resolveSubstitutions(substitution, expression, resolver)

        assertThat(resolved.value).isEqualTo(JSONObjectValue())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `negative and partial transformations preserve deliberately incomplete objects`(negative: Boolean) {
        val original = JSONObjectPattern(
            pattern = mapOf("id" to StringPattern(), "name?" to StringPattern()), minProperties = 2, maxProperties = 2
        )
        val resolver = if (negative) Resolver(isNegative = true) else Resolver().partializeKeyCheck()
        val partial = parsedJSONObject("""{"id":"keep"}""")

        assertThat(original.fixValue(partial, resolver)).isEqualTo(partial)
        assertThat(original.fillInTheBlanks(partial, resolver).value).isEqualTo(partial)
        assertThat(original.resolveSubstitutions(SubstitutionImpl.empty(), partial, resolver).value).isEqualTo(partial)
    }

    @Test
    fun `optional key elimination remains a deliberately reduced value projection`() {
        val original = JSONObjectPattern(
            pattern = mapOf("id" to StringPattern(), "name?" to StringPattern()), minProperties = 2, maxProperties = 2
        )

        val reduced = original.eliminateOptionalKey(parsedJSONObject("""{"id":"keep","name":"n"}"""), Resolver())

        assertThat(reduced).isEqualTo(parsedJSONObject("""{"id":"keep"}"""))
    }

    @Test
    fun `attribute selection lowers the inherited minimum for a smaller response projection`() {
        val original = JSONObjectPattern(
            pattern = mapOf("id?" to StringPattern(), "name?" to StringPattern(), "city?" to StringPattern()),
            minProperties = 2, maxProperties = 2
        )
        val resolver = Resolver()

        val projected = original.removeKeysNotPresentIn(setOf("id"), resolver)

        assertThat(projected.matches(parsedJSONObject("""{"id":"keep"}"""), resolver).isSuccess()).isTrue()
        assertThat(projected.matches(parsedJSONObject("{}"), resolver).isSuccess()).isFalse()
    }

    @Test
    fun `attribute selection reports selections exceeding the original maximum`() {
        val original = JSONObjectPattern(
            pattern = mapOf("id?" to StringPattern(), "name?" to StringPattern(), "city?" to StringPattern()),
            minProperties = 2, maxProperties = 2
        )

        assertThatThrownBy { original.removeKeysNotPresentIn(setOf("id", "name", "city"), Resolver()) }
            .isInstanceOf(ContractException::class.java)
            .hasMessageContaining("Cannot select 3 properties because maxProperties is 2")
    }

    @ParameterizedTest
    @CsvSource(
        "2 | 3 | {\"id\":\"keep\",\"name\":\"$(drop)\"} | false",
        "1 | 2 | {\"id\":\"keep\",\"name\":\"n\",\"extra\":\"x\"} | false",
        "1 | 3 | {\"id\":\"keep\",\"name\":\"$(drop)\"} | true",
        "1 | 3 | {\"id\":\"keep\",\"extra\":\"$(drop)\"} | true",
        delimiter = '|'
    )
    fun `substitution preserves valid drops and reports resulting count violations`(
        minimum: Int, maximum: Int, json: String, valid: Boolean
    ) {
        val original = JSONObjectPattern(
            pattern = mapOf("id" to StringPattern(), "name?" to StringPattern()),
            minProperties = minimum, maxProperties = maximum,
            additionalProperties = AdditionalProperties.PatternConstrained(StringPattern())
        )
        val resolver = Resolver()

        val resolved = original.resolveSubstitutions(SubstitutionImpl.empty(), parsedJSONObject(json), resolver)

        if (valid) {
            assertThat(resolved.value).isEqualTo(parsedJSONObject("""{"id":"keep"}"""))
            val result = original.matches(resolved.value, resolver)
            assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
        } else {
            assertThat(resolved).isInstanceOf(HasFailure::class.java)
            assertThat((resolved as HasFailure).failure.reportString()).contains("R1003")
        }
    }

    @ParameterizedTest
    @CsvSource("3, 3, true", "0, 1, false")
    fun `generation with all properties respects bounds and additional property schemas`(minimum: Int, maximum: Int, additional: Boolean) {
        val original = JSONObjectPattern(
            pattern = mapOf("id" to StringPattern(), "name?" to StringPattern()),
            minProperties = minimum, maxProperties = maximum,
            additionalProperties = if (additional) AdditionalProperties.PatternConstrained(StringPattern()) else AdditionalProperties.NoAdditionalProperties
        )
        val resolver = Resolver()

        val generated = original.generateWithAll(resolver)

        val result = original.matches(generated, resolver)
        assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "3 | 3 | true | {\"id\":\"keep\"} | true",
        "2 | 2 | false | {\"id\":\"keep\"} | true",
        "3 | 3 | true | (anyvalue) | true",
        "1 | 2 | true | {\"id\":\"keep\",\"name\":\"n\",\"extra\":\"x\"} | false",
        delimiter = '|'
    )
    fun `blank filling completes minimum counts and rejects supplied overflow`(
        minimum: Int, maximum: Int, additional: Boolean, input: String, valid: Boolean
    ) {
        val original = JSONObjectPattern(
            pattern = mapOf("id" to StringPattern(), "name?" to StringPattern()),
            minProperties = minimum, maxProperties = maximum,
            additionalProperties = if (additional) AdditionalProperties.PatternConstrained(StringPattern()) else AdditionalProperties.NoAdditionalProperties
        )
        val resolver = Resolver()
        val value = if (input.startsWith("(")) StringValue(input) else parsedJSONObject(input)

        val filled = original.fillInTheBlanks(value, resolver)

        if (valid) {
            val result = original.matches(filled.value, resolver)
            assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
        } else {
            assertThat(filled).isInstanceOf(HasFailure::class.java)
            assertThat((filled as HasFailure).failure.reportString()).contains("R1003", "at most 2 properties")
        }
    }

    @ParameterizedTest
    @CsvSource(
        "3 | 3 | true | {\"id\":\"keep\",\"additionalProperty1\":\"existing\"}",
        "2 | 2 | false | {\"id\":\"keep\"}",
        "1 | 2 | true | {\"id\":\"keep\",\"name\":\"keep-name\",\"extra1\":\"x\",\"extra2\":\"y\"}",
        delimiter = '|'
    )
    fun `fixValue repairs object counts while retaining required values`(minimum: Int, maximum: Int, additional: Boolean, json: String) {
        val original = JSONObjectPattern(
            pattern = mapOf("id" to StringPattern(), "name?" to StringPattern()),
            minProperties = minimum, maxProperties = maximum,
            additionalProperties = if (additional) AdditionalProperties.PatternConstrained(StringPattern()) else AdditionalProperties.NoAdditionalProperties
        )
        val resolver = Resolver()

        val fixed = original.fixValue(parsedJSONObject(json), resolver) as JSONObjectValue

        val result = original.matches(fixed, resolver)
        assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
        assertThat(fixed.jsonObject.getValue("id")).isEqualTo(StringValue("keep"))
    }
}
