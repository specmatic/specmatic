package io.specmatic.core.utilities

import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal

class JSONSerialisationKtTest {
    @Test
    fun `should parse a JSON string with a UTF-8 BOM`() {
        val jsonContent = "\uFEFF{\"greeting\":\"hello\"}"
        assertDoesNotThrow { parsedJSONObject(jsonContent) }
    }

    @Test
    fun `should be able to convert json string to appropriate value`() {
        val jsonContent = "{\"key\": 123}"
        val value = jsonStringToValue(jsonContent)
        assertThat(value).isInstanceOf(JSONObjectValue::class.java)
    }

    @ParameterizedTest
    @MethodSource("numberValuesProvider")
    fun `should be able to de-serialise numbers from json to number value`(value: Number) {
        val jsonString = """{"value": $value}"""
        val valueMap = jsonStringToValueMap(jsonString)
        val parsedValue = (valueMap["value"] as NumberValue).nativeValue

        assertThat(
            BigDecimal(parsedValue.toString())
        ).isEqualTo(
            BigDecimal(value.toString())
        )
    }

    @ParameterizedTest
    @MethodSource("numberValuesProvider")
    fun `should be able to serialise from number value to json`(value: Number) {
        val valueMap = mapOf("value" to NumberValue(value))
        val jsonContent = valueMapToUnindentedJsonString(valueMap)

        assertThat(jsonContent).isEqualTo("""{"value":$value}""")
    }

    companion object {
        @JvmStatic
        fun numberValuesProvider(): List<Number> {
            return listOf(
                Int.MIN_VALUE, Int.MAX_VALUE,
                Long.MIN_VALUE, Long.MAX_VALUE,
                Float.MIN_VALUE, Float.MAX_VALUE,
                Double.MIN_VALUE, Double.MAX_VALUE
            )
        }
    }
}