package io.specmatic.core

import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.utilities.parseXML
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue

class XMLMatching {
    private val threeQuotes = "\"\"\""

    @Test
    @Throws(Throwable::class)
    fun theContractShouldSupportXMLResponseGeneration() {
        val contractGherkin = """
Feature: Unit test
  Scenario: Unit test
    When GET /balance?account_id=(number)
    Then status 200
    And response-body <account type="(string)"><name>(string)</name><address>(string)</address><age>(number)</age></account>
"""

        val contractGherkinWithDocString = """
Feature: Unit test
  Scenario: Unit test
    When GET /balance?account_id=(number)
    Then status 200
    And response-body
    $threeQuotes
    <account type="(string)"><name>(string)</name><address>(string)</address><age>(number)</age></account>
    $threeQuotes
"""

        testResponseGeneration(contractGherkin)
        testResponseGeneration(contractGherkinWithDocString)
    }

    private fun testResponseGeneration(contractGherkin: String) {
        try {
            val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
            val request = HttpRequest().updateMethod("GET").updatePath("/balance").updateQueryParam("account_id", "10")
            val response = contractBehaviour.lookupResponse(request)
            Assertions.assertEquals(200, response.status)
            val root = parseXML(response.body.displayableValue()).documentElement
            val type = root.attributes.getNamedItem("type").nodeValue
            val name = root.childNodes.item(0).childNodes.item(0).nodeValue
            val address = root.childNodes.item(1).childNodes.item(0).nodeValue
            val age = root.childNodes.item(2).childNodes.item(0).nodeValue
            Assertions.assertTrue(StringPattern().matches(StringValue(type), Resolver()) is Result.Success)
            Assertions.assertTrue(StringPattern().matches(StringValue(name), Resolver()) is Result.Success)
            Assertions.assertTrue(StringPattern().matches(StringValue(address), Resolver()) is Result.Success)
            Assertions.assertTrue( NumberPattern().matches(NumberValue(age.toInt()), Resolver()) is Result.Success)
        }
        catch(e: ContractException) {
            println(e.report())
        }
        catch (e: Throwable) {
            println(e.stackTrace)
        }
    }

    @Test
    @Throws(Throwable::class)
    fun theContractShouldSupportXMLRequestMatching() {
        val contractGherkin = """
Feature: Unit test
  Scenario: Unit test
    When POST /balance
    And request-body <account type="(string)"><name>(string)</name><address>(string)</address><age>(number)</age></account>
    Then status 200
"""
        val contractGherkinWithDocString = """
Feature: Unit test
  Scenario: Unit test
    When POST /balance
    And request-body
    $threeQuotes
    <account type="(string)"><name>(string)</name><address>(string)</address><age>(number)</age></account>
    $threeQuotes
    Then status 200
"""
        testResponseMatching(contractGherkin)
        testResponseMatching(contractGherkinWithDocString)
    }

    private fun testResponseMatching(contractGherkin: String) {
        val contractBehaviour = parseGherkinStringToFeature(contractGherkin)
        val request = HttpRequest().updateMethod("POST").updatePath("/balance").updateBody("<account type=\"user\"><name>John</name><address>Mumbai</address><age>25</age></account>")
        val response = contractBehaviour.lookupResponse(request)
        Assertions.assertEquals(200, response.status)
    }
}
