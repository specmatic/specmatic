package io.specmatic.core.pattern

import io.specmatic.core.value.*
import org.apache.commons.io.ByteOrderMark
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.nio.charset.Charset
import java.util.function.Consumer
import java.util.stream.Stream

internal class GrammarKtTest {
    companion object {
        @JvmStatic
        fun bomProvider(): List<ByteOrderMark> {
            return ByteOrderMark::class.java.fields.mapNotNull { it.get(null) as? ByteOrderMark }
        }

        @JvmStatic
        fun quotedJsonScalarProvider(): List<String> = listOf(
            "\"null\"",
            "\"123\"",
            "\"true\"",
            "\"line\\nquote\\\"slash\\\\\"",
        )

        @JvmStatic
        fun contentToFormatProvider(): Stream<Arguments> {
            val expectedValue = JSONObjectValue(mapOf("hello" to StringValue("world")))
            val expectedXmlValue = XMLNode("hello", "hello", emptyMap(), listOf(StringValue("world")), "", emptyMap())
            return Stream.of(
                Arguments.of("{\"hello\": \"world\"}", "json", expectedValue),
                Arguments.of("hello: world", "yaml", expectedValue),
                Arguments.of("hello: world", "yml", expectedValue),
                Arguments.of("<hello>world</hello>", "xml", expectedXmlValue),
                Arguments.of("null", "txt", StringValue("null")),
            )
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

    @Test
    fun `null literal remains a scalar string value`() {
        assertThat(parsedScalarValue("null")).isEqualTo(StringValue("null"))
    }

    @Test
    fun `null literal with surrounding whitespace remains a scalar string value`() {
        assertThat(parsedScalarValue("  null\n")).isEqualTo(StringValue("null"))
    }

    @ParameterizedTest
    @MethodSource("bomProvider")
    fun `null literal with BOM remains a scalar string value`(bom: ByteOrderMark) {
        val charSet = Charset.forName(bom.charsetName)
        val inputBytes = bom.bytes + "null".toByteArray(charSet)
        val inputString = String(inputBytes, charset = charSet)

        assertThat(parsedScalarValue(inputString)).isEqualTo(StringValue("null"))
    }

    @Test
    fun `json null literal parses to null value`() {
        assertThat(parsedJsonValue("  null\n")).isEqualTo(NullValue)
    }

    @ParameterizedTest
    @MethodSource("bomProvider")
    fun `json null literal with BOM parses to null value`(bom: ByteOrderMark) {
        val charSet = Charset.forName(bom.charsetName)
        val inputBytes = bom.bytes + "  null\n".toByteArray(charSet)
        val inputString = String(inputBytes, charset = charSet)

        assertThat(parsedJsonValue(inputString)).isEqualTo(NullValue)
    }

    @ParameterizedTest
    @MethodSource("quotedJsonScalarProvider")
    fun `quoted json scalar preserves its wire representation`(input: String) {
        assertThat(parsedJsonValue(input)).isEqualTo(StringValue(input))
    }

    @Test
    fun `uppercase null literal remains a string value`() {
        assertThat(parsedScalarValue("NULL")).isEqualTo(StringValue("NULL"))
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

    @Test
    fun `email pattern should be recognized as a built-in pattern`() {
        val pattern = getBuiltInPattern("(email)")
        assertThat(pattern).isInstanceOf(EmailPattern::class.java)
    }

    @ParameterizedTest
    @MethodSource("contentToFormatProvider")
    fun `readValue should be able to read various file formats`(content: String, extension: String, expected: Value) {
        val contentFile = File.createTempFile("content", ".$extension").apply { writeText(content) }
        val value = readValue(contentFile)
        assertThat(value).isEqualTo(expected)
    }
}
