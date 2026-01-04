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
        fun contentToFormatProvider(): Stream<Arguments> {
            val expectedValue = JSONObjectValue(mapOf("hello" to StringValue("world")))
            val expectedXmlValue = XMLNode("hello", "hello", emptyMap(), listOf(StringValue("world")), "", emptyMap())
            return Stream.of(
                Arguments.of("{\"hello\": \"world\"}", "json", expectedValue),
                Arguments.of("hello: world", "yaml", expectedValue),
                Arguments.of("hello: world", "yml", expectedValue),
                Arguments.of("<hello>world</hello>", "xml", expectedXmlValue),
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

    @Test
    fun `parsedValue with contentType application-json should parse JSON strictly`() {
        val jsonObject = """{"hello": "world"}"""
        val jsonArray = """["item1", "item2"]"""
        
        val objResult = parsedValue(jsonObject, "application/json")
        val arrayResult = parsedValue(jsonArray, "application/json")
        
        assertThat(objResult).isInstanceOf(JSONObjectValue::class.java)
        assertThat(arrayResult).isInstanceOf(JSONArrayValue::class.java)
    }

    @Test
    fun `parsedValue with contentType application-json should throw exception for non-JSON content`() {
        assertThatThrownBy {
            parsedValue("not json content", "application/json")
        }.isInstanceOf(ContractException::class.java)
            .hasMessageContaining("Expected JSON content")
    }

    @Test
    fun `parsedValue with contentType application-xml should parse XML strictly`() {
        val xmlContent = "<root>value</root>"
        
        val result = parsedValue(xmlContent, "application/xml")
        
        assertThat(result).isInstanceOf(XMLNode::class.java)
    }

    @Test
    fun `parsedValue with contentType text-xml should parse XML strictly`() {
        val xmlContent = "<root>value</root>"
        
        val result = parsedValue(xmlContent, "text/xml")
        
        assertThat(result).isInstanceOf(XMLNode::class.java)
    }

    @Test
    fun `parsedValue with contentType text-plain should parse as string`() {
        val content = """{"this": "looks like json"}"""
        
        val result = parsedValue(content, "text/plain")
        
        assertThat(result).isInstanceOf(StringValue::class.java)
        assertThat((result as StringValue).string).isEqualTo(content)
    }

    @Test
    fun `parsedValue with contentType text-slash-anything should parse as string`() {
        val content = """<this>looks like xml</this>"""
        
        val result = parsedValue(content, "text/html")
        
        assertThat(result).isInstanceOf(StringValue::class.java)
        assertThat((result as StringValue).string).isEqualTo(content)
    }

    @Test
    fun `parsedValue with unknown contentType should fallback to guessing`() {
        val jsonContent = """{"hello": "world"}"""
        val xmlContent = """<root>value</root>"""
        val plainContent = """plain text"""
        
        val jsonResult = parsedValue(jsonContent, "unknown/type")
        val xmlResult = parsedValue(xmlContent, "unknown/type")
        val plainResult = parsedValue(plainContent, "unknown/type")
        
        assertThat(jsonResult).isInstanceOf(JSONObjectValue::class.java)
        assertThat(xmlResult).isInstanceOf(XMLNode::class.java)
        assertThat(plainResult).isInstanceOf(StringValue::class.java)
    }

    @Test
    fun `parsedValue with null contentType should use existing guessing behavior`() {
        val jsonContent = """{"hello": "world"}"""
        val xmlContent = """<root>value</root>"""
        val plainContent = """plain text"""
        
        val jsonResult = parsedValue(jsonContent, null)
        val xmlResult = parsedValue(xmlContent, null)
        val plainResult = parsedValue(plainContent, null)
        
        assertThat(jsonResult).isInstanceOf(JSONObjectValue::class.java)
        assertThat(xmlResult).isInstanceOf(XMLNode::class.java)
        assertThat(plainResult).isInstanceOf(StringValue::class.java)
    }

    @Test
    fun `parsedValue with JSON subtype content types should parse as JSON`() {
        val content = """{"data": "test"}"""
        
        // Test various JSON subtypes that should be parsed as JSON
        val mergePatchResult = parsedValue(content, "application/merge-patch+json")
        val jsonPatchResult = parsedValue(content, "application/json-patch+json")
        val customJsonResult = parsedValue(content, "application/vnd.api+json")
        
        assertThat(mergePatchResult).isInstanceOf(JSONObjectValue::class.java)
        assertThat(jsonPatchResult).isInstanceOf(JSONObjectValue::class.java)
        assertThat(customJsonResult).isInstanceOf(JSONObjectValue::class.java)
        
        // Verify the content is correctly parsed
        assertThat((mergePatchResult as JSONObjectValue).jsonObject["data"]?.toStringLiteral()).isEqualTo("test")
    }

    @Test
    fun `parsedValue with JSON subtype content types handles charset correctly`() {
        val content = """{"message": "hello"}"""
        
        val result = parsedValue(content, "application/merge-patch+json; charset=utf-8")
        
        assertThat(result).isInstanceOf(JSONObjectValue::class.java)
        assertThat((result as JSONObjectValue).jsonObject["message"]?.toStringLiteral()).isEqualTo("hello")
    }

    @Test
    fun `parsedValue with contentType containing charset should work correctly`() {
        val jsonContent = """{"hello": "world"}"""
        
        val result = parsedValue(jsonContent, "application/json; charset=utf-8")
        
        assertThat(result).isInstanceOf(JSONObjectValue::class.java)
    }

    @Test
    fun `parsedValue with case insensitive contentType should work correctly`() {
        val jsonContent = """{"hello": "world"}"""
        val xmlContent = """<root>value</root>"""
        
        val jsonResult = parsedValue(jsonContent, "APPLICATION/JSON")
        val xmlResult = parsedValue(xmlContent, "TEXT/XML")
        
        assertThat(jsonResult).isInstanceOf(JSONObjectValue::class.java)
        assertThat(xmlResult).isInstanceOf(XMLNode::class.java)
    }
}