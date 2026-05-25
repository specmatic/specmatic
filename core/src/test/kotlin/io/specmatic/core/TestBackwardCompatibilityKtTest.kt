package io.specmatic.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.flipkart.zjsonpatch.JsonPatch
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.reporter.internal.dto.bcc.ChangeStatus
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.core.utilities.Flags.Companion.using
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
import io.specmatic.reporter.api.client.OBJECT_MAPPER
import io.specmatic.stub.captureStandardOutput
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

internal class TestBackwardCompatibilityKtTest {
    private fun assertBackwardCompatibilityFailure(results: Results, expectedReport: String) {
        assertThat(results.success()).withFailMessage(results.report()).isFalse
        assertThat(results.report()).isEqualToNormalizingNewlines(expectedReport.trimIndent())
    }

    @Test
    fun `when a contract is run as test against another contract fake, it should fail if the two contracts are incompatible`() {
        val oldContract = """Feature: Contract
Scenario: api call
Given GET /
Then status 200
"""

        val newContract = """Feature: Contract
Scenario: api call
Given GET /d
Then status 200"""

        val olderContract = parseGherkinStringToFeature(oldContract)
        val newerContract = parseGherkinStringToFeature(newContract)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertThat(result.success()).withFailMessage(result.report()).isFalse
        assertThat(result.report()).isEqualToNormalizingNewlines("""
        In scenario "api call"
        API: GET / -> 200
        
              This API exists in the old contract but not in the new contract
        """.trimIndent())
    }

