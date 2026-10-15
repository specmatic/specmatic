package io.specmatic.core

import org.json.JSONObject
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.xml.sax.SAXException
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.Value
import io.specmatic.core.value.toXMLNode
import java.io.IOException
import javax.xml.parsers.ParserConfigurationException

class ValueToString {
    @Test
    fun noMessageYieldsEmptyString() {
        val body: Value = EmptyString
        Assertions.assertEquals("", body.toString())
    }

    @Test
    fun jsonStringTest() {
        val jsonString = """{"a": 1, "b": 2}"""
        val jsonObject = JSONObject(jsonString)
        val body: Value = parsedJSON(jsonString)
        val jsonObject2 = JSONObject(body.toString())
        Assertions.assertEquals(jsonObject.getInt("a"), jsonObject2.getInt("a"))
        Assertions.assertEquals(jsonObject.getInt("b"), jsonObject2.getInt("b"))
    }

    @Test
    @Throws(IOException::class, SAXException::class, ParserConfigurationException::class)
    fun xmlStringTest() {
        val xmlData = "<node>1</node>"
        val body: Value = toXMLNode(xmlData)
        val xmlData2 = body.toString()
        val body2 = toXMLNode(xmlData2)
        Assertions.assertEquals("node", body2.name)
        Assertions.assertEquals("1", body2.childNodes[0].toStringLiteral())
    }
}
