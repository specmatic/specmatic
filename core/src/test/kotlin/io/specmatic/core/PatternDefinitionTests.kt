package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.parseXML
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.NumberValue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.w3c.dom.Node
import io.specmatic.core.value.StringValue
import io.specmatic.shouldMatch
import org.junit.jupiter.api.Assertions.assertTrue

class PatternDefinitionTests {
    @Test
    @Throws(Throwable::class)
    fun aliasANewPrimitivePattern() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    Given pattern NewNumber (number) \n" +
                "    When GET /accounts?id=(NewNumber)\n" +
                "    Then status 200\n" +
                "    And response-body {\"name\": \"(string)\"}"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val request = HttpRequest().updateMethod("GET").updatePath("/accounts").updateQueryParam("id", "10")
        val response = contractBehaviour.lookupResponse(request)
        Assertions.assertEquals(200, response.status)
        val responseJSON = JSONObject(response.body.displayableValue())
        assertTrue(StringPattern().matches(StringValue(responseJSON.getString("name")), Resolver()) is Result.Success)
    }

    @Test
    @Throws(Throwable::class)
    fun declareANewComplexJSONPattern() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    Given pattern Address {\"city\": \"(string)\"} \n" +
                "    When POST /accounts\n" +
                "    And request-body {\"name\": \"(string)\", \"address\": \"(Address)\"}\n" +
                "    Then status 200\n"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val request = HttpRequest().updateMethod("POST").updatePath("/accounts").updateBody("{\"name\": \"Jerry\", \"address\": {\"city\": \"Mumbai\"}}")
        val response = contractBehaviour.lookupResponse(request)
        Assertions.assertEquals(200, response.status)
    }

    @Test
    @Throws(Throwable::class)
    fun declareANewComplexXMLPattern() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    Given pattern Location <city>(string)</city> \n" +
                "    When POST /locations\n" +
                "    And request-body <location>(Location)</location> \n" +
                "    Then status 200\n"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val request = HttpRequest().updateMethod("POST").updatePath("/locations").updateBody("<location><city>Mumbai</city></location>")
        val response = contractBehaviour.lookupResponse(request)
        Assertions.assertEquals(200, response.status)
    }

    @Test
    @Throws(Throwable::class)
    fun matchVariableLengthArraysInJSONRequest() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    Given pattern Address {\"city\": \"(string)\"}\n" +
                "    When POST /addresses\n" +
                "    And request-body {\"addresses\": \"(Address*)\"}\n" +
                "    Then status 200\n"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val requestBody = "{\"addresses\": [{\"city\": \"Mumbai\"}, {\"city\": \"Bangalore\"}]}"
        val request = HttpRequest().updateMethod("POST").updatePath("/addresses").updateBody(requestBody)
        val response = contractBehaviour.lookupResponse(request)
        Assertions.assertEquals(200, response.status)
    }

    @Test
    @Throws(Throwable::class)
    fun matchVariableLengthArraysInXMLRequest() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    Given pattern Location <city>(string)</city> \n" +
                "    When POST /locations\n" +
                "    And request-body <locations>(Location*)</locations>\n" +
                "    Then status 200\n"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val requestBody = "<locations><city>Mumbai></city><city>Bangalore</city></locations>"
        val request = HttpRequest().updateMethod("POST").updatePath("/locations").updateBody(requestBody)
        val response = contractBehaviour.lookupResponse(request)
        Assertions.assertEquals(200, response.status)
    }

    @Test
    @Throws(Throwable::class)
    fun generateVariableLengthArraysInJSONResponse() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    Given pattern Address {\"city\": \"(string)\"}\n" +
                "    When GET /addresses\n" +
                "    Then status 200\n" +
                "    And response-body {\"addresses\": \"(Address*)\"}"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val request = HttpRequest().updateMethod("GET").updatePath("/addresses")
        val response = contractBehaviour.lookupResponse(request)
        Assertions.assertEquals(200, response.status)
        val responseObject = JSONObject(response.body.displayableValue())
        val addresses = responseObject.getJSONArray("addresses")
        assertTrue(responseObject.length() > 0)
        for (i in 0 until responseObject.length()) {
            val address = addresses.getJSONObject(i)
            Assertions.assertNotNull(address.getString("city"))
        }
    }

    @Test
    @Throws(Throwable::class)
    fun generateVariableLengthArraysInXMLResponse() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    Given pattern Location <city>(string)</city> \n" +
                "    When GET /locations\n" +
                "    Then status 200\n" +
                "    And response-body <locations>(Location*)</locations>\n"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val request = HttpRequest().updateMethod("GET").updatePath("/locations")
        val response = contractBehaviour.lookupResponse(request)
        Assertions.assertEquals(200, response.status)
        try {
            val document = parseXML(response.body.displayableValue())
            val root: Node = document.documentElement
            Assertions.assertEquals("locations", root.nodeName)
            val childNodes = root.childNodes
            for (i in 0 until childNodes.length) {
                val childNode = childNodes.item(i)
                Assertions.assertEquals("city", childNode.nodeName)
                val cityNode = childNode.firstChild
                assertTrue(cityNode.nodeValue.isNotEmpty())
            }
        } catch(e: Throwable) {
            println(e.stackTrace)
        }
    }

    @Test
    @Throws(Throwable::class)
    fun matchTopLevelPatternInRequest() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    Given pattern Person {\"id\": \"(number)\", \"name\": \"(string)\"} \n" +
                "    When POST /accounts\n" +
                "    And request-body (Person)\n" +
                "    Then status 200"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val request = HttpRequest().updateMethod("POST").updatePath("/accounts").updateBody("{\"id\": 10, \"name\": \"John Doe\"}")
        val response = contractBehaviour.lookupResponse(request)
        Assertions.assertEquals(200, response.status)
    }

    @Test
    @Throws(Throwable::class)
    fun matchTopLevelPatternInResponse() {
        val contractGherkin = "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    Given pattern Person {\"id\": \"(number)\", \"name\": \"(string)\"} \n" +
                "    When GET /accounts?id=(number)\n" +
                "    Then status 200\n" +
                "    And response-body (Person)"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val request = HttpRequest().updateMethod("GET").updatePath("/accounts").updateQueryParam("id", "10")
        val response = contractBehaviour.lookupResponse(request)
        Assertions.assertEquals(200, response.status)
        val responseJSON = JSONObject(response.body.displayableValue())
        assertTrue(StringPattern().matches(StringValue(responseJSON.getString("name")), Resolver()) is Result.Success)
    }

    @Test
    @Throws(Throwable::class)
    fun generateTopLevelArrayInRequestAndResponse() {
        val contractGherkin = "Feature: Contract for updating accounts API\n\n" +
                "  Scenario: api call\n\n" +
                "    Given pattern Person {\"id\": \"(number)\", \"name\": \"(string)\"} \n" +
                "    And pattern People (Person*) \n" +
                "    And pattern Id {\"id\": \"(number)\"}\n" +
                "    And pattern Ids (Id*)\n" +
                "    When POST /accounts\n" +
                "    And request-body (People)\n" +
                "    Then status 200\n" +
                "    And response-body (Ids)"
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val request = HttpRequest().updateMethod("POST").updatePath("/accounts").updateBody("[{\"id\": 10, \"name\": \"John Doe\"}, {\"id\": 20, \"name\": \"Jane Doe\"}]")
        val response = contractBehaviour.lookupResponse(request)
        Assertions.assertEquals(200, response.status)
        val responseArray = JSONArray(response.body.displayableValue())
        for (i in 0 until responseArray.length()) {
            val id = responseArray.getJSONObject(i)
            assertThat(id["id"]).isInstanceOf(Number::class.java)
        }
    }

    @Test
    fun `(string) should match an empty string`() {
        val jsonString = """{"name": ""}"""
        val jsonPattern = """{"name": "(string)"}"""

        val value = parsedValue(jsonString)
        val pattern = parsedPattern(jsonPattern, null)
        assertTrue(pattern.matches(value, Resolver()).isSuccess())
    }

    @Test
    fun `(boolean) should match a boolean value`() {
        val boolValue = true
        val boolPattern = BooleanPattern()

        BooleanValue(boolValue) shouldMatch  boolPattern
    }

    @Test
    fun `(boolean) should match a boolean value in a JSON Object`() {
        val jsonString = """{"result": true}"""
        val jsonPattern = """{"result": "(boolean)"}"""

        parsedValue(jsonString) shouldMatch parsedPattern(jsonPattern, null)
    }

    @Test
    fun `(number) should match a decimal value`() {
        val value = 10.5
        val pattern = NumberPattern()

        NumberValue(value) shouldMatch pattern
    }

    @Test
    fun `(number) should match a decimal value in a JSON Object`() {
        val jsonString = """{"result": 10.1}"""
        val jsonPattern = """{"result": "(number)"}"""

        parsedValue(jsonString) shouldMatch parsedPattern(jsonPattern, null)
    }

    @Test
    fun `a key suffixed with a question mark is equivalent to one without`() {
        val jsonString = """{"result": 10.1, "id": 10}"""
        val jsonPattern = """{"result": "(number)", "id?": "(number)"}"""

        parsedValue(jsonString) shouldMatch parsedPattern(jsonPattern, null)
    }

    @Test
    fun `A json object value missing a key (say id) will match the pattern having that key suffixed with a question mark`() {
        val jsonString = """{"result": 10.1}"""
        val jsonPattern = """{"result": "(number)", "id?": "(number)"}"""

        parsedValue(jsonString) shouldMatch parsedPattern(jsonPattern, null)
    }
}