    @Test
    fun `contract backward compatibility should break when optional key is made mandatory in request`() {
        val gherkin1 = """
Feature: Older contract API

Scenario: api call
Given json Value
| value     | (number) |
| optional? | (number) |
When POST /value
And request-body (Value)
Then status 200
    """.trim()

        val gherkin2 = """
Feature: Newer contract API

Scenario: api call
Given json Value
| value    | (number) |
| optional | (number) |
When POST /value
And request-body (Value)
Then status 200
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertThat(result.success()).withFailMessage(result.report()).isFalse
        assertThat(result.report()).isEqualToNormalizingNewlines("""
        In scenario "api call"
        API: POST /value -> 200
        
          >> REQUEST.BODY.optional
          
              R2001: Missing required property
              Documentation: https://docs.specmatic.io/rules#r2001
              Summary: A required property defined in the specification is missing
          
              New specification expects property "optional" in the request but it is missing from the old specification
        """.trimIndent())
    }

    @Test
    fun `contract backward compatibility should break when mandatory key is made optional in response`() {
        val gherkin1 = """
Feature: Older contract API

Scenario: api call
Given json Value
| value     | (number) |
| mandatory | (number) |
When POST /value
And request-body "test"
Then status 200
And response-body (Value)
    """.trim()

        val gherkin2 = """
Feature: Newer contract API

Scenario: api call
Given json Value
| value      | (number) |
| mandatory? | (number) |
When POST /value
And request-body "test"
Then status 200
And response-body (Value)
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "api call"
            API: POST /value -> 200
            
              >> RESPONSE.BODY.mandatory
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  The old specification expects property "mandatory" but it is missing in the new specification
            """
        )
    }

    @Test
    fun `contract backward compatibility should break when there is value incompatibility one level down`() {
        val gherkin1 = """
Feature: Old contract
  Scenario: Test Scenario
    Given type RequestBody
    | address? | (Address?) |
    And type Address
    | street | (string) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val gherkin2 = """
Feature: New contract
  Scenario: Test Scenario
    Given type RequestBody
    | address? | (Address?) |
    And type Address
    | street | (number) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "Test Scenario"
            API: POST / -> 200
            
              >> REQUEST.BODY.address.street
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type number in the new specification, but type string in the old specification
            """
        )
    }

    @Test
    fun `contract backward compatibility should break when optional key is made mandatory one level down in request`() {
        val gherkin1 = """
Feature: Old contract
  Scenario: Test Scenario
    Given type RequestBody
    | address | (Address) |
    And type Address
    | street? | (string) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val gherkin2 = """
Feature: New contract
  Scenario: Test Scenario
    Given type RequestBody
    | address | (Address) |
    And type Address
    | street | (string) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "Test Scenario"
            API: POST / -> 200
            
              >> REQUEST.BODY.address.street
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "street" in the request but it is missing from the old specification
            """
        )
    }

    @Test
    fun `contract backward compatibility should break when optional key is made mandatory inside an optional parent`() {
        val gherkin1 = """
Feature: Old contract
  Scenario: Test Scenario
    Given type RequestBody
    | address? | (Address) |
    And type Address
    | street? | (string) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val gherkin2 = """
Feature: New contract
  Scenario: Test Scenario
    Given type RequestBody
    | address? | (Address) |
    And type Address
    | street | (string) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "Test Scenario"
            API: POST / -> 200
            
              >> REQUEST.BODY.address.street
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "street" in the request but it is missing from the old specification
            """
        )
    }

    @Test
    fun `contract backward compatibility should break when optional key is made mandatory inside an optional parent two levels down`() {
        val gherkin1 = """
Feature: Old contract
  Scenario: Test Scenario
    Given type RequestBody
    | person | (Person) |
    And type Person
    | address? | (Address) |
    And type Address
    | street? | (string) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val gherkin2 = """
Feature: Old contract
  Scenario: Test Scenario
    Given type RequestBody
    | person | (Person) |
    And type Person
    | address? | (Address) |
    And type Address
    | street | (string) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "Test Scenario"
            API: POST / -> 200
            
              >> REQUEST.BODY.person.address.street
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "street" in the request but it is missing from the old specification
            """
        )
    }

    @Test
    fun `contract backward compatibility should break when optional key data type is changed inside an optional parent two levels down`() {
        val gherkin1 = """
Feature: Old contract
  Scenario: Test Scenario
    Given type RequestBody
    | person | (Person) |
    And type Person
    | address? | (Address) |
    And type Address
    | street? | (string) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val gherkin2 = """
Feature: Old contract
  Scenario: Test Scenario
    Given type RequestBody
    | person | (Person) |
    And type Person
    | address? | (Address) |
    And type Address
    | street | (number) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "Test Scenario"
            API: POST / -> 200
            
              >> REQUEST.BODY.person.address.street
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type number in the new specification, but type string in the old specification
            
            In scenario "Test Scenario"
            API: POST / -> 200
            
              >> REQUEST.BODY.person.address.street
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "street" in the request but it is missing from the old specification
            """
        )
    }

    @Test
    fun `contract backward compatibility should break when optional value is made mandatory inside an optional parent two levels down`() {
        val gherkin1 = """
Feature: Old contract
  Scenario: Test Scenario
    Given type RequestBody
    | person | (Person) |
    And type Person
    | address? | (Address) |
    And type Address
    | street? | (string?) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val gherkin2 = """
Feature: Old contract
  Scenario: Test Scenario
    Given type RequestBody
    | person | (Person) |
    And type Person
    | address? | (Address) |
    And type Address
    | street? | (string) |
    When POST /
    And request-body (RequestBody)
    Then status 200
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "Test Scenario"
            API: POST / -> 200
            
              >> REQUEST.BODY.person.address.street
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type string in the new specification, but type null in the old specification
            """
        )
    }

    @Test
    fun `contract backward compatibility should break when mandatory key is made optional one level down in response`() {
        val gherkin1 = """
Feature: Old contract
  Scenario: Test Scenario
    Given type ResponseBody
    | address | (Address) |
    And type Address
    | street | (string) |
    When POST /
    And request-body "test"
    Then status 200
    And response-body (ResponseBody)
    """.trim()

        val gherkin2 = """
Feature: New contract
  Scenario: Test Scenario
    Given type ResponseBody
    | address | (Address) |
    And type Address
    | street? | (string) |
    When POST /
    And request-body "test"
    Then status 200
    And response-body (ResponseBody) 
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "Test Scenario"
            API: POST / -> 200
            
              >> RESPONSE.BODY.address.street
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  The old specification expects property "street" but it is missing in the new specification
            """
        )
    }

    @Test
    fun `contract backward compatibility should break when mandatory key is made optional two levels down in response`() {
        val gherkin1 = """
Feature: Old contract
  Scenario: Test Scenario
    Given type ResponseBody
    | person | (Person) |
    And type Person
    | address? | (Address) |
    And type Address
    | street | (string) |
    When POST /
    And request-body "test"
    Then status 200
    And response-body (ResponseBody)
    """.trim()

        val gherkin2 = """
Feature: New contract
  Scenario: Test Scenario
    Given type ResponseBody
    | person | (Person) |
    And type Person
    | address? | (Address) |
    And type Address
    | street? | (string) |
    When POST /
    And request-body "test"
    Then status 200
    And response-body (ResponseBody) 
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "Test Scenario"
            API: POST / -> 200
            
              >> RESPONSE.BODY.person.address.street
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  The old specification expects property "street" but it is missing in the new specification
            """
        )
    }

    @Test
    fun `contract backward compatibility should not break when both have an optional keys`() {
        val gherkin1 = """
Feature: API contract

Scenario: api call
Given json Value
| value     | (number) |
| optional? | (number) |
And fact id 10
When POST /value/(id:number)
And request-body (Value)
Then status 200
    """.trim()

        val gherkin2 = """
Feature: API contract

Scenario: api call
Given json Value
| value    | (number) |
| optional? | (number) |
And fact id 10
When POST /value/(id:number)
And request-body (Value)
Then status 200
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        println(result.report())

        assertThat(result.success()).isTrue
    }

    @Test
    fun `contract backward compatibility should break when a new fact is added`() {
        val gherkin1 = """
Feature: Older contract API

Scenario: api call
Given json Value
| value     | (number) |
| optional? | (number) |
When POST /value/(id:number)
And request-body (Value)
Then status 200
    """.trim()

        val gherkin2 = """
Feature: Older contract API

Scenario: api call
Given json Value
| value    | (number) |
| optional? | (number) |
And fact id 10
When POST /value/(id:number)
And request-body (Value)
Then status 200
    """.trim()

        val olderContract = parseGherkinStringToFeature(gherkin1)
        val newerContract = parseGherkinStringToFeature(gherkin2)

        val results: Results = testBackwardCompatibility(olderContract, newerContract)

        println(results.report())

        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "api call"
            API: POST /value/(id:number) -> 200
            
              >> FACTS
              
                  Facts mismatch
            """
        )
    }

    @Test
    fun `contract should test successfully against itself when fact name is specified without a value in the URL path`() {
        val gherkin = """
Feature: Contract API

Scenario: api call
Given fact id
When POST /value/(id:number)
Then status 200
    """.trim()

        val contract = parseGherkinStringToFeature(gherkin)

        val results: Results = testBackwardCompatibility(contract, contract)

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.success()).withFailMessage(results.report()).isTrue
    }

    @Test
    fun `contract should test successfully against itself when fact name is specified without a value in the query`() {
        val gherkin = """
Feature: Contract API

Scenario: Test Contract
Given fact id
When GET /value?id=(number)
Then status 200
    """.trim()

        val contract = parseGherkinStringToFeature(gherkin)

        val results: Results = testBackwardCompatibility(contract, contract)

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.success()).withFailMessage(results.report()).isTrue
    }

    @Test
    fun `should be able to validate new contract compatibility with optional request body`() {
        val gherkin = """
Feature: Contract API

Scenario: api call
When POST /number
And request-body (number?)
Then status 200
    """.trim()

        val contract = parseGherkinStringToFeature(gherkin)

        val results: Results = testBackwardCompatibility(contract, contract)

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.success()).withFailMessage(results.report()).isTrue
    }

    @Test
    fun `should be able to validate new contract compatibility with optional key in request body`() {
        val gherkin = """
Feature: Contract API

Scenario: api call
Given json Number
| number | (number?) |
When POST /number
And request-body (Number)
Then status 200
    """.trim()

        val contract = parseGherkinStringToFeature(gherkin)

        val results: Results = testBackwardCompatibility(contract, contract)

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.success()).withFailMessage(results.report()).isTrue
    }

    @Test
    fun `should be able to validate new contract compatibility with optional response body`() {
        val gherkin = """
Feature: Contract API

Scenario: api call
When POST /number
Then status 200
And response-body (number?)
    """.trim()

        val contract = parseGherkinStringToFeature(gherkin)

        val results: Results = testBackwardCompatibility(contract, contract)

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.success()).withFailMessage(results.report()).isTrue
    }

    @Test
    fun `should be able to validate new contract compatibility with optional key in response body`() {
        val gherkin = """
Feature: Contract API

Scenario: api call
Given json Number
| number | (number?) |
When POST /number
Then status 200
And response-body (Number)
    """.trim()

        val contract = parseGherkinStringToFeature(gherkin)

        val results: Results = testBackwardCompatibility(contract, contract)

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.success()).withFailMessage(results.report()).isTrue
    }

    @Test
    fun `contract with an optional key in the response should pass against itself`() {
        val behaviour = parseGherkinStringToFeature(
            """
Feature: Contract API

Scenario: api call
When POST /number
And request-body (number)
Then status 200
And response-body
| number       | (number) |
| description? | (string) |
""".trim()
        )

        val results: Results = testBackwardCompatibility(behaviour, behaviour)

        println(results.report())
        assertThat(results.success()).withFailMessage(results.report()).isTrue
    }

    @Test
    fun `should work with multipart content part`() {
        val behaviour = parseGherkinStringToFeature(
            """
Feature: Contract API

Scenario: api call
When POST /number
And request-part number (number)
Then status 200
And response-body
| number       | (number) |
| description? | (string) |
""".trim()
        )

        val results: Results = testBackwardCompatibility(behaviour, behaviour)

        println(results.report())
        assertThat(results.success()).withFailMessage(results.report()).isTrue
    }

    @Test
    fun `should work with multipart file part`() {
        val behaviour = parseGherkinStringToFeature(
            """
Feature: Contract API

Scenario: api call
When POST /number
And request-part number @number.txt text/plain
Then status 200
And response-body
| number       | (number) |
| description? | (string) |
""".trim()
        )

        val results: Results = testBackwardCompatibility(behaviour, behaviour)

        println(results.report())
        assertThat(results.success()).isTrue
    }

    @Test
    fun `should fail given a file part in one and a content part in the other`() {
        val older = parseGherkinStringToFeature(
            """
Feature: Contract API

Scenario: api call
When POST /number
And request-part number @number.txt text/plain
Then status 200
And response-body
| number       | (number) |
| description? | (string) |
""".trim()
        )

        val newer = parseGherkinStringToFeature(
            """
Feature: Contract API

Scenario: api call
When POST /number
And request-part number (number))
Then status 200
And response-body
| number       | (number) |
| description? | (string) |
""".trim()
        )

        val results: Results = testBackwardCompatibility(older, newer)

        println(results.report())
        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "api call"
            API: POST /number -> 200
            
              >> REQUEST.MULTIPART-FORMDATA.number.content
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  Type (number)) does not exist
            """
        )
    }

    @Test
    fun `a contract should be backward compatible with itself`() {
        val gherkin = """
Feature: Contract API

Scenario: api call
When POST /number
Then status 200
And response-body (number)
    """.trim()

        val contract = parseGherkinStringToFeature(gherkin)

        val results: Results = testBackwardCompatibility(contract, contract)

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.success()).isTrue
    }

    @Test
    fun `a contract with named patterns should be backward compatible with itself`() {
        val gherkin = """
Feature: Contract API

Scenario: api call
Given json Payload
  | number | (number) |
When POST /number
  And request-body (Payload)
Then status 200
And response-body (number)
    """.trim()

        val contract = parseGherkinStringToFeature(gherkin)

        val results: Results = testBackwardCompatibility(contract, contract)

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.success()).isTrue
    }

    @Test
    fun `a contract with named patterns should not be backward compatible with another contract with a different pattern against the same name`() {
        val gherkin1 = """
Feature: Contract API

Scenario: api call
Given json Payload
  | number | (number) |
When POST /number
  And request-body (Payload)
Then status 200
And response-body (number)
    """.trim()

        val gherkin2 = """
Feature: Contract API

Scenario: api call
Given json Payload
  | number | (string) |
When POST /number
  And request-body (Payload)
Then status 200
And response-body (number)
    """.trim()

        val results: Results =
            testBackwardCompatibility(parseGherkinStringToFeature(gherkin1), parseGherkinStringToFeature(gherkin2))

        if (results.failureCount > 0)
            println(results.report())

        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "api call"
            API: POST /number -> 200
            
              >> REQUEST.BODY.number
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type string in the new specification, but type number in the old specification
            """
        )
    }

    @Test
    fun `a breaking WIP scenario should not break backward compatibility tests`() {
        val gherkin1 = """
Feature: Contract API

@WIP
Scenario: api call
When POST /data
  And request-body (number)
Then status 200
    """.trim()

        val gherkin2 = """
Feature: Contract API

@WIP
Scenario: api call
When POST /data
  And request-body (string)
Then status 200
    """.trim()

        val results: Results =
            testBackwardCompatibility(parseGherkinStringToFeature(gherkin1), parseGherkinStringToFeature(gherkin2))

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.success()).isTrue
        assertThat(results.hasFailures()).isFalse()
    }

    @Test
    fun `two xml contracts should be backward compatibility when the only thing changing is namespace prefixes`() {
        val gherkin1 = """
Feature: Contract API

Scenario: api call
When POST /data
  And request-body <ns1:customer xmlns:ns1="http://example.com/customer"><name>(string)</name></ns1:customer>
Then status 200
    """.trim()

        val gherkin2 = """
Feature: Contract API

Scenario: api call
When POST /data
  And request-body <ns2:customer xmlns:ns2="http://example.com/customer"><name>(string)</name></ns2:customer>
Then status 200
    """.trim()

        val results: Results =
            testBackwardCompatibility(parseGherkinStringToFeature(gherkin1), parseGherkinStringToFeature(gherkin2))

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.success()).isTrue
        assertThat(results.success()).isTrue()
    }

    @Test
    fun `two xml contracts should not be backward compatibility when optional key is made mandatory in request`() {
        val gherkin1 = """
Feature: Contract API

Scenario: api call
When POST /data
  And request-body <ns1:customer xmlns:ns1="http://example.com/customer"><name specmatic_occurs="optional">(string)</name></ns1:customer>
Then status 200
    """.trim()

        val gherkin2 = """
Feature: Contract API

Scenario: api call
When POST /data
  And request-body <ns2:customer xmlns:ns2="http://example.com/customer"><name>(string)</name></ns2:customer>
Then status 200
    """.trim()

        val results: Results =
            testBackwardCompatibility(parseGherkinStringToFeature(gherkin1), parseGherkinStringToFeature(gherkin2))

        if (results.failureCount > 0)
            println(results.report())

        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "api call"
            API: POST /data -> 200
            
              >> REQUEST.BODY.customer.name
              
                  Didn't get enough values
            """
        )
    }

    @Test
    fun `two xml contracts should not be backward compatibility when mandatory key is made optional in response`() {
        val gherkin1 = """
Feature: Contract API

Scenario: api call
When POST /data
  And request-body "test"
Then status 200
  And response-body <ns1:customer xmlns:ns1="http://example.com/customer"><name>(string)</name></ns1:customer>
    """.trim()

        val gherkin2 = """
Feature: Contract API

Scenario: api call
When POST /data
  And request-body "test"
Then status 200
  And response-body <ns1:customer xmlns:ns1="http://example.com/customer"><name specmatic_occurs="optional">(string)</name></ns1:customer>
    """.trim()

        val results: Results =
            testBackwardCompatibility(parseGherkinStringToFeature(gherkin1), parseGherkinStringToFeature(gherkin2))

        if (results.failureCount > 0)
            println(results.report())

        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "api call"
            API: POST /data -> 200
            
              >> RESPONSE.BODY.customer.name
              
                  This node must occur whereas the other is optional.
            """
        )
    }

    @Test
    fun `a contract with an optional and a required node in that order should be backward compatible with itself`() {
        val docString = "\"\"\""
        val gherkin =
            """
                Feature: test xml
                    Scenario: Test xml
                    Given type RequestBody
                    $docString
                        <parent>
                            <optionalNode specmatic_occurs="optional" />
                            <requiredNode />
                        </parent>
                    $docString
                    When POST /
                    And request-body (RequestBody)
                    Then status 200
            """.trimIndent()

        val feature = parseGherkinStringToFeature(gherkin)
        val results = testBackwardCompatibility(feature, feature)

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.success()).isTrue()
    }

    @Test
    fun `backward compatibility error in optional key should not contain a question mark`() {
        val olderContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          description: Sample
          version: 1
        servers:
          - url: http://api.example.com/v1
            description: Optional server description, e.g. Main (production) server
        paths:
          /data:
            get:
              summary: hello world
              description: Optional extended description in CommonMark or HTML.
              responses:
                '200':
                  description: Says hello
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          data:
                            type: string
            """.trimIndent().openAPIToContract()

        val newerContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          description: Sample
          version: 1
        servers:
          - url: http://api.example.com/v1
            description: Optional server description, e.g. Main (production) server
        paths:
          /data:
            get:
              summary: hello world
              description: Optional extended description in CommonMark or HTML.
              responses:
                '200':
                  description: Says hello
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          data:
                            type: number
            """.trimIndent().openAPIToContract()

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        assertThat(result.report()).doesNotContain("data?")
    }

    @Nested
    inner class EnumStringIsBackwardCompatibleWithString {
        private val specWithStringInResponse: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          description: Sample
          version: 1
        servers:
          - url: http://api.example.com/v1
            description: Optional server description, e.g. Main (production) server
        paths:
          /data:
            get:
              summary: hello world
              description: Optional extended description in CommonMark or HTML.
              responses:
                '200':
                  description: Says hello
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          data:
                            type: string
            """.trimIndent().openAPIToContract()

        private val specWithEnumInResponse: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          description: Sample
          version: 1
        servers:
          - url: http://api.example.com/v1
            description: Optional server description, e.g. Main (production) server
        paths:
          /data:
            get:
              summary: hello world
              description: Optional extended description in CommonMark or HTML.
              responses:
                '200':
                  description: Says hello
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          data:
                            type: string
                            enum:
                              - 01
                              - 02
            """.trimIndent().openAPIToContract()


        @Test
        fun `new should be should be backward compatible with old`() {
            val results: Results = testBackwardCompatibility(specWithStringInResponse, specWithEnumInResponse)

            assertThat(results.success()).withFailMessage(results.report()).isTrue
        }

        @Test
        fun `old should be backward incompatible with new`() {
            val results: Results = testBackwardCompatibility(specWithEnumInResponse, specWithStringInResponse)

            println(results.report())

            assertThat(results.hasFailures()).isTrue
        }
    }

    @Test
    fun `backward compatibility error in request shows contextual error message`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            examples:
              200_OK:
                value:
                  data: 10
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            examples:
              200_OK:
                value:
                  data: abc
            schema:
              type: object
              properties:
                data:
                  type: string
              required:
                - data
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        val result: Results = testBackwardCompatibility(oldContract, newContract)

        assertThat(result.report()).isEqualToNormalizingWhitespace(
            """
        In scenario "hello world. Response: Says hello"
        API: POST /data -> 200
        
        ${
                toViolationReportString(
                    breadCrumb = "REQUEST.BODY.data",
                    details = "This is type string in the new specification, but type number in the old specification",
                    StandardRuleViolation.TYPE_MISMATCH
                )
            }
        """.trimIndent()
        )
    }

    @Test
    fun `backward compatibility error in response shows contextual error message`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            examples:
              200_OK:
                value:
                  data: 10
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            examples:
              200_OK:
                value:
                  data: 10
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        val result: Results = testBackwardCompatibility(oldContract, newContract)

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "hello world. Response: Says hello"
            API: POST /data -> 200
            
              >> RESPONSE.BODY
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is number in the new specification response but string in the old specification
            """
        )
    }

    @Test
    fun `backward compatibility errors are deduplicated`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - data2
              properties:
                data:
                  type: number
                data2:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val result: Results = testBackwardCompatibility(oldContract, newContract)

        val report: String = result.report()
        println(report)

        assertThat(report.indexOf("REQUEST.BODY.data2")).`as`("There should only be one instance of any error report that occurs in multiple contract-vs-contract requests")
            .isEqualTo(report.lastIndexOf("REQUEST.BODY.data2"))
    }

    @Test
    fun `distinctReport should merge errors for a scenario block when openapi request incompatibilities overlap to avoid duplicate logs`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 1.0.0
paths:
  /ping:
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                event:
                  type: string
      responses:
        '200':
          description: ok
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 1.0.0
paths:
  /ping:
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required:
                - timestamp
              properties:
                event:
                  type: number
                timestamp:
                  type: string
      responses:
        '200':
          description: ok
""".trimIndent(), ""
        ).toFeature()

        val result: Results = testBackwardCompatibility(oldContract, newContract)

        assertThat(result.distinctReport()).isEqualTo("""
            In scenario "POST /ping. Response: ok"
            API: POST /ping -> 200
            
              >> REQUEST.BODY.timestamp
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "timestamp" in the request but it is missing from the old specification
              
              >> REQUEST.BODY.event
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type number in the new specification, but type string in the old specification
        """.trimIndent())
    }

    @Test
    fun `distinctReport should not merge errors from different scenario blocks with the same path and method when openapi request incompatibilities overlap`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 1.0.0
paths:
  /ping:
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                event:
                  type: string
      responses:
        '200':
          description: ok
        '400':
          description: bad request
          content:
            application/json:
              schema:
                type: object
                properties:
                  message:
                    type: string
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 1.0.0
paths:
  /ping:
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required:
                - timestamp
              properties:
                event:
                  type: number
                timestamp:
                  type: string
      responses:
        '200':
          description: common
        '400':
          description: common
          content:
            application/json:
              schema:
                type: object
                required:
                  - code
                properties:
                  message:
                    type: number
                  code:
                    type: string
""".trimIndent(), ""
        ).toFeature()

        val result: Results = testBackwardCompatibility(oldContract, newContract)

        assertThat(result.distinctReport()).isEqualToIgnoringWhitespace("""
            In scenario "POST /ping. Response: common"
            API: POST /ping -> 200
            
              >> REQUEST.BODY.timestamp
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "timestamp" in the request but it is missing from the old specification
              
              >> REQUEST.BODY.event
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type number in the new specification, but type string in the old specification
            
            In scenario "POST /ping. Response: common"
            API: POST /ping -> 400
            
              >> REQUEST.BODY.timestamp
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "timestamp" in the request but it is missing from the old specification
              
              >> REQUEST.BODY.event
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type number in the new specification, but type string in the old specification
            
              >> RESPONSE.BODY.message
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is number in the new specification response but string in the old specification
        """.trimIndent())
    }

    @Test
    fun `backward compatibility errors in request and response are returned together`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required:
                - data2
              properties:
                data:
                  type: number
                data2:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        val result: Results = testBackwardCompatibility(oldContract, newContract)

        val reportText: String = result.report()
        println(reportText)

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "hello world. Response: Says hello"
            API: POST /data -> 200
            
              >> REQUEST.BODY.data2
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "data2" in the request but it is missing from the old specification
            
            In scenario "hello world. Response: Says hello"
            API: POST /data -> 200
            
              >> REQUEST.BODY
              
                  This is json object in the new specification, but an empty string or no body value in the old specification
            
            In scenario "hello world. Response: Says hello"
            API: POST /data -> 200
            
              >> RESPONSE.BODY
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is number in the new specification response but string in the old specification
            """
        )
    }

    @Test
    fun `contract with multiple responses statuses should be backward compatible with itself`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        '400':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val result: Results = testBackwardCompatibility(contract, contract)

        assertThat(result.success()).isTrue
    }

    @Nested
    inner class FluffyBackwardCompatibilityErrors {
        private val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        '400':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        private val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data2:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        '400':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val result: Results = testBackwardCompatibility(oldContract, newContract)

        private val reportText: String = result.report().also { println(it) }

        @Test
        fun `fluffy backward compatibility errors should be eliminated`() {
            assertThat(reportText).doesNotContain(">> STATUS")
        }

        @Test
        fun `backward compatibility errors with fluff removed should have at least one error`() {
            assertThat(result.failureCount).isNotZero
        }
    }

    @Test
    fun `errors for a missing URL should show up`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        '400':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data2:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: string
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        '400':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val result = testBackwardCompatibility(oldContract, newContract)
        val reportText = result.report()

        assertThat(reportText).contains("POST /data")
    }

    @Test
    fun `errors for a missing URL should show up even when there are deep matches for other URLs`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        '400':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
  /data2:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: string
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        '400':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val result = testBackwardCompatibility(oldContract, newContract)
        val reportText = result.report()

        println(reportText)

        assertThat(reportText).contains("POST /data")
        assertThat(reportText).contains("POST /data2")
    }

    @Test
    fun `backward compatibility errors with fluff removed should show deep mismatch errors`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        '400':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
  /data2:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
  /data3:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: string
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
        '400':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
  /data2:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val result = testBackwardCompatibility(oldContract, newContract)
        val reportText = result.report().also { println(it) }

        assertThat(reportText).contains("API: POST /data -> 200")
        assertThat(reportText).contains("API: POST /data -> 400")
        assertThat(reportText).doesNotContain("API: POST /data2 -> 200")
        assertThat(reportText).contains("API: POST /data3 -> 200")
    }

    @Test
    fun `backward compatibility check going from nullable to non-nullable`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - data
              properties:
                data:
                  oneOf:
                    - nullable: true
                    - type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - data
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val result = testBackwardCompatibility(oldContract, newContract)
        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "hello world. Response: Says hello"
            API: POST /data -> 200
            
              >> REQUEST.BODY.data
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type number in the new specification, but type null in the old specification
            """
        )
    }

    @Test
    fun `backward compatibility check going from oneOf number to number`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - data
              properties:
                data:
                  oneOf:
                    - type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - data
              properties:
                data:
                  type: number
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val result = testBackwardCompatibility(oldContract, newContract)
        assertThat(result.success()).isTrue()
    }

    @Test
    fun `backward compatibility check going null number string to number string`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - data
              properties:
                data:
                  oneOf:
                    - nullable: true
                    - type: number
                    - type: string
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - data
              properties:
                data:
                  oneOf:
                    - type: number
                    - type: string
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val result = testBackwardCompatibility(oldContract, newContract)
        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "hello world. Response: Says hello"
            API: POST /data -> 200
            
              >> REQUEST.BODY.data
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type number in the new specification, but type null in the old specification
              
              >> REQUEST.BODY.data
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type string in the new specification, but type null in the old specification
            """
        )
    }

    @Test
    fun `removing a key in the request should be backward compatible`() {
        val older = parseGherkinStringToFeature(
            """
            Feature: test
              Scenario: test
                When POST /data
                And request-body
                | id | (number) |
                | name | (string) |
                Then status 200
        """.trimIndent()
        )

        val newer = parseGherkinStringToFeature(
            """
            Feature: test
              Scenario: test
                When POST /data
                And request-body
                | id | (number) |
                Then status 200
        """.trimIndent()
        )

        val result = testBackwardCompatibility(older, newer)
        assertThat(result.success()).withFailMessage(result.report()).isTrue
    }

    @Test
    fun `backward compatibility check going from string to nothing in request`() {
        val oldContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            schema:
              type: string
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val newContract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              schema:
                type: string
""".trimIndent(), ""
        ).toFeature()

        val results = testBackwardCompatibility(oldContract, newContract)
        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "hello world. Response: Says hello"
            API: POST /data -> 200
            
              >> REQUEST.BODY
              
                  R1002: Value mismatch
                  Documentation: https://docs.specmatic.io/rules#r1002
                  Summary: The value does not match the expected value defined in the specification
              
                  This is no body in the new specification, but string in the old specification
            """
        )
    }

    @Test
    fun `should pass for scalar query parameter when type is changed from number to string`() {
        val olderContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    type: number
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val newerContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    type: string
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)

        assertThat(results.success()).isTrue
    }

    @Test
    fun `should fail for scalar query parameter when type is changed from string to number`() {
        val olderContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    type: string
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val newerContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    type: number
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)

        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "get products. Response: OK"
            API: GET /products -> 200
            
              >> REQUEST.PARAMETERS.QUERY.brand_ids
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type number in the new specification, but type string in the old specification
            """
        )
    }

    @Test
    fun `should pass for array query parameter when type is changed from number to string`() {
        val olderContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    items:
                      type: number
                    type: array
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val newerContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    items:
                      type: string
                    type: array
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)

        assertThat(results.success()).isTrue
    }

    @Test
    fun `should fail for array query parameter when type is changed from string to number`() {
        val olderContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    items:
                      type: string
                    type: array
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val newerContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    items:
                      type: number
                    type: array
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        println(results.report())
        assertThat(results.report())
            .containsIgnoringWhitespaces("""
            In scenario "get products. Response: OK"
            API: GET /products -> 200
            """.trimIndent())
            .containsIgnoringWhitespaces("""
             >> REQUEST.PARAMETERS.QUERY.brand_ids
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type number in the new specification, but type string in the old specification
            """.trimIndent())
    }

    @Test
    fun `should pass when scalar number query parameter is changed from to array number`() {
        val olderContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    type: number
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val newerContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    items:
                      type: number
                    type: array
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        assertThat(results.success()).isTrue
    }

    @Test
    fun `should fail when array number query parameter is changed to scalar number`() {
        val olderContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    items:
                      type: number
                    type: array
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val newerContract: Feature =
            """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              summary: get products
              description: Get multiple products filtered by Brand Ids
              parameters:
                - name: brand_ids
                  required: true
                  in: query
                  schema:
                    type: number
              responses:
                '200':
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: string
            """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        assertThat(results.report())
            .containsIgnoringWhitespaces("""
            In scenario "get products. Response: OK"
            API: GET /products -> 200
            """.trimIndent())
            .containsIgnoringWhitespaces("""
             >> REQUEST.PARAMETERS.QUERY.brand_ids
             R1001: Type mismatch
             Documentation: https://docs.specmatic.io/rules#r1001
             Summary: The value type does not match the expected type defined in the specification
            """.trimIndent())
    }

    @Test
    fun `should fail when response is changed from no-body to some body`() {
        val olderContract: Feature =
            """
                openapi: 3.0.0
                info:
                  title: Sample API
                  version: 0.1.9
                paths:
                  /products:
                    post:
                      summary: Create a product
                      description: Create a new product entry
                      requestBody:
                        description: Product to add
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                name:
                                  type: string
                                type:
                                  type: string
                      responses:
                        '201':
                          description: Created
                          # No content key, indicating no body
        """.trimIndent().openAPIToContract()

        val newerContract: Feature =
            """
                openapi: 3.0.0
                info:
                  title: Sample API
                  version: 0.2.0
                paths:
                  /products:
                    post:
                      summary: Create a product
                      description: Create a new product entry
                      requestBody:
                        description: Product to add
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                name:
                                  type: string
                                type:
                                  type: string
                      responses:
                        '201':
                          description: Created
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  id:
                                    type: string
                                  message:
                                    type: string
        """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "Create a product. Response: Created"
            API: POST /products -> 201
            
              >> RESPONSE.BODY
              
                  Expected no body, but found json object
            """
        )
    }

    @Test
    fun `should fail when response is changed from some body to no-body`() {
        val olderContract: Feature =
            """
                openapi: 3.0.0
                info:
                  title: Sample API
                  version: 0.1.9
                paths:
                  /products:
                    post:
                      summary: Create a product
                      description: Create a new product entry
                      requestBody:
                        description: Product to add
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                name:
                                  type: string
                                type:
                                  type: string
                      responses:
                        '201':
                          description: Created
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  id:
                                    type: string
                                  message:
                                    type: string
        """.trimIndent().openAPIToContract()

        val newerContract: Feature =
            """
                openapi: 3.0.0
                info:
                  title: Sample API
                  version: 0.2.0
                paths:
                  /products:
                    post:
                      summary: Create a product
                      description: Create a new product entry
                      requestBody:
                        description: Product to add
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                name:
                                  type: string
                                type:
                                  type: string
                      responses:
                        '201':
                          description: Created
                          # No content key, indicating no body
        """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        println(results.report())
        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "Create a product. Response: Created"
            API: POST /products -> 201
            
              >> RESPONSE.BODY
              
                  Expected json type, got no-body
            """
        )
    }

    @Test
    fun `should fail when request is changed from some body to no-body`() {
        val olderContract: Feature =
            """
                openapi: 3.0.0
                info:
                  title: Sample API
                  version: 0.1.9
                paths:
                  /products:
                    post:
                      summary: Create a product
                      description: Create a new product entry
                      requestBody:
                        description: Product to add
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                name:
                                  type: string
                                type:
                                  type: string
                                inventory:
                                  type: integer
                                  minimum: 1
                                  maximum: 101
                      responses:
                        '201':
                          description: Created
        """.trimIndent().openAPIToContract()

        val newerContract: Feature =
            """
                openapi: 3.0.0
                info:
                  title: Sample API
                  version: 0.2.0
                paths:
                  /products:
                    get:
                      summary: Create a product
                      description: Create a new product entry
                      # No requestBody, indicating no body
                      responses:
                        '201':
                          description: Created
        """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        println(results.report())
        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "Create a product. Response: Created"
            API: POST /products -> 201
            
                  This API exists in the old contract but not in the new contract
            """
        )
    }

    @Test
    fun `should show a message when testing each API`() {
        val oldLogger = logger
        try {

            val feature: Feature =
                """
                openapi: 3.0.0
                info:
                  title: Sample API
                  version: 0.1.9
                paths:
                  /products:
                    post:
                      summary: Create a product
                      description: Create a new product entry
                      responses:
                        '201':
                          description: Created
        """.trimIndent().openAPIToContract()

            logger = Verbose()
            val (stdout, _) = captureStandardOutput {
                testBackwardCompatibility(feature, feature)
            }

        assertThat(stdout).contains("[Compatibility Check] Executing 1 scenarios for POST /products against 1 operations")
        assertThat(stdout).contains("- POST /products -> 201")
        } finally {
            logger = oldLogger
        }
    }

    @Test
    fun `should not log grouped scenario count for each API with progress when count is less than thousand`() {
        val feature = OpenApiSpecification.fromYAML("""
        openapi: 3.0.0
        info:
          title: Sample API
          version: 1.0.0
        paths:
          /products:
            get:
              responses:
                '200':
                  description: OK
        """.trimIndent(), "sample.yaml").toFeature()

        val groupedFeature = feature.copy(scenarios = listOf(feature.scenarios.first(), feature.scenarios.first().copy()))
        val (stdout, _) = captureStandardOutput {
            testBackwardCompatibility(groupedFeature, groupedFeature)
        }

        assertThat(stdout).contains("[Compatibility Check] Executing 1 scenarios for GET /products against 2 operations")
        assertThat(stdout).contains("- GET /products -> 200")
        assertThat(stdout).doesNotContain("[Compatibility Check] Completed 1/2")
    }

    @Test
    fun `should log completion only every hundred scenarios when batch is large`() {
        val feature = OpenApiSpecification.fromYAML("""
        openapi: 3.0.0
        info:
          title: Sample API
          version: 1.0.0
        paths:
          /products:
            get:
              responses:
                '200':
                  description: OK
        """.trimIndent(), "sample.yaml").toFeature()

        val groupedFeature = feature.copy(scenarios = List(1001) { feature.scenarios.first().copy() })
        val (stdout, _) = captureStandardOutput {
            testBackwardCompatibility(groupedFeature, groupedFeature)
        }

        assertThat(stdout).contains("[Compatibility Check] Executing 1 scenarios for GET /products against 1001 operations")
        assertThat(stdout).doesNotContain("[Compatibility Check] Completed 100/1001")
        assertThat(stdout).doesNotContain("[Compatibility Check] Completed 1000/1001")
        assertThat(stdout).doesNotContain("[Compatibility Check] Completed 1001/1001")
        assertThat(stdout).doesNotContain("[Compatibility Check] Completed 1/1001")
        assertThat(stdout).doesNotContain("[Compatibility Check] Completed 99/1001")
        assertThat(stdout).contains("[Compatibility Check] Verdict: PASS")
    }

    @Test
    fun `should log grouped scenario count for each API with progress when total count is grater than thousand`() {
        val feature = OpenApiSpecification.fromYAML("""
        openapi: 3.0.0
        info:
          title: Sample API
          version: 1.0.0
        paths:
          /products:
            get:
              responses:
                '200':
                  description: OK
                '400':
                  description: Bad Request
        """.trimIndent(), "sample.yaml").toFeature()

        val groupedFeature = feature.copy(scenarios = List(1000) { feature.scenarios.first().copy() }.plus(feature.scenarios.last()))
        val (stdout, _) = captureStandardOutput {
            testBackwardCompatibility(groupedFeature, groupedFeature)
        }

        assertThat(stdout).contains("[Compatibility Check] Executing 1 scenarios for GET /products against 1001 operations")
        assertThat(stdout).contains("- GET /products -> 400")
        assertThat(stdout).contains("[Compatibility Check] Verdict: PASS")
    }

    @Test
    fun `should log pass verdict after completing all scenarios for an API`() {
        val feature = OpenApiSpecification.fromYAML("""
        openapi: 3.0.0
        info:
          title: Sample API
          version: 1.0.0
        paths:
          /products:
            get:
              responses:
                '200':
                  description: OK
        """.trimIndent(), "sample.yaml").toFeature()

        val groupedFeature = feature.copy(scenarios = listOf(feature.scenarios.first(), feature.scenarios.first().copy()))
        val (stdout, _) = captureStandardOutput {
            testBackwardCompatibility(groupedFeature, groupedFeature)
        }

        assertThat(stdout).contains("[Compatibility Check] Executing 1 scenarios for GET /products against 2 operations")
        assertThat(stdout).contains("- GET /products -> 200")
        assertThat(stdout).contains("[Compatibility Check] Verdict: PASS")
    }

    @Test
    fun `should log fail verdict after completing all scenarios for an API`() {
        val olderContract: Feature = """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            get:
              responses:
                '200':
                  description: OK
        """.trimIndent().openAPIToContract()

        val newerContract: Feature = """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.2.0
        paths:
          /products:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        name:
                          type: string
              responses:
                '200':
                  description: OK
        """.trimIndent().openAPIToContract()

        val (stdout, _) = captureStandardOutput {
            testBackwardCompatibility(olderContract, newerContract)
        }

        assertThat(stdout).contains("[Compatibility Check] Executing 1 scenarios for GET /products against 1 operations")
        assertThat(stdout).contains("- GET /products -> 200")
        assertThat(stdout).contains("[Compatibility Check] Verdict: FAIL")
    }

    @Test
    fun `should fail when request is changed from no-body to some body`() {
        val olderContract: Feature =
            """
                openapi: 3.0.0
                info:
                  title: Sample API
                  version: 0.1.9
                paths:
                  /products:
                    post:
                      summary: Create a product
                      description: Create a new product entry
                      responses:
                        '201':
                          description: Created
        """.trimIndent().openAPIToContract()

        val newerContract: Feature =
            """
                openapi: 3.0.0
                info:
                  title: Sample API
                  version: 0.2.0
                paths:
                  /products:
                    post:
                      summary: Create a product
                      description: Create a new product entry
                      requestBody:
                        description: Product to add
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                name:
                                  type: string
                                type:
                                  type: string
                                inventory:
                                  type: integer
                                  minimum: 1
                                  maximum: 101
                      responses:
                        '201':
                          description: Created
        """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        println(results.report())
        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "Create a product. Response: Created"
            API: POST /products -> 201
            
              >> REQUEST.BODY
              
                  This is json object in the new specification, but an empty string or no body value in the old specification
            """
        )
    }

    @Test
    fun `should fail when request is changed from some-body to no-body with appropriate error message`() {
        val olderContract: Feature = """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.2.0
        paths:
          /products:
            post:
              summary: Create a product
              description: Create a new product entry
              requestBody:
                description: Product to add
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        name:
                          type: string
              responses:
                '201':
                  description: Created
        """.trimIndent().openAPIToContract()

        val newerContract: Feature = """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.1.9
        paths:
          /products:
            post:
              summary: Create a product
              description: Create a new product entry
              responses:
                '201':
                  description: Created
        """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "Create a product. Response: Created"
            API: POST /products -> 201
            
              >> REQUEST.BODY
              
                  R1002: Value mismatch
                  Documentation: https://docs.specmatic.io/rules#r1002
                  Summary: The value does not match the expected value defined in the specification
              
                  This is no body in the new specification, but json object in the old specification
            """
        )
    }

    @Test
    fun `should fail when request content-type has been changes from one to another`() {
        val olderContract: Feature = """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.2.0
        paths:
          /products:
            post:
              summary: Create a product
              description: Create a new product entry
              requestBody:
                description: Product to add
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        name:
                          type: string
              responses:
                '201':
                  description: Created
        """.trimIndent().openAPIToContract()

        val newerContract: Feature = """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.2.0
        paths:
          /products:
            post:
              summary: Create a product
              description: Create a new product entry
              requestBody:
                description: Product to add
                required: true
                content:
                  application/custom+json:
                    schema:
                      type: object
                      properties:
                        name:
                          type: string
              responses:
                '201':
                  description: Created
        """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        assertBackwardCompatibilityFailure(
            results = results,
            expectedReport = """
            In scenario "Create a product. Response: Created"
            API: POST /products -> 201
            
              >> REQUEST.PARAMETERS.HEADER.Content-Type
              
                  R1002: Value mismatch
                  Documentation: https://docs.specmatic.io/rules#r1002
                  Summary: The value does not match the expected value defined in the specification
              
                  This is application/custom+json in the new specification, but application/json in the old specification
            """
        )
    }

    @Test
    fun `should fail when request content-type has been changes from one to another but another path with same method and content-type exists`() {
        val olderContract: Feature = """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.2.0
        paths:
          /products:
            post:
              summary: Create a product
              description: Create a new product entry
              requestBody:
                description: Product to add
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        name:
                          type: string
              responses:
                '201':
                  description: Created
          /orders:
            post:
              summary: Create an order
              description: Create a new order entry
              requestBody:
                description: Order to add
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        name:
                          type: string
              responses:
                '201':
                  description: Created
        """.trimIndent().openAPIToContract()

        val newerContract: Feature = """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.2.0
        paths:
          /products:
            post:
              summary: Create a product
              description: Create a new product entry
              requestBody:
                description: Product to add
                required: true
                content:
                  application/custom+json:
                    schema:
                      type: object
                      properties:
                        name:
                          type: string
              responses:
                '201':
                  description: Created
          /orders:
            post:
              summary: Create an order
              description: Create a new order entry
              requestBody:
                description: Order to add
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        name:
                          type: string
              responses:
                '201':
                  description: Created
        """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        assertBackwardCompatibilityFailure(
            results = results,
            expectedReport = """
            In scenario "Create a product. Response: Created"
            API: POST /products -> 201
            
              >> REQUEST.PARAMETERS.HEADER.Content-Type
              
                  R1002: Value mismatch
                  Documentation: https://docs.specmatic.io/rules#r1002
                  Summary: The value does not match the expected value defined in the specification
              
                  This is application/custom+json in the new specification, but application/json in the old specification
            """
        )
    }

    @Test
    fun `should fail when request content-type has been changes from one to another but multiple content-type exists making the change ambiguous`() {
        val olderContract: Feature = """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.2.0
        paths:
          /products:
            post:
              summary: Create a product
              description: Create a new product entry
              requestBody:
                description: Product to add
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        name:
                          type: string
              responses:
                '201':
                  description: Created
        """.trimIndent().openAPIToContract()

        val newerContract: Feature = """
        openapi: 3.0.0
        info:
          title: Sample API
          version: 0.2.0
        paths:
          /products:
            post:
              summary: Create a product
              description: Create a new product entry
              requestBody:
                description: Product to add
                required: true
                content:
                  application/custom+json:
                    schema:
                      type: object
                      properties:
                        name:
                          type: string
                  application/custom2+json:
                    schema:
                      type: object
                      properties:
                        name:
                          type: string
              responses:
                '201':
                  description: Created
        """.trimIndent().openAPIToContract()

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        assertBackwardCompatibilityFailure(
            results = results,
            expectedReport = """
            In scenario "Create a product. Response: Created"
            API: POST /products -> 201
            
                  This API exists in the old contract but not in the new contract
            """
        )
    }

    @Test
    fun `example-only changes do not break backward compatibility for anyOf nullable response schema`() {
        val oldSpec = """
openapi: 3.1.0
info:
  title: Repro API
  version: '1.0'
paths:
  /products/validate:
    post:
      responses:
        "200":
          description: Validation successful
          content:
            application/json:
              schema:
                type: object
                properties:
                  data:
                    anyOf:
                      - type: array
                        items:
                          type: string
                      - type: "null"
              examples:
                EXAMPLE_1:
                  value:
                    data: ["item1", "item2"]
""".trimIndent()

        val newSpec = """
openapi: 3.1.0
info:
  title: Repro API
  version: '1.0'
paths:
  /products/validate:
    post:
      responses:
        "200":
          description: Validation successful - updated example text
          content:
            application/json:
              schema:
                type: object
                properties:
                  data:
                    anyOf:
                      - type: array
                        items:
                          type: string
                      - type: "null"
              examples:
                EXAMPLE_1:
                  value:
                    data: ["item1", "item2", "item3"]
""".trimIndent()

        val olderContract = OpenApiSpecification.fromYAML(oldSpec, "").toFeature()
        val newerContract = OpenApiSpecification.fromYAML(newSpec, "").toFeature()

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        assertThat(result.success()).isTrue
    }

    @Test
    fun `example-only changes do not break backward compatibility for oneOf array item schema`() {
        val oldSpec = """
openapi: 3.1.0
info:
  title: Repro API
  version: '1.0'
paths:
  /products/validate:
    post:
      responses:
        "200":
          description: Validation successful
          content:
            application/json:
              schema:
                type: object
                properties:
                  data:
                    type: array
                    items:
                      oneOf:
                        - type: string
                        - type: boolean
              examples:
                EXAMPLE_1:
                  value:
                    data: ["item1", "item2"]
""".trimIndent()

        val newSpec = """
openapi: 3.1.0
info:
  title: Repro API
  version: '1.0'
paths:
  /products/validate:
    post:
      responses:
        "200":
          description: Validation successful - updated example
          content:
            application/json:
              schema:
                type: object
                properties:
                  data:
                    type: array
                    items:
                      oneOf:
                        - type: string
                        - type: boolean
              examples:
                EXAMPLE_1:
                  value:
                    data: ["item1", true]
""".trimIndent()

        val olderContract = OpenApiSpecification.fromYAML(oldSpec, "").toFeature()
        val newerContract = OpenApiSpecification.fromYAML(newSpec, "").toFeature()

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        assertThat(result.success()).isTrue
    }

    @Test
    fun `request body array string remains backward compatible when unchanged and examples are absent`() {
        val oldSpec = """
openapi: 3.1.0
info:
  title: Repro API
  version: '1.0'
paths:
  /products/validate:
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required:
                - data
              properties:
                data:
                  type: array
                  items:
                    type: string
      responses:
        "200":
          description: Validation successful
""".trimIndent()

        val newSpec = """
openapi: 3.1.0
info:
  title: Repro API
  version: '1.0'
paths:
  /products/validate:
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required:
                - data
              properties:
                data:
                  type: array
                  items:
                    type: string
      responses:
        "200":
          description: Validation successful
""".trimIndent()

        val olderContract = OpenApiSpecification.fromYAML(oldSpec, "").toFeature()
        val newerContract = OpenApiSpecification.fromYAML(newSpec, "").toFeature()

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        assertThat(result.success()).withFailMessage(result.report()).isTrue
    }

    @Test
    fun `request body array string to boolean is backward incompatible when examples are absent`() {
        val oldSpec = """
openapi: 3.1.0
info:
  title: Repro API
  version: '1.0'
paths:
  /products/validate:
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required:
                - data
              properties:
                data:
                  type: array
                  items:
                    type: string
      responses:
        "200":
          description: Validation successful
""".trimIndent()

        val newSpec = """
openapi: 3.1.0
info:
  title: Repro API
  version: '1.0'
paths:
  /products/validate:
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required:
                - data
              properties:
                data:
                  type: array
                  items:
                    type: boolean
      responses:
        "200":
          description: Validation successful
""".trimIndent()

        val olderContract = OpenApiSpecification.fromYAML(oldSpec, "").toFeature()
        val newerContract = OpenApiSpecification.fromYAML(newSpec, "").toFeature()

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        assertThat(result.success()).withFailMessage(result.report()).isFalse
        assertThat(result.report())
            .containsIgnoringWhitespaces("""
            In scenario "POST /products/validate. Response: Validation successful"
            API: POST /products/validate -> 200
            """.trimIndent())
            .containsIgnoringWhitespaces("""
             >> REQUEST.BODY.data[0]
  
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type boolean in the new specification, but type string in the old specification
            """.trimIndent())
    }

    @Test
    fun `backward breaking multi request and response content-type scenario`() {
        val specificationV1 = OpenApiSpecification.fromFile("src/test/resources/openapi/multi_req_res_ct/openapi_v1.yaml")
        val specificationV2 = OpenApiSpecification.fromFile("src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml")
        val bccChecker = OpenApiBackwardCompatibilityChecker(specificationV1.toFeature(), specificationV2.toFeature())
        val result = bccChecker.run().toBackwardCompatibilityResults()

        assertThat(result.report()).isEqualToNormalizingNewlines("""
        In scenario "Missing endpoint. Response: A simple string response"
        API: GET /missing -> 200
        
              This API exists in the old contract but not in the new contract
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        """.trimIndent())
    }

    @Test
    fun `backward breaking single request and response content-type scenario content-type overridden`() {
        val specificationV1 = OpenApiSpecification.fromFile("src/test/resources/openapi/single_req_res_ct_overriden/openapi_v1.yaml")
        val specificationV2 = OpenApiSpecification.fromFile("src/test/resources/openapi/single_req_res_ct_overriden/openapi_v2.yaml")
        val bccChecker = OpenApiBackwardCompatibilityChecker(specificationV1.toFeature(), specificationV2.toFeature())
        val result = bccChecker.run().toBackwardCompatibilityResults()

        assertThat(result.report()).isEqualToNormalizingNewlines("""
        In scenario "Missing endpoint. Response: A simple string response"
        API: GET /missing -> 200
        
              This API exists in the old contract but not in the new contract
      
        In scenario "Simple POST endpoint. Response: A simple string response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple POST endpoint. Response: A simple string response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple POST endpoint. Response: A simple string response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple POST endpoint. Response: A simple string response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        """.trimIndent())
    }

    @Test
    @Disabled // TODO: fix matching logic for override in HttpHeadersPattern when mediaType matches override
    fun `backward breaking multi request and response content-type scenario content-type overridden`() {
        val specificationV1 = OpenApiSpecification.fromFile("src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v1.yaml")
        val specificationV2 = OpenApiSpecification.fromFile("src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml")
        val bccChecker = OpenApiBackwardCompatibilityChecker(specificationV1.toFeature(), specificationV2.toFeature())
        val result = bccChecker.run().toBackwardCompatibilityResults()

        assertThat(result.report()).isEqualToNormalizingNewlines("""
        In scenario "Missing endpoint. Response: A simple string response"
        API: GET /missing -> 200
        
              This API exists in the old contract but not in the new contract
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field2
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        """.trimIndent())
    }

    @Test
    fun `should generate backward compatibility report with mixed compatible and incompatible operations`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText("""
            version: 2
            reportDirPath: ${tempDir.canonicalPath}/reports
            """.trimIndent())
        }

        val olderSpec = tempDir.resolve("orders-v1.yaml").apply {
            writeText("""
            openapi: 3.0.0
            info:
              title: Orders API
              version: 1.0.0
            paths:
              /orders:
                get:
                  responses:
                    '200':
                      description: List orders
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              type: object
                              required: [id]
                              properties:
                                id:
                                  type: string
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [id]
                          properties:
                            id:
                              type: string
                            note:
                              type: string
                  responses:
                    '201':
                      description: Created
            """.trimIndent())
        }

        val newerSpec = tempDir.resolve("orders-v2.yaml").apply {
            writeText("""
            openapi: 3.0.0
            info:
              title: Orders API
              version: 2.0.0
            paths:
              /orders:
                get:
                  responses:
                    '200':
                      description: List orders
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              type: object
                              required: [id]
                              properties:
                                id:
                                  type: string
                                note:
                                  type: string
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [id, note]
                          properties:
                            id:
                              type: string
                            note:
                              type: string
                  responses:
                    '201':
                      description: Created
            """.trimIndent())
        }

        val olderContract = OpenApiSpecification.fromFile(olderSpec.canonicalPath).toFeature()
        val newerContract = OpenApiSpecification.fromFile(newerSpec.canonicalPath).toFeature()
        val reportDir = tempDir.resolve("reports/backward_compatibility").canonicalFile

        using(CONFIG_FILE_PATH to configFile.canonicalPath, "SPECMATIC_BCC_REPORT" to "true") {
            val (result, report) = testBackwardCompatibilityWithReport(olderContract, newerContract)
            assertThat(report).withFailMessage("Expected CTRF Report to be generated").isNotNull
            assertThat(result.success()).isFalse

            val htmlReport = reportDir.resolve("html/index.html").canonicalFile
            assertThat(htmlReport)
                .withFailMessage { "Expected HTML Report ${htmlReport.canonicalPath} to exist" }
                .exists()

            val reportJson = OBJECT_MAPPER.valueToTree<JsonNode>(report)
            val summary = reportJson.path("results").path("summary")
            val executionDetails = summary.path("extra").path("executionDetails")
            val operations = executionDetails.first().path("operations")

            assertThat(htmlReport.readText()).contains("BackwardCompatibility")
            assertThat(executionDetails.toList()).allSatisfy {
                assertThat(it.path("specification").asText().replace('\\', '/'))
                    .isEqualTo(newerSpec.canonicalPath.replace('\\', '/'))
            }

            assertThat(operations.size()).isEqualTo(2)
            assertThat(summary.path("tests").asInt()).isEqualTo(5)
            assertThat(summary.path("failed").asInt()).isEqualTo(1)
            assertThat(reportJson.path("results").path("tests").size()).isEqualTo(5)
            assertThat(reportJson.path("results").path("extra").path("reportType").asText()).isEqualTo("BackwardCompatibility")
            assertThat(reportJson.path("results").path("extra").path("specmaticConfigPath").asText().replace('\\', '/'))
                .isEqualTo(configFile.canonicalPath.replace('\\', '/'))

            val postOperation = operations.first { it.path("method").asText() == "POST" }
            assertThat(postOperation.path("path").asText()).isEqualTo("/orders")
            assertThat(postOperation.path("method").asText()).isEqualTo("POST")
            assertThat(postOperation.path("contentType").asText()).isEqualTo("application/json")
            assertThat(postOperation.path("responseCode").asInt()).isEqualTo(201)
            assertThat(postOperation.path("qualifiers").map { it.asText() }).containsExactly("changed")
            assertThat(postOperation.path("testIds").isArray).isTrue()
            assertThat(postOperation.path("testIds").size()).isGreaterThan(0)
            assertThat(postOperation.path("status").asText()).isEqualTo("Incompatible")

            val getOperation = operations.first { it.path("method").asText() == "GET" }
            assertThat(getOperation.path("path").asText()).isEqualTo("/orders")
            assertThat(getOperation.path("method").asText()).isEqualTo("GET")
            assertThat(getOperation.path("responseCode").asInt()).isEqualTo(200)
            assertThat(getOperation.path("responseContentType").asText()).isEqualTo("application/json")
            assertThat(getOperation.path("qualifiers").map { it.asText() }).containsExactly("changed")
            assertThat(getOperation.path("testIds").isArray).isTrue()
            assertThat(getOperation.path("testIds").size()).isGreaterThan(0)
            assertThat(getOperation.path("status").asText()).isEqualTo("Compatible")
        }
    }

    @Nested
    inner class BccIntegrationFromFixtures {
        @Test
        fun `report reflects per-5-tuple change status and compatibility result across refs, components, and recursion`(@TempDir tempDir: File) {
            val oldSpec = readFixture("orders_old.yaml")
            val newSpec = readFixture("orders_new.yaml")

            val operations = runBccAndGetOperations(tempDir, oldSpec, newSpec)
            val json = "application/json"

            // NEW-SIDE N1: Order gains optional `notes` -> CHANGED + Compatible on every Order-using row
            operations.assertRow(OperationKey("GET", "/orders", null, 200, json), changeStatus = "CHANGED", result = "Compatible")
            operations.assertRow(OperationKey("POST", "/orders", json, 201, json), changeStatus = "CHANGED", result = "Compatible")
            operations.assertRow(OperationKey("GET", "/orders/{id}", null, 200, json), changeStatus = "CHANGED", result = "Compatible")

            // NEW-SIDE N2: ErrorBrief.code string -> integer (reachable ONLY from GET /orders 400)
            operations.assertRow(OperationKey("GET", "/orders", null, 400, json), changeStatus = "CHANGED", result = "Incompatible")

            // NEW-SIDE N3: Self-recursive Category gains optional `description`
            operations.assertRow(OperationKey("GET", "/categories", null, 200, json), changeStatus = "CHANGED", result = "Compatible")

            // OLD-SIDE O1 (non-breaking): ErrorDetailed loses optional `traceId`
            operations.assertRow(OperationKey("GET", "/orders/{id}", null, 404, json), changeStatus = "CHANGED", result = "Compatible")

            // OLD-SIDE O2 (breaking): DELETE /orders/{id} removed from new (no request/response body)
            operations.assertRow(OperationKey("DELETE", "/orders/{id}", null, 204, null), changeStatus = "CHANGED", result = "Incompatible")

            // OLD-SIDE O3 (breaking): GET /orders 500 response removed from new
            operations.assertRow(OperationKey("GET", "/orders", null, 500, json), changeStatus = "CHANGED", result = "Incompatible")

            // UNCHANGED 5-tuple sharing a (path, method) with a CHANGED row -
            // POST /orders 400 uses OrderInput (untouched) for request and ValidationError
            // (untouched) for response, while POST /orders 201 uses Order (changed) for response.
            operations.assertRow(OperationKey("POST", "/orders", json, 400, json), changeStatus = "UNCHANGED", result = "Compatible")

            // UNCHANGED standalone operation - GET /health is identical in both specs
            operations.assertRow(OperationKey("GET", "/health", null, 200, json), changeStatus = "UNCHANGED", result = "Compatible")

            // Sanity: the report has exactly the rows we asserted above and no more
            assertThat(operations.toList())
                .describedAs("expected 10 operation rows in the report")
                .hasSize(10)
        }

        private fun JsonNode.assertRow(key: OperationKey, changeStatus: String, result: String) {
            val row = firstOrNull { node ->
                node.path("method").asText() == key.method &&
                node.path("path").asText() == key.path &&
                node.path("responseCode").asInt() == key.responseCode &&
                node.path("contentType").asText().ifEmpty { null } == key.requestContentType &&
                node.path("responseContentType").asText().ifEmpty { null } == key.responseContentType
            } ?: throw AssertionError("No operation row matching $key. Rows present:\n" +
                joinToString("\n") { node ->
                    "  - method=${node.path("method").asText()} path=${node.path("path").asText()} " +
                        "reqCT=${node.path("contentType").asText().ifEmpty { "null" }} " +
                        "status=${node.path("responseCode").asInt()} " +
                        "resCT=${node.path("responseContentType").asText().ifEmpty { "null" }}"
                })
            assertThat(row.path("status").asText())
                .describedAs("status for $key")
                .isEqualTo(result)
            val qualifiers = row.path("qualifiers").map { it.asText() }
            if (changeStatus == "CHANGED") {
                assertThat(qualifiers).describedAs("qualifiers for $key").contains("changed")
            } else {
                assertThat(qualifiers).describedAs("qualifiers for $key").doesNotContain("changed")
            }
        }

        private fun readFixture(name: String): String =
            javaClass.getResource("/openapi/bcc_integration/$name")!!.readText()

        private fun runBccAndGetOperations(tempDir: File, oldSpec: String, newSpec: String): JsonNode {
            val configFile = tempDir.resolve("specmatic.yaml").apply {
                writeText("""
                version: 2
                reportDirPath: ${tempDir.canonicalPath}/reports
                """.trimIndent())
            }
            val oldFeature = OpenApiSpecification.fromYAML(oldSpec, "old.yaml").toFeature()
            val newFeature = OpenApiSpecification.fromYAML(newSpec, "new.yaml").toFeature()

            return using(CONFIG_FILE_PATH to configFile.canonicalPath, "SPECMATIC_BCC_REPORT" to "true") {
                val (_, report) = testBackwardCompatibilityWithReport(oldFeature, newFeature)
                val json = OBJECT_MAPPER.valueToTree<JsonNode>(report)
                json.path("results").path("summary").path("extra").path("executionDetails").first().path("operations")
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class OperationChangeStatus {
        private val baseSpec = """
            openapi: 3.0.0
            info:
              title: Orders API
              version: 1.0.0
            paths:
              /orders:
                get:
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              type: object
                              required: [id]
                              properties:
                                id:
                                  type: string
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [id]
                          properties:
                            id:
                              type: string
                  responses:
                    '201':
                      description: created
              /orders/{id}:
                get:
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            required: [id]
                            properties:
                              id:
                                type: string
        """.trimIndent()

        @ParameterizedTest(name = "{0}")
        @MethodSource("changeStatusCases")
        fun `change status detection`(case: ChangeStatusCase, @TempDir tempDir: File) {
            val oldSpec = case.oldPatch?.let { baseSpec.applyJsonPatch(it) } ?: baseSpec
            val newSpec = case.newPatch?.let { baseSpec.applyJsonPatch(it) } ?: baseSpec

            val operations = runBccAndGetOperations(tempDir, oldSpec, newSpec)
            val op = operations.first {
                it.path("path").asText() == case.path &&
                it.path("method").asText() == case.method &&
                (case.responseCode == null || it.path("responseCode").asInt() == case.responseCode) &&
                (case.requestContentType == null || it.path("contentType").asText() == case.requestContentType) &&
                (case.responseContentType == null || it.path("responseContentType").asText() == case.responseContentType)
            }

            val qualifiers = op.path("qualifiers").map { it.asText() }
            val changeStatus = if (qualifiers.contains("changed")) "CHANGED" else "UNCHANGED"
            assertThat(changeStatus).isEqualTo(case.expected.name)
        }

        @Test
        fun `when only one response status changes, other response statuses stay UNCHANGED`(@TempDir tempDir: File) {
            val twoStatusSpec = """
                openapi: 3.0.0
                info: { title: Orders API, version: 1.0.0 }
                paths:
                  /orders:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [id]
                                properties: { id: { type: string } }
                        '400':
                          description: bad
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [message]
                                properties: { message: { type: string } }
            """.trimIndent()

            val newSpec = twoStatusSpec.applyJsonPatch("""
                - op: replace
                  path: /paths/~1orders/get/responses/400/content/application~1json/schema/properties/message/type
                  value: integer
            """.trimIndent())

            val operations = runBccAndGetOperations(tempDir, twoStatusSpec, newSpec)

            assertThat(operations.changeStatusFor(method = "GET", responseCode = 200)).isEqualTo("UNCHANGED")
            assertThat(operations.changeStatusFor(method = "GET", responseCode = 400)).isEqualTo("CHANGED")
        }

        @Test
        fun `when only one response content type changes, other response content types stay UNCHANGED`(@TempDir tempDir: File) {
            val twoContentTypeSpec = """
                openapi: 3.0.0
                info: { title: Orders API, version: 1.0.0 }
                paths:
                  /orders:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [id]
                                properties: { id: { type: string } }
                            application/vnd.orders.v2+json:
                              schema:
                                type: object
                                required: [id]
                                properties: { id: { type: string } }
            """.trimIndent()

            val newSpec = twoContentTypeSpec.applyJsonPatch("""
                - op: replace
                  path: /paths/~1orders/get/responses/200/content/application~1vnd.orders.v2+json/schema/properties/id/type
                  value: integer
            """.trimIndent())

            val operations = runBccAndGetOperations(tempDir, twoContentTypeSpec, newSpec)

            assertThat(operations.changeStatusFor(method = "GET", responseCode = 200, responseContentType = "application/json"))
                .isEqualTo("UNCHANGED")
            assertThat(operations.changeStatusFor(method = "GET", responseCode = 200, responseContentType = "application/vnd.orders.v2+json"))
                .isEqualTo("CHANGED")
        }

        @Test
        fun `when only one request content type changes, other request content types stay UNCHANGED`(@TempDir tempDir: File) {
            val twoRequestContentTypeSpec = """
                openapi: 3.0.0
                info: { title: Orders API, version: 1.0.0 }
                paths:
                  /orders:
                    post:
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              required: [id]
                              properties: { id: { type: string } }
                          application/vnd.orders.v2+json:
                            schema:
                              type: object
                              required: [id]
                              properties: { id: { type: string } }
                      responses:
                        '201': { description: created }
            """.trimIndent()

            val newSpec = twoRequestContentTypeSpec.applyJsonPatch("""
                - op: replace
                  path: /paths/~1orders/post/requestBody/content/application~1vnd.orders.v2+json/schema/properties/id/type
                  value: integer
            """.trimIndent())

            val operations = runBccAndGetOperations(tempDir, twoRequestContentTypeSpec, newSpec)

            assertThat(operations.changeStatusFor(method = "POST", contentType = "application/json")).isEqualTo("UNCHANGED")
            assertThat(operations.changeStatusFor(method = "POST", contentType = "application/vnd.orders.v2+json")).isEqualTo("CHANGED")
        }

        private fun JsonNode.changeStatusFor(
            method: String,
            path: String = "/orders",
            responseCode: Int? = null,
            contentType: String? = null,
            responseContentType: String? = null,
        ): String {
            val operation = first {
                it.path("path").asText() == path &&
                it.path("method").asText() == method &&
                (responseCode == null || it.path("responseCode").asInt() == responseCode) &&
                (contentType == null || it.path("contentType").asText() == contentType) &&
                (responseContentType == null || it.path("responseContentType").asText() == responseContentType)
            }
            val qualifiers = operation.path("qualifiers").map { it.asText() }
            return if (qualifiers.contains("changed")) "CHANGED" else "UNCHANGED"
        }

        // TODO(product): decide whether new-only operations should surface as CHANGED in the BCC report.
        // Today OpenApiBackwardCompatibilityChecker iterates over old scenarios only, so an operation
        // present in the new spec but absent in the old spec produces no record and is not reported.
        // If we want it to appear (likely as CHANGED + Incompatible "added in new"), the checker must
        // iterate over the union of (path, method) pairs from old and new.
        @Test
        fun `new-only operations are absent from the report today`(@TempDir tempDir: File) {
            val newSpec = baseSpec.applyJsonPatch("""
                - op: add
                  path: /paths/~1health
                  value:
                    get:
                      responses:
                        '200':
                          description: ok
            """.trimIndent())

            val operations = runBccAndGetOperations(tempDir, baseSpec, newSpec).toList()

            val operationKeys = operations.map { "${it.path("method").asText()} ${it.path("path").asText()}" }
            assertThat(operationKeys).doesNotContain("GET /health")
        }

        private fun runBccAndGetOperations(tempDir: File, oldSpec: String, newSpec: String): JsonNode {
            val configFile = tempDir.resolve("specmatic.yaml").apply {
                writeText("""
                version: 2
                reportDirPath: ${tempDir.canonicalPath}/reports
                """.trimIndent())
            }
            val oldFeature = OpenApiSpecification.fromYAML(oldSpec, "old.yaml").toFeature()
            val newFeature = OpenApiSpecification.fromYAML(newSpec, "new.yaml").toFeature()

            return using(CONFIG_FILE_PATH to configFile.canonicalPath, "SPECMATIC_BCC_REPORT" to "true") {
                val (_, report) = testBackwardCompatibilityWithReport(oldFeature, newFeature)
                val json = OBJECT_MAPPER.valueToTree<JsonNode>(report)
                json.path("results").path("summary").path("extra").path("executionDetails").first().path("operations")
            }
        }

        private fun String.applyJsonPatch(patch: String): String {
            val specNode = yamlMapper.readTree(this)
            val patchNode = yamlMapper.readTree(patch.trimIndent())
            return yamlMapper.writeValueAsString(JsonPatch.apply(patchNode, specNode)).trim()
        }

        private val yamlMapper = ObjectMapper(YAMLFactory())

        private fun changeStatusCases(): Stream<ChangeStatusCase> = Stream.of(
            ChangeStatusCase(
                name = "1. identical specs are UNCHANGED",
                path = "/orders", method = "GET",
                expected = ChangeStatus.UNCHANGED,
            ),
            ChangeStatusCase(
                name = "2. add optional request body field",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/properties/note
                      value:
                        type: string
                """,
            ),
            ChangeStatusCase(
                name = "3. add required request body field",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/properties/note
                      value:
                        type: string
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/required/-
                      value: note
                """,
            ),
            ChangeStatusCase(
                name = "4. remove optional request body field",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                oldPatch = """
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/properties/note
                      value:
                        type: string
                """,
            ),
            ChangeStatusCase(
                name = "5. remove required request body field",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                oldPatch = """
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/properties/note
                      value:
                        type: string
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/required/-
                      value: note
                """,
            ),
            ChangeStatusCase(
                name = "6. required request field becomes optional",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                oldPatch = """
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/properties/note
                      value:
                        type: string
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/required/-
                      value: note
                """,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/properties/note
                      value:
                        type: string
                """,
            ),
            ChangeStatusCase(
                name = "7. optional request field becomes required",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                oldPatch = """
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/properties/note
                      value:
                        type: string
                """,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/properties/note
                      value:
                        type: string
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/required/-
                      value: note
                """,
            ),
            ChangeStatusCase(
                name = "8. change request field type",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema/properties/id/type
                      value: integer
                """,
            ),
            ChangeStatusCase(
                name = "9. add optional query parameter",
                path = "/orders", method = "GET",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/get/parameters
                      value:
                        - name: limit
                          in: query
                          required: false
                          schema:
                            type: integer
                """,
            ),
            ChangeStatusCase(
                name = "10. add required query parameter",
                path = "/orders", method = "GET",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/get/parameters
                      value:
                        - name: limit
                          in: query
                          required: true
                          schema:
                            type: integer
                """,
            ),
            ChangeStatusCase(
                name = "11. add optional request header",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/post/parameters
                      value:
                        - name: X-Trace-Id
                          in: header
                          required: false
                          schema:
                            type: string
                """,
            ),
            ChangeStatusCase(
                name = "12. add required request header",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/post/parameters
                      value:
                        - name: X-Trace-Id
                          in: header
                          required: true
                          schema:
                            type: string
                """,
            ),
            ChangeStatusCase(
                name = "13. add optional response field",
                path = "/orders", method = "GET",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/get/responses/200/content/application~1json/schema/items/properties/note
                      value:
                        type: string
                """,
            ),
            ChangeStatusCase(
                name = "14. remove response field that old promised",
                path = "/orders", method = "GET",
                expected = ChangeStatus.CHANGED,
                oldPatch = """
                    - op: add
                      path: /paths/~1orders/get/responses/200/content/application~1json/schema/items/properties/note
                      value:
                        type: string
                """,
            ),
            ChangeStatusCase(
                name = "15. change response field type",
                path = "/orders", method = "GET",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: replace
                      path: /paths/~1orders/get/responses/200/content/application~1json/schema/items/properties/id/type
                      value: integer
                """,
            ),
            // The newly-added 400 row does not appear in today's report (checker iterates old scenarios
            // only - see `new-only operations are absent from the report today`). The existing 200 row
            // is identical between old and new, so per-5-tuple it stays UNCHANGED.
            ChangeStatusCase(
                name = "16. add a new response status code - existing 200 row stays UNCHANGED",
                path = "/orders", method = "GET", responseCode = 200,
                expected = ChangeStatus.UNCHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/get/responses/400
                      value:
                        description: bad request
                """,
            ),
            ChangeStatusCase(
                name = "17. remove a response status code that existed in old",
                path = "/orders", method = "GET", responseCode = 400,
                expected = ChangeStatus.CHANGED,
                oldPatch = """
                    - op: add
                      path: /paths/~1orders/get/responses/400
                      value:
                        description: bad request
                """,
            ),
            // Newly-added text/plain request row does not appear in today's report; the existing
            // application/json row is unchanged between old and new.
            ChangeStatusCase(
                name = "18. add a new request content type - existing json row stays UNCHANGED",
                path = "/orders", method = "POST", requestContentType = "application/json",
                expected = ChangeStatus.UNCHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/text~1plain
                      value:
                        schema:
                          type: string
                """,
            ),
            // The removed text/plain row is collapsed into the surviving json row by the checker's
            // content-type fallback in `findExactOrSingle`, so it never gets its own report row.
            // The surviving json row is semantically unchanged.
            ChangeStatusCase(
                name = "19. remove a request content type from old - surviving json row stays UNCHANGED",
                path = "/orders", method = "POST", requestContentType = "application/json",
                expected = ChangeStatus.UNCHANGED,
                oldPatch = """
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/text~1plain
                      value:
                        schema:
                          type: string
                """,
            ),
            // Newly-added text/plain response row does not appear in today's report; the existing
            // application/json row is unchanged between old and new.
            ChangeStatusCase(
                name = "20. add a new response content type - existing json row stays UNCHANGED",
                path = "/orders", method = "GET", responseCode = 200, responseContentType = "application/json",
                expected = ChangeStatus.UNCHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/get/responses/200/content/text~1plain
                      value:
                        schema:
                          type: string
                """,
            ),
            // Same as #19: the removed text/plain row is collapsed into the surviving json row.
            ChangeStatusCase(
                name = "21. remove a response content type from old - surviving json row stays UNCHANGED",
                path = "/orders", method = "GET", responseCode = 200, responseContentType = "application/json",
                expected = ChangeStatus.UNCHANGED,
                oldPatch = """
                    - op: add
                      path: /paths/~1orders/get/responses/200/content/text~1plain
                      value:
                        schema:
                          type: string
                """,
            ),
            ChangeStatusCase(
                name = "22. change path parameter type",
                path = "/orders/{id}", method = "GET",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: replace
                      path: /paths/~1orders~1{id}/get/parameters/0/schema/type
                      value: integer
                """,
            ),
            ChangeStatusCase(
                name = "23. add a security scheme requirement",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: add
                      path: /components
                      value:
                        securitySchemes:
                          apiKey:
                            type: apiKey
                            in: header
                            name: X-API-Key
                    - op: add
                      path: /paths/~1orders/post/security
                      value:
                        - apiKey: []
                """,
            ),
            ChangeStatusCase(
                name = "24. operation present in old, removed in new",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                    - op: remove
                      path: /paths/~1orders/post
                """,
            ),
            ChangeStatusCase(
                name = "26. only operation description text changed",
                path = "/orders", method = "GET",
                expected = ChangeStatus.UNCHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/get/description
                      value: List all orders
                """,
            ),
            ChangeStatusCase(
                name = "27. only operationId renamed",
                path = "/orders", method = "GET",
                expected = ChangeStatus.UNCHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/get/operationId
                      value: listOrders
                """,
            ),
            ChangeStatusCase(
                name = "28. only tags changed",
                path = "/orders", method = "GET",
                expected = ChangeStatus.UNCHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/get/tags
                      value: [orders]
                """,
            ),
            ChangeStatusCase(
                name = "29. only request body example added",
                path = "/orders", method = "POST",
                expected = ChangeStatus.UNCHANGED,
                newPatch = """
                    - op: add
                      path: /paths/~1orders/post/requestBody/content/application~1json/examples
                      value:
                        sample:
                          value:
                            id: "abc"
                """,
            ),
            ChangeStatusCase(
                name = "30. schema referenced via \$ref in old, inlined in new",
                path = "/orders", method = "POST",
                expected = ChangeStatus.UNCHANGED,
                oldPatch = """
                    - op: add
                      path: /components
                      value:
                        schemas:
                          Order:
                            type: object
                            required: [id]
                            properties:
                              id:
                                type: string
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        ${'$'}ref: '#/components/schemas/Order'
                """,
            ),
            ChangeStatusCase(
                name = "31. schema referenced via same \$ref has component schema changed",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                oldPatch = """
                    - op: add
                      path: /components
                      value:
                        schemas:
                          Order:
                            type: object
                            required: [id]
                            properties:
                              id:
                                type: string
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        ${'$'}ref: '#/components/schemas/Order'
                """,
                newPatch = """
                    - op: add
                      path: /components
                      value:
                        schemas:
                          Order:
                            type: object
                            required: [id]
                            properties:
                              id:
                                type: string
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        ${'$'}ref: '#/components/schemas/Order'
                    - op: replace
                      path: /components/schemas/Order/properties/id/type
                      value: integer
                """,
            ),
            ChangeStatusCase(
                name = "33. self-recursive schema - leaf type changes",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                oldPatch = """
                    - op: add
                      path: /components
                      value:
                        schemas:
                          TreeNode:
                            type: object
                            required: [id]
                            properties:
                              id:
                                type: string
                              children:
                                type: array
                                items:
                                  ${'$'}ref: '#/components/schemas/TreeNode'
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        ${'$'}ref: '#/components/schemas/TreeNode'
                """,
                newPatch = """
                    - op: add
                      path: /components
                      value:
                        schemas:
                          TreeNode:
                            type: object
                            required: [id]
                            properties:
                              id:
                                type: integer
                              children:
                                type: array
                                items:
                                  ${'$'}ref: '#/components/schemas/TreeNode'
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        ${'$'}ref: '#/components/schemas/TreeNode'
                """,
            ),
            ChangeStatusCase(
                name = "34. mutually recursive schemas - leaf changes on File",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                oldPatch = """
                    - op: add
                      path: /components
                      value:
                        schemas:
                          Folder:
                            type: object
                            required: [name]
                            properties:
                              name:
                                type: string
                              files:
                                type: array
                                items:
                                  ${'$'}ref: '#/components/schemas/File'
                          File:
                            type: object
                            required: [name]
                            properties:
                              name:
                                type: string
                              folder:
                                ${'$'}ref: '#/components/schemas/Folder'
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        ${'$'}ref: '#/components/schemas/Folder'
                """,
                newPatch = """
                    - op: add
                      path: /components
                      value:
                        schemas:
                          Folder:
                            type: object
                            required: [name]
                            properties:
                              name:
                                type: string
                              files:
                                type: array
                                items:
                                  ${'$'}ref: '#/components/schemas/File'
                          File:
                            type: object
                            required: [name]
                            properties:
                              name:
                                type: integer
                              folder:
                                ${'$'}ref: '#/components/schemas/Folder'
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        ${'$'}ref: '#/components/schemas/Folder'
                """,
            ),
            ChangeStatusCase(
                name = "35. optional self-recursive parent ref becomes required",
                path = "/orders", method = "POST",
                expected = ChangeStatus.CHANGED,
                oldPatch = """
                    - op: add
                      path: /components
                      value:
                        schemas:
                          Node:
                            type: object
                            required: [id]
                            properties:
                              id:
                                type: string
                              parent:
                                ${'$'}ref: '#/components/schemas/Node'
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        ${'$'}ref: '#/components/schemas/Node'
                """,
                newPatch = """
                    - op: add
                      path: /components
                      value:
                        schemas:
                          Node:
                            type: object
                            required: [id, parent]
                            properties:
                              id:
                                type: string
                              parent:
                                ${'$'}ref: '#/components/schemas/Node'
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        ${'$'}ref: '#/components/schemas/Node'
                """,
            ),
            ChangeStatusCase(
                name = "36. self-recursive schema identical is UNCHANGED",
                path = "/orders", method = "POST",
                expected = ChangeStatus.UNCHANGED,
                oldPatch = """
                    - op: add
                      path: /components
                      value:
                        schemas:
                          TreeNode:
                            type: object
                            required: [id]
                            properties:
                              id:
                                type: string
                              children:
                                type: array
                                items:
                                  ${'$'}ref: '#/components/schemas/TreeNode'
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        ${'$'}ref: '#/components/schemas/TreeNode'
                """,
                newPatch = """
                    - op: add
                      path: /components
                      value:
                        schemas:
                          TreeNode:
                            type: object
                            required: [id]
                            properties:
                              id:
                                type: string
                              children:
                                type: array
                                items:
                                  ${'$'}ref: '#/components/schemas/TreeNode'
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        ${'$'}ref: '#/components/schemas/TreeNode'
                """,
            ),
            ChangeStatusCase(
                name = "32. required array and properties reordered",
                path = "/orders", method = "POST",
                expected = ChangeStatus.UNCHANGED,
                oldPatch = """
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        type: object
                        required: [id, note]
                        properties:
                          id:
                            type: string
                          note:
                            type: string
                """,
                newPatch = """
                    - op: replace
                      path: /paths/~1orders/post/requestBody/content/application~1json/schema
                      value:
                        type: object
                        required: [note, id]
                        properties:
                          note:
                            type: string
                          id:
                            type: string
                """,
            ),
            ChangeStatusCase(
                name = "33. operation path has been modified, parameter name changed",
                path = "/orders/{orderId}", method = "GET",
                expected = ChangeStatus.CHANGED,
                newPatch = """
                - op: remove
                  path: /paths/~1orders~1{id}
                - op: add
                  path: /paths/~1orders~1{orderId}
                  value:
                    get:
                      parameters:
                        - name: orderId
                          in: path
                          required: true
                          schema:
                            type: string
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [id]
                                properties:
                                  id:
                                    type: string
                """,
            ),
        )

    }
}

internal data class OperationKey(
    val method: String,
    val path: String,
    val requestContentType: String?,
    val responseCode: Int,
    val responseContentType: String?,
)

internal data class ChangeStatusCase(
    val name: String,
    val path: String,
    val method: String,
    val expected: ChangeStatus,
    val oldPatch: String? = null,
    val newPatch: String? = null,
    val responseCode: Int? = null,
    val requestContentType: String? = null,
    val responseContentType: String? = null,
) {
    override fun toString(): String = name
}

private fun String.openAPIToContract(): Feature {
    return OpenApiSpecification.fromYAML(this, "").toFeature()
}
