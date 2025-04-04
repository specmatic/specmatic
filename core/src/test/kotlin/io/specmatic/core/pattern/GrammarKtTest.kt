package io.specmatic.core.pattern

import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.toXMLNode
import org.apache.commons.io.ByteOrderMark
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.Charset
import java.util.function.Consumer

internal class GrammarKtTest {
    companion object {
        @JvmStatic
        fun bomProvider(): List<ByteOrderMark> {
            return ByteOrderMark::class.java.fields.mapNotNull { it.get(null) as? ByteOrderMark }
        }
    }

    @Test
    fun `value starting with a brace which is not json parses to string value`() {
        assertThat(parsedValue("""{one""")).isInstanceOf(StringValue::class.java)
    }

    @Test
    fun `value starting with a square bracket which is not json parses to string value`() {
        assertThat(parsedValue("""[one""")).isInstanceOf(StringValue::class.java)
    }

    @Test
    fun `value starting with an angular bracket which is not json parses to string value`() {
        assertThat(parsedValue("""<one""")).isInstanceOf(StringValue::class.java)
    }

    @Test
    fun `pattern in string is parsed as such`() {
        val type = parsedPattern("(name:string)")
        assertThat(type).isEqualTo(LookupRowPattern(StringPattern(), "name"))
    }

    @Test
    fun `unknown pattern is parsed as deferred`() {
        val type = parsedPattern("(name string)")
        assertThat(type).isEqualTo(DeferredPattern("(name string)"))
    }

    @Test
    fun `The type contained in the string should be used as is as the type name`() {
        val type: Pattern = getBuiltInPattern("(JSONDataStructure in string)")

        if(type !is PatternInStringPattern)
            fail("Expected pattern in string")

        assertThat(type.pattern.typeName).isEqualTo("JSONDataStructure")
    }

    @ParameterizedTest
    @MethodSource("bomProvider")
    fun `should be able to parse scalar data with BOM`(bom: ByteOrderMark) {
        val charSet = Charset.forName(bom.charsetName)
        val inputBytes = bom.bytes + "DATA".toByteArray(charSet)
        val inputString = String(inputBytes, charset = charSet)

        assertThat(parsedScalarValue(inputString)).isEqualTo(StringValue("DATA"))
    }

    @ParameterizedTest
    @MethodSource("bomProvider")
    fun `should be able to parse JsonObject data with BOM`(bom: ByteOrderMark) {
        val charSet = Charset.forName(bom.charsetName)
        val inputBytes = bom.bytes + "{\"DATA\" : \"VALUE\"}".toByteArray(charSet)
        val inputString = String(inputBytes, charset = charSet)

        assertDoesNotThrow { parsedValue(inputString) as JSONObjectValue }
        assertDoesNotThrow { parsedJSON(inputString) as JSONObjectValue }
        assertDoesNotThrow { parsedJSONObject(inputString) }
    }

    @ParameterizedTest
    @MethodSource("bomProvider")
    fun `should be able to parse JsonArray data with BOM`(bom: ByteOrderMark) {
        val charSet = Charset.forName(bom.charsetName)
        val inputBytes = bom.bytes + "[\"VALUE\"]".toByteArray(charSet)
        val inputString = String(inputBytes, charset = charSet)

        assertDoesNotThrow { parsedValue(inputString) as JSONArrayValue }
        assertDoesNotThrow { parsedJSON(inputString) as JSONArrayValue }
        assertDoesNotThrow { parsedJSONArray(inputString) }
    }

    @ParameterizedTest
    @MethodSource("bomProvider")
    fun `should be able to parse xml data with BOM`(bom: ByteOrderMark) {
        val charSet = Charset.forName(bom.charsetName)
        val inputBytes = bom.bytes + "<DATA />".toByteArray(charSet)
        val inputString = String(inputBytes, charset = charSet)

        assertDoesNotThrow { toXMLNode(inputString) }
    }

    @Nested
    inner class CustomEmptyStringMessage {
        @Test
        fun `should show custom empty string message when trying to parse it as json object`() {
            assertThatThrownBy {
                parsedJSONObject("")
            }.satisfies(Consumer {
                it as ContractException
                assertThat(it.report()).contains("an empty string")
            })
        }

        @Test
        fun `should show custom empty string message when trying to parse it as json array`() {
            assertThatThrownBy {
                parsedJSONArray("")
            }.satisfies(Consumer {
                it as ContractException
                assertThat(it.report()).contains("an empty string")
            })
        }

        @Test
        fun `should show custom empty string message when trying to parse it as json value`() {
            assertThatThrownBy {
                parsedJSON("")
            }.satisfies(Consumer {
                it as ContractException
                assertThat(it.report()).contains("an empty string")
            })
        }
    }
}