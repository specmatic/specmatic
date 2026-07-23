package io.specmatic.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.flipkart.zjsonpatch.JsonPatch
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.core.utilities.Flags.Companion.using
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.reporter.api.client.OBJECT_MAPPER
import io.specmatic.reporter.ctrf.model.CtrfReport
import io.specmatic.stub.captureStandardOutput
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        val stripTrailingWhitespacePerLine: (String) -> String = { s ->
            s.lines().joinToString("\n") { it.trimEnd() }
        }
        assertThat(stripTrailingWhitespacePerLine(results.report()))
            .isEqualToNormalizingNewlines(stripTrailingWhitespacePerLine(expectedReport.trimIndent()))
    }

    @Test
    fun `all breaking changes from the fixture pair are detected and annotated`() {
        val oldSpec = File("src/test/resources/openapi/bcc-breaking-changes/old.yaml").readText()
        val newSpec = File("src/test/resources/openapi/bcc-breaking-changes/new.yaml").readText()

        val older = OpenApiSpecification.fromYAML(oldSpec, "old.yaml").toFeature()
        val newer = OpenApiSpecification.fromYAML(newSpec, "new.yaml").toFeature()

        val results = testBackwardCompatibility(older, newer)

        assertThat(results.success()).isFalse
        assertThat(results.distinctReport().normalizeBlankLines()).isEqualToNormalizingNewlines(
            """
    In scenario "submit data. Response: ok"
    API: POST /data -> 200

      >> REQUEST.PARAMETERS.QUERY.extra (new.yaml:36:11)

          R2001: Missing required property
          Documentation: https://docs.specmatic.io/rules#r2001
          Summary: A required property defined in the specification is missing

          New specification expects query param "extra" in the request but it is missing from the old specification

      >> REQUEST.PARAMETERS.QUERY.q (new.yaml:24:11)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.PARAMETERS.HEADER.X-Extra (new.yaml:54:11)

          R2001: Missing required property
          Documentation: https://docs.specmatic.io/rules#r2001
          Summary: A required property defined in the specification is missing

          New specification expects header "X-Extra" in the request but it is missing from the old specification

      >> REQUEST.PARAMETERS.HEADER.X-Required (new.yaml:42:11)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.BODY.extra (new.yaml:74:17)

          R2001: Missing required property
          Documentation: https://docs.specmatic.io/rules#r2001
          Summary: A required property defined in the specification is missing

          New specification expects property "extra" in the request but it is missing from the old specification

      >> REQUEST.BODY.id (new.yaml:69:17)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.BODY.address.street (new.yaml:81:21)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.BODY.tags[0].name (new.yaml:90:23)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.PARAMETERS.HEADER.X-Optional (new.yaml:48:11)

          R2001: Missing required property
          Documentation: https://docs.specmatic.io/rules#r2001
          Summary: A required property defined in the specification is missing

          New specification expects header "X-Optional" in the request but it is missing from the old specification

      >> REQUEST.BODY.note (new.yaml:71:17)

          R2001: Missing required property
          Documentation: https://docs.specmatic.io/rules#r2001
          Summary: A required property defined in the specification is missing

          New specification expects property "note" in the request but it is missing from the old specification

      >> REQUEST.PARAMETERS.QUERY.tag (new.yaml:30:11)

          R2001: Missing required property
          Documentation: https://docs.specmatic.io/rules#r2001
          Summary: A required property defined in the specification is missing

          New specification expects query param "tag" in the request but it is missing from the old specification

      >> RESPONSE.HEADER.X-Resp (new.yaml:97:13)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is number in the new specification response but string in the old specification

      >> RESPONSE.BODY.id (new.yaml:107:19)

          R2001: Missing required property
          Documentation: https://docs.specmatic.io/rules#r2001
          Summary: A required property defined in the specification is missing

          The old specification expects property "id" but it is missing in the new specification

      >> RESPONSE.BODY.extra (old.yaml:86:19)

          R2001: Missing required property
          Documentation: https://docs.specmatic.io/rules#r2001
          Summary: A required property defined in the specification is missing

          The old specification expects property "extra" but it is missing in the new specification

      >> RESPONSE.BODY.code (new.yaml:117:19)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is number in the new specification response but string in the old specification

    In scenario "get item. Response: ok"
    API: GET /items/(id:number) -> 200

      >> REQUEST.PARAMETERS.PATH.id (new.yaml:124:11)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

    In scenario "foo. Response: ok"
    API: GET /foo -> 200

          This API exists in the old contract but not in the new contract (old.yaml:116:5)

    In scenario "bar. Response: ok"
    API: GET /bar -> 200

          This API exists in the old contract but not in the new contract (old.yaml:122:5)

    In scenario "create baz. Response: ok"
    API: POST /baz -> 200

          This API exists in the old contract but not in the new contract (old.yaml:133:5)

    In scenario "register pet. Response: ok"
    API: POST /pets -> 200

      >> REQUEST.BODY (when Dog object).species (new.yaml:223:9)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.BODY (when Dog object).sound (new.yaml:238:13)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.BODY (when Cat object).species (new.yaml:223:9)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.BODY (when Cat object).sound (new.yaml:249:13)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type boolean in the new specification, but type string in the old specification

    In scenario "submit. Response: ok"
    API: POST /submissions -> 200

      >> REQUEST.BODY.id (new.yaml:208:9)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.BODY.kind (new.yaml:211:9)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type string in the new specification, but type number in the old specification

      >> REQUEST.BODY.category (new.yaml:215:9)

          R1002: Value mismatch
          Documentation: https://docs.specmatic.io/rules#r1002
          Summary: The value does not match the expected value defined in the specification

          This is ("A") in the new specification, but "B" in the old specification

      >> RESPONSE.BODY.status (new.yaml:185:19)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is ("accepted" or "pending") in the new specification response but ("accepted") in the old specification

    In scenario "reusable components. Response: ok"
    API: POST /reusable-components -> 200

      >> REQUEST.PARAMETERS.QUERY.reusableQuery (new.yaml:253:7)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.PARAMETERS.QUERY.filterKind (new.yaml:274:11)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.PARAMETERS.HEADER.X-Reusable (new.yaml:259:7)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> REQUEST.BODY.componentId (new.yaml:285:15)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is type number in the new specification, but type string in the old specification

      >> RESPONSE.HEADER.X-Reusable-Response (new.yaml:302:5)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is number in the new specification response but string in the old specification

      >> RESPONSE.BODY.componentStatus (new.yaml:299:15)

          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification

          This is number in the new specification response but string in the old specification

    In scenario "reusable components. Response: fallback"
    API: POST /reusable-components -> 1000

          This API exists in the old contract but not in the new contract (old.yaml:201:9)
            """.trimIndent().normalizeBlankLines()
        )
    }

    private fun String.normalizeBlankLines(): String {
        return lineSequence().joinToString("\n") { line -> if (line.isBlank()) "" else line }
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
When GET /data
Then status 200
  And response-body (number)
    """.trim()

        val gherkin2 = """
Feature: Contract API

@WIP
Scenario: api call
When GET /data
Then status 200
  And response-body (string)
    """.trim()

        val results: Results =
            testBackwardCompatibility(parseGherkinStringToFeature(gherkin1), parseGherkinStringToFeature(gherkin2))

        // The breaking WIP scenario is retained as an ignorable failure: it does not break the
        // check, but it is still visible (so it shows up in console output) rather than dropped.
        assertThat(results.successExcludingIgnorableFailures()).isTrue
        assertThat(results.hasFailures()).isTrue()
        assertThat(results.hasIgnorableFailures()).isTrue()
        assertThat(results.withoutIgnorableFailures().hasFailures()).isFalse()
        assertThat(results.ignorableFailures().report()).contains("GET /data")
    }

    @Test
    fun `all breaking XML changes from the fixture pair are detected and annotated`() {
        val oldSpec = File("src/test/resources/openapi/bcc-xml/old.yaml").readText()
        val newSpec = File("src/test/resources/openapi/bcc-xml/new.yaml").readText()

        val older = OpenApiSpecification.fromYAML(oldSpec, "old.yaml").toFeature()
        val newer = OpenApiSpecification.fromYAML(newSpec, "new.yaml").toFeature()

        val results = testBackwardCompatibility(older, newer)

        assertThat(results.success()).isFalse
        assertThat(results.distinctReport().normalizeBlankLines()).isEqualToNormalizingNewlines(
            """
    In scenario "attribute breakage. Response: ok"
    API: POST /attributes -> 200

      >> REQUEST.BODY.order.id (new.yaml:20:17)

          R2001: Missing required property
          Documentation: https://docs.specmatic.io/rules#r2001
          Summary: A required property defined in the specification is missing

          New specification expects attribute "id" in the request but it is missing from the old specification

    In scenario "child property breakage. Response: ok"
    API: POST /child-property -> 200

      >> REQUEST.BODY.order.customer.name (new.yaml:57:21)

          Didn't get enough values

    In scenario "array item breakage. Response: ok"
    API: POST /array-items -> 200

      >> REQUEST.BODY.order.items.item.name (new.yaml:88:23)

          Didn't get enough values

    In scenario "response breakage. Response: ok"
    API: POST /response -> 200

      >> RESPONSE.BODY.customer.name (new.yaml:112:19)

          This node must occur whereas the other is optional.
    """.trimIndent()
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
            """.trimIndent().openAPIToContract("old.yaml")

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
            """.trimIndent().openAPIToContract("new.yaml")

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
""".trimIndent(), "old.yaml"
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
""".trimIndent(), "new.yaml"
        ).toFeature()

        val result: Results = testBackwardCompatibility(oldContract, newContract)

        assertThat(result.report()).isEqualToNormalizingWhitespace(
            """
        In scenario "hello world. Response: Says hello"
        API: POST /data -> 200

        ${
                toViolationReportString(
                    breadCrumb = "REQUEST.BODY.data (new.yaml:20:17)",
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
""".trimIndent(), "old.yaml"
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
""".trimIndent(), "new.yaml"
        ).toFeature()

        val result: Results = testBackwardCompatibility(oldContract, newContract)

        assertThat(result.distinctReport()).isEqualTo("""
            In scenario "POST /ping. Response: ok"
            API: POST /ping -> 200

              >> REQUEST.BODY.timestamp (new.yaml:19:17)
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "timestamp" in the request but it is missing from the old specification
              
              >> REQUEST.BODY.event (new.yaml:17:17)
              
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
""".trimIndent(), "old.yaml"
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
""".trimIndent(), "new.yaml"
        ).toFeature()

        val result: Results = testBackwardCompatibility(oldContract, newContract)

        assertThat(result.distinctReport()).isEqualToIgnoringWhitespace("""
            In scenario "POST /ping. Response: common"
            API: POST /ping -> 200

              >> REQUEST.BODY.timestamp (new.yaml:19:17)

                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing

                  New specification expects property "timestamp" in the request but it is missing from the old specification

              >> REQUEST.BODY.event (new.yaml:17:17)

                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification

                  This is type number in the new specification, but type string in the old specification

            In scenario "POST /ping. Response: common"
            API: POST /ping -> 400

              >> REQUEST.BODY.timestamp (new.yaml:19:17)

                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing

                  New specification expects property "timestamp" in the request but it is missing from the old specification

              >> REQUEST.BODY.event (new.yaml:17:17)

                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification

                  This is type number in the new specification, but type string in the old specification

              >> RESPONSE.BODY.message (new.yaml:33:19)

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
""".trimIndent(), "old.yaml"
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
""".trimIndent(), "new.yaml"
        ).toFeature()

        val result: Results = testBackwardCompatibility(oldContract, newContract)

        val reportText: String = result.report()
        println(reportText)

        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "hello world. Response: Says hello"
            API: POST /data -> 200
            
              >> REQUEST.BODY.data2 (new.yaml:21:17)

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
""".trimIndent(), "old.yaml"
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
""".trimIndent(), "new.yaml"
        ).toFeature()

        val result = testBackwardCompatibility(oldContract, newContract)
        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "hello world. Response: Says hello"
            API: POST /data -> 200

              >> REQUEST.BODY.data (new.yaml:18:17)

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
""".trimIndent(), "old.yaml"
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
""".trimIndent(), "new.yaml"
        ).toFeature()

        val result = testBackwardCompatibility(oldContract, newContract)
        assertBackwardCompatibilityFailure(
            result,
            """
            In scenario "hello world. Response: Says hello"
            API: POST /data -> 200

              >> REQUEST.BODY.data (new.yaml:18:17)

                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification

                  This is type number in the new specification, but type null in the old specification

              >> REQUEST.BODY.data (new.yaml:18:17)

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
            """.trimIndent().openAPIToContract("old.yaml")

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
            """.trimIndent().openAPIToContract("new.yaml")

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
            """.trimIndent().openAPIToContract("old.yaml")

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
            """.trimIndent().openAPIToContract("new.yaml")

        val results: Results = testBackwardCompatibility(olderContract, newerContract)

        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "get products. Response: OK"
            API: GET /products -> 200

              >> REQUEST.PARAMETERS.QUERY.brand_ids (new.yaml:11:11)

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
            """.trimIndent().openAPIToContract("old.yaml")

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
            """.trimIndent().openAPIToContract("new.yaml")

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
            """.trimIndent().openAPIToContract("old.yaml")

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
            """.trimIndent().openAPIToContract("new.yaml")

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        println(results.report())
        assertThat(results.report())
            .containsIgnoringWhitespaces("""
            In scenario "get products. Response: OK"
            API: GET /products -> 200
            """.trimIndent())
            .containsIgnoringWhitespaces("""
             >> REQUEST.PARAMETERS.QUERY.brand_ids (new.yaml:11:11)

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
            """.trimIndent().openAPIToContract("old.yaml")

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
            """.trimIndent().openAPIToContract("new.yaml")

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
            """.trimIndent().openAPIToContract("old.yaml")

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
            """.trimIndent().openAPIToContract("new.yaml")

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        assertThat(results.report())
            .containsIgnoringWhitespaces("""
            In scenario "get products. Response: OK"
            API: GET /products -> 200
            """.trimIndent())
            .containsIgnoringWhitespaces("""
             >> REQUEST.PARAMETERS.QUERY.brand_ids (new.yaml:11:11)
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
        """.trimIndent().openAPIToContract("old.yaml")

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
        """.trimIndent().openAPIToContract("new.yaml")

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
        """.trimIndent().openAPIToContract("old.yaml")

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
        """.trimIndent().openAPIToContract("new.yaml")

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
        """.trimIndent().openAPIToContract("old.yaml")

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
        """.trimIndent().openAPIToContract("new.yaml")

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        println(results.report())
        assertBackwardCompatibilityFailure(
            results,
            """
            In scenario "Create a product. Response: Created"
            API: POST /products -> 201

                  This API exists in the old contract but not in the new contract (old.yaml:7:5)
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
        """.trimIndent().openAPIToContract("old.yaml")

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
        """.trimIndent().openAPIToContract("new.yaml")

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
        """.trimIndent().openAPIToContract("old.yaml")

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
        """.trimIndent().openAPIToContract("new.yaml")

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
        """.trimIndent().openAPIToContract("old.yaml")

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
        """.trimIndent().openAPIToContract("new.yaml")

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
        """.trimIndent().openAPIToContract("old.yaml")

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
        """.trimIndent().openAPIToContract("new.yaml")

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
        """.trimIndent().openAPIToContract("old.yaml")

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
        """.trimIndent().openAPIToContract("new.yaml")

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
        """.trimIndent().openAPIToContract("old.yaml")

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
        """.trimIndent().openAPIToContract("new.yaml")

        val results: Results = testBackwardCompatibility(olderContract, newerContract)
        assertBackwardCompatibilityFailure(
            results = results,
            expectedReport = """
            In scenario "Create a product. Response: Created"
            API: POST /products -> 201

                  This API exists in the old contract but not in the new contract (old.yaml:7:5)
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

        val olderContract = OpenApiSpecification.fromYAML(oldSpec, "old.yaml").toFeature()
        val newerContract = OpenApiSpecification.fromYAML(newSpec, "new.yaml").toFeature()

        val result: Results = testBackwardCompatibility(olderContract, newerContract)

        assertThat(result.success()).withFailMessage(result.report()).isFalse
        assertThat(result.report())
            .containsIgnoringWhitespaces("""
            In scenario "POST /products/validate. Response: Validation successful"
            API: POST /products/validate -> 200
            """.trimIndent())
            .containsIgnoringWhitespaces("""
             >> REQUEST.BODY.data[0] (new.yaml:19:19)

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
        val result = bccChecker.run().toBackwardCompatibilityStatuses().copy(addSourceLocation = true)

        assertThat(result.report()).isEqualToNormalizingNewlines("""
        In scenario "Missing endpoint. Response: A simple string response"
        API: GET /missing -> 200
        
              This API exists in the old contract but not in the new contract (src/test/resources/openapi/multi_req_res_ct/openapi_v1.yaml:7:5)
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:23:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:43:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:23:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field2 (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:51:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:23:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:62:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:23:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field2 (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:70:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field2 (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:31:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:43:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field2 (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:31:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field2 (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:51:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field2 (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:31:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:62:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field2 (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:31:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field2 (src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml:70:19)
          
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
        val result = bccChecker.run().toBackwardCompatibilityStatuses()

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
        val result = bccChecker.run().toBackwardCompatibilityStatuses().copy(addSourceLocation = true)

        assertThat(result.report()).isEqualToNormalizingNewlines("""
        In scenario "Missing endpoint. Response: A simple string response"
        API: GET /missing -> 200
        
              This API exists in the old contract but not in the new contract (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v1.yaml:7:5)
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:31:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:58:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:31:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field2 (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:66:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:31:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:84:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:31:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field2 (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:92:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field2 (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:39:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:58:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> REQUEST.BODY.field2 (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:39:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 200
        
          >> RESPONSE.BODY.field2 (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:66:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field2 (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:39:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:84:19)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is number in the new specification response but string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> REQUEST.BODY.field2 (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:39:17)
          
              R1001: Type mismatch
              Documentation: https://docs.specmatic.io/rules#r1001
              Summary: The value type does not match the expected type defined in the specification
          
              This is type number in the new specification, but type string in the old specification
        
        In scenario "Simple test POST endpoint. Response: A simple integer response"
        API: POST /exists/(id:string) -> 201
        
          >> RESPONSE.BODY.field2 (src/test/resources/openapi/multi_req_res_ct_overriden/openapi_v2.yaml:92:19)
          
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

        using(CONFIG_FILE_PATH to configFile.canonicalPath) {
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
            assertThat(postOperation.path("status").asText()).isEqualTo("incompatible")

            val getOperation = operations.first { it.path("method").asText() == "GET" }
            assertThat(getOperation.path("path").asText()).isEqualTo("/orders")
            assertThat(getOperation.path("method").asText()).isEqualTo("GET")
            assertThat(getOperation.path("responseCode").asInt()).isEqualTo(200)
            assertThat(getOperation.path("responseContentType").asText()).isEqualTo("application/json")
            assertThat(getOperation.path("qualifiers").map { it.asText() }).containsExactly("changed")
            assertThat(getOperation.path("testIds").isArray).isTrue()
            assertThat(getOperation.path("testIds").size()).isGreaterThan(0)
            assertThat(getOperation.path("status").asText()).isEqualTo("compatible")
        }
    }

    @Test
    fun `generateBackwardCompatibilityReport returns null and writes nothing when there are no records`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText("""
            version: 2
            reportDirPath: ${tempDir.canonicalPath}/reports
            """.trimIndent())
        }

        using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            val report = generateBackwardCompatibilityReport(emptyList(), 0L, 1L)

            assertThat(report).isNull()
            assertThat(tempDir.resolve("reports")).doesNotExist()
        }
    }

    @Nested
    inner class BccIntegrationFromFixtures {
        @Test
        fun `report reflects per-5-tuple change status and compatibility result across refs, components, and recursion`(@TempDir tempDir: File) {
            val oldSpec = readFixture("orders_old.yaml")
            val newSpec = readFixture("orders_new.yaml")

            val (results, report) = runBcc(tempDir, oldSpec, newSpec)
            val operations = operationsFrom(report)
            val json = "application/json"

            // The real breaking changes (GET /orders 400/500, DELETE /orders/{id}, the anyOf/oneOf
            // unions, and the reffed-out request body/response/path-item/header) fail the check;
            // the breaking WIP operation does not contribute to this verdict.
            assertThat(results.success()).isFalse()

            // The full console report: every incompatibility (including the breaking WIP
            // operation GET /promotions, which is shown but does not break the verdict). Each
            // located breadcrumb is asserted at the source position of the element that changed,
            // including across $ref'd-out request bodies, responses, path items, and headers.
            assertThat(results.report()).isEqualToNormalizingNewlines("""
            In scenario "GET /orders. Response: bad request"
            API: GET /orders -> 400

              >> RESPONSE.BODY.code (new.yaml:283:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is number in the new specification response but string in the old specification

            In scenario "GET /orders. Response: server error"
            API: GET /orders -> 500

                  This API exists in the old contract but not in the new contract (old.yaml:28:9)

            In scenario "DELETE /orders/(id:string). Response: deleted"
            API: DELETE /orders/(id:string) -> 204

                  This API exists in the old contract but not in the new contract (old.yaml:87:5)

            In scenario "GET /shipments. Response: ok"
            API: GET /shipments -> 200

              >> RESPONSE.BODY.weight (new.yaml:332:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is number in the new specification response but string in the old specification
              
              >> RESPONSE.BODY.carrier (new.yaml:340:13)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is number in the new specification response but string in the old specification

            In scenario "GET /promotions. Response: ok"
            API: GET /promotions -> 200

              >> RESPONSE.BODY.code (new.yaml:116:19)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is number in the new specification response but string in the old specification

            In scenario "PUT /widgets/(widgetId:number). Response: ok"
            API: PUT /widgets/(widgetId:number) -> 200

              >> REQUEST.PARAMETERS.PATH.widgetId (new.yaml:344:7)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type number in the new specification, but type string in the old specification

            In scenario "GET /anyof. Response: ok"
            API: GET /anyof -> 200

              >> RESPONSE.BODY.alpha (old.yaml:186:23)
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  The old specification expects property "alpha" but it is missing in the new specification

            In scenario "GET /oneof. Response: ok"
            API: GET /oneof -> 200

              >> RESPONSE.BODY.gamma (new.yaml:174:23)
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  The old specification expects property "gamma" but it is missing in the new specification

            In scenario "POST /parcels. Response: ok"
            API: POST /parcels -> 200

              >> REQUEST.BODY.size (new.yaml:358:15)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type number in the new specification, but type string in the old specification

            In scenario "GET /inventory. Response: ok"
            API: GET /inventory -> 200

              >> RESPONSE.BODY.count (new.yaml:369:15)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is number in the new specification response but string in the old specification

            In scenario "GET /audit. Response: ok"
            API: GET /audit -> 200

              >> RESPONSE.BODY.event (new.yaml:383:21)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is number in the new specification response but string in the old specification

            In scenario "GET /tokens. Response: ok"
            API: GET /tokens -> 200

              >> RESPONSE.HEADER.X-Rate-Limit (new.yaml:386:5)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is number in the new specification response but string in the old specification
            """.trimIndent())

            // NEW-SIDE N1: Order gains optional `notes` -> CHANGED + Compatible on every Order-using row
            operations.assertRow(OperationKey("GET", "/orders", null, 200, json), changeStatus = "CHANGED", result = "compatible")
            operations.assertRow(OperationKey("POST", "/orders", json, 201, json), changeStatus = "CHANGED", result = "compatible")
            operations.assertRow(OperationKey("GET", "/orders/{id}", null, 200, json), changeStatus = "CHANGED", result = "compatible")

            // NEW-SIDE N2: ErrorBrief.code string -> integer (reachable ONLY from GET /orders 400)
            operations.assertRow(OperationKey("GET", "/orders", null, 400, json), changeStatus = "CHANGED", result = "incompatible")

            // NEW-SIDE N3: Self-recursive Category gains optional `description`
            operations.assertRow(OperationKey("GET", "/categories", null, 200, json), changeStatus = "CHANGED", result = "compatible")

            // NEW-SIDE N4 (breaking): Shipment is an allOf of a $ref'd constituent (ShipmentBase,
            // contributing `weight`) and an inline constituent (contributing `carrier`). Both
            // properties change type string -> number, so GET /shipments 200 is CHANGED +
            // Incompatible. The console report above asserts each breadcrumb is annotated at the
            // constituent that declares the property (ShipmentBase.weight and the inline carrier),
            // proving annotateAllOfPattern resolves both $ref and inline allOf members.
            operations.assertRow(OperationKey("GET", "/shipments", null, 200, json), changeStatus = "CHANGED", result = "incompatible")

            // NEW-SIDE N5 (breaking): PUT /widgets/{widgetId} takes its `widgetId` path parameter
            // via $ref to #/components/parameters/WidgetId, whose schema changes string -> integer.
            // The $ref use-site has no `name`, so the breadcrumb must resolve to the component
            // parameter's `name` key (asserted at new.yaml:248:7 in the console report above) -
            // a regression guard that reffed-out path parameters are located at the component.
            operations.assertRow(OperationKey("PUT", "/widgets/{widgetId}", null, 200, json), changeStatus = "CHANGED", result = "incompatible")

            // OLD-SIDE O1 (non-breaking): ErrorDetailed loses optional `traceId`
            operations.assertRow(OperationKey("GET", "/orders/{id}", null, 404, json), changeStatus = "CHANGED", result = "compatible")

            // OLD-SIDE O2 (breaking): DELETE /orders/{id} removed from new (no request/response body)
            operations.assertRow(OperationKey("DELETE", "/orders/{id}", null, 204, null), changeStatus = "CHANGED", result = "incompatible")

            // OLD-SIDE O3 (breaking): GET /orders 500 response removed from new
            operations.assertRow(OperationKey("GET", "/orders", null, 500, json), changeStatus = "CHANGED", result = "incompatible")

            // UNCHANGED 5-tuple sharing a (path, method) with a CHANGED row -
            // POST /orders 400 uses OrderInput (untouched) for request and ValidationError
            // (untouched) for response, while POST /orders 201 uses Order (changed) for response.
            operations.assertRow(OperationKey("POST", "/orders", json, 400, json), changeStatus = "UNCHANGED", result = "compatible")

            // UNCHANGED standalone operation - GET /health is identical in both specs
            operations.assertRow(OperationKey("GET", "/health", null, 200, json), changeStatus = "UNCHANGED", result = "compatible")

            // WIP operation: still executes and is change-tracked. `code` changes string -> integer
            // (breaking), so the operation is CHANGED + Incompatible and carries the `wip` qualifier.
            // It must NOT break the verdict (asserted separately below).
            val wipRow = operations.assertRow(OperationKey("GET", "/promotions", null, 200, json), changeStatus = "CHANGED", result = "incompatible")
            assertThat(wipRow.path("qualifiers").map { it.asText() })
                .describedAs("WIP operation qualifiers")
                .contains("wip")

            // NEW-SIDE N6 (breaking): GET /anyof returns an anyOf of two inline object members;
            // member[1].beta changes type string -> integer. The located breadcrumb above
            // (old.yaml:186:23) resolves to the `alpha` branch member, proving annotateAnyOfPattern
            // annotates per-branch property pointers.
            operations.assertRow(OperationKey("GET", "/anyof", null, 200, json), changeStatus = "CHANGED", result = "incompatible")

            // NEW-SIDE N7 (breaking): GET /oneof returns a oneOf of two inline object members;
            // member[1].delta changes type string -> integer. The located breadcrumb above
            // (new.yaml:174:23) resolves to the `gamma` branch member, proving annotateAnyPattern
            // annotates per-branch property pointers.
            operations.assertRow(OperationKey("GET", "/oneof", null, 200, json), changeStatus = "CHANGED", result = "incompatible")

            // NEW-SIDE N8 (breaking): POST /parcels takes its request body via $ref to
            // #/components/requestBodies/ParcelBody, whose schema property `size` changes type
            // string -> integer. The REQUEST.BODY.size breadcrumb is annotated at the component
            // requestBody's schema (new.yaml:358:15), not the use-site - a reffed-out request body.
            operations.assertRow(OperationKey("POST", "/parcels", json, 200, json), changeStatus = "CHANGED", result = "incompatible")

            // NEW-SIDE N9 (breaking): GET /inventory returns a $ref to
            // #/components/responses/InventoryOk, whose schema property `count` changes type
            // string -> integer. The RESPONSE.BODY.count breadcrumb is annotated at the component
            // response's schema (new.yaml:369:15) - a reffed-out response.
            operations.assertRow(OperationKey("GET", "/inventory", null, 200, json), changeStatus = "CHANGED", result = "incompatible")

            // NEW-SIDE N10 (breaking): /audit is a $ref to #/components/pathItems/Audit, whose
            // GET 200 response schema property `event` changes type string -> integer. The
            // RESPONSE.BODY.event breadcrumb resolves to the component pathItem's schema
            // (new.yaml:383:21) - a reffed-out path item.
            operations.assertRow(OperationKey("GET", "/audit", null, 200, json), changeStatus = "CHANGED", result = "incompatible")

            // NEW-SIDE N11 (breaking): GET /tokens 200 declares a header via $ref to
            // #/components/headers/RateLimit, whose schema changes type string -> integer. The
            // RESPONSE.HEADER.X-Rate-Limit breadcrumb resolves to the component header definition
            // (new.yaml:386:5) - a reffed-out response header.
            operations.assertRow(OperationKey("GET", "/tokens", null, 200, json), changeStatus = "CHANGED", result = "incompatible")

            // Sanity: the report has exactly the rows we asserted above and no more
            assertThat(operations.toList())
                .describedAs("expected 19 operation rows in the report")
                .hasSize(19)
        }

        @Test
        fun `a breaking wip operation executes and is reported as other while retaining its incompatible raw status`(@TempDir tempDir: File) {
            val oldSpec = readFixture("orders_old.yaml")
            val newSpec = readFixture("orders_new.yaml")

            val (results, report) = runBcc(tempDir, oldSpec, newSpec)

            // The breaking WIP operation does not drive the verdict; the real breaking changes do.
            assertThat(results.success()).isFalse()

            val reportJson = OBJECT_MAPPER.valueToTree<JsonNode>(report)
            val tests = reportJson.path("results").path("tests")
            val wipTests = tests.filter { it.path("tags").map { tag -> tag.asText() }.contains("wip") }
            assertThat(wipTests).describedAs("WIP tests in the report").isNotEmpty()

            // Every WIP test is reported as `other` (never `passed`/`failed`).
            assertThat(wipTests).allSatisfy { wipTest ->
                assertThat(wipTest.path("status").asText()).isEqualTo("other")
            }
            // The WIP operation actually executed and produced a real failure: at least one WIP
            // test retains an `incompatible` raw status even though its reported state is `other`.
            assertThat(wipTests).anySatisfy { wipTest ->
                assertThat(wipTest.path("rawStatus").asText()).isEqualTo("incompatible")
            }

            // The WIP failure is counted under `other`, never `failed`.
            val summary = reportJson.path("results").path("summary")
            assertThat(summary.path("other").asInt()).isGreaterThanOrEqualTo(1)
        }

        @Test
        fun `composition keyword and ref form combinations annotate source locations at the changed property`(@TempDir tempDir: File) {
            val oldSpec = readFixture("composition_old.yaml")
            val newSpec = readFixture("composition_new.yaml")

            val (results, _) = runBcc(tempDir, oldSpec, newSpec)

            assertThat(results.success()).isFalse()

            assertThat(results.report()).isEqualToNormalizingNewlines("""
            In scenario "POST /allof-inline. Response: ok"
            API: POST /allof-inline -> 200

              >> REQUEST.BODY.count (new.yaml:28:21)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /allof-reffed. Response: ok"
            API: POST /allof-reffed -> 200

              >> REQUEST.BODY.count (new.yaml:167:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /allof-payload-ref. Response: ok"
            API: POST /allof-payload-ref -> 200

              >> REQUEST.BODY.count (new.yaml:167:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /oneof-inline. Response: ok"
            API: POST /oneof-inline -> 200

              >> REQUEST.BODY.name (new.yaml:71:21)
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "name" in the request but it is missing from the old specification
              
              >> REQUEST.BODY.count (new.yaml:76:21)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /oneof-reffed. Response: ok"
            API: POST /oneof-reffed -> 200

              >> REQUEST.BODY (when MetricName object).name (new.yaml:161:9)
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "name" in the request but it is missing from the old specification
              
              >> REQUEST.BODY (when MetricCount object).count (new.yaml:167:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /oneof-payload-ref. Response: ok"
            API: POST /oneof-payload-ref -> 200

              >> REQUEST.BODY (when MetricName object).name (new.yaml:161:9)
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "name" in the request but it is missing from the old specification
              
              >> REQUEST.BODY (when MetricCount object).count (new.yaml:167:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /anyof-inline. Response: ok"
            API: POST /anyof-inline -> 200

              >> REQUEST.BODY.count (new.yaml:124:21)
              
                  R3005: Property matches no schema option
                  Documentation: https://docs.specmatic.io/rules#r3005
                  Summary: The property does not satisfy any available schema options
              
                  Key 'count' did not match any anyOf option that declares it
              
              >> REQUEST.BODY.count (new.yaml:124:21)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification
              
              >> REQUEST.BODY.name (new.yaml:119:21)
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "name" in the request but it is missing from the old specification
              
              >> REQUEST.BODY.count (new.yaml:124:21)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /anyof-reffed. Response: ok"
            API: POST /anyof-reffed -> 200

              >> REQUEST.BODY.count (new.yaml:167:9)
              
                  R3005: Property matches no schema option
                  Documentation: https://docs.specmatic.io/rules#r3005
                  Summary: The property does not satisfy any available schema options
              
                  Key 'count' did not match any anyOf option that declares it
              
              >> REQUEST.BODY.count (new.yaml:167:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification
              
              >> REQUEST.BODY (when MetricName object).name (new.yaml:161:9)
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "name" in the request but it is missing from the old specification
              
              >> REQUEST.BODY (when MetricCount object).count (new.yaml:167:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /anyof-payload-ref. Response: ok"
            API: POST /anyof-payload-ref -> 200

              >> REQUEST.BODY.count (new.yaml:167:9)
              
                  R3005: Property matches no schema option
                  Documentation: https://docs.specmatic.io/rules#r3005
                  Summary: The property does not satisfy any available schema options
              
                  Key 'count' did not match any anyOf option that declares it
              
              >> REQUEST.BODY.count (new.yaml:167:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification
              
              >> REQUEST.BODY (when MetricName object).name (new.yaml:161:9)
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "name" in the request but it is missing from the old specification
              
              >> REQUEST.BODY (when MetricCount object).count (new.yaml:167:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification
            """.trimIndent())
        }

        @Test
        fun `oneOf and anyOf with a nullable empty-object branch still annotate the changed property`(@TempDir tempDir: File) {
            val oldSpec = readFixture("composition_nullable_old.yaml")
            val newSpec = readFixture("composition_nullable_new.yaml")

            val (results, _) = runBcc(tempDir, oldSpec, newSpec)

            assertThat(results.success()).isFalse()

            assertThat(results.report()).isEqualToNormalizingNewlines("""
            In scenario "POST /oneof-nullable-first. Response: ok"
            API: POST /oneof-nullable-first -> 200

              >> REQUEST.BODY.count (new.yaml:25:21)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /oneof-nullable-middle. Response: ok"
            API: POST /oneof-nullable-middle -> 200

              >> REQUEST.BODY.name (new.yaml:42:21)
              
                  R2001: Missing required property
                  Documentation: https://docs.specmatic.io/rules#r2001
                  Summary: A required property defined in the specification is missing
              
                  New specification expects property "name" in the request but it is missing from the old specification
              
              >> REQUEST.BODY.count (new.yaml:49:21)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /anyof-nullable-first. Response: ok"
            API: POST /anyof-nullable-first -> 200

              >> REQUEST.BODY.count (new.yaml:68:21)
              
                  R3005: Property matches no schema option
                  Documentation: https://docs.specmatic.io/rules#r3005
                  Summary: The property does not satisfy any available schema options
              
                  Key 'count' did not match any anyOf option that declares it
              
              >> REQUEST.BODY.count (new.yaml:68:21)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification
            """.trimIndent())
        }

        @Test
        fun `array bodies reached through a ref annotate the changed item property`(@TempDir tempDir: File) {
            val oldSpec = readFixture("composition_array_old.yaml")
            val newSpec = readFixture("composition_array_new.yaml")

            val (results, _) = runBcc(tempDir, oldSpec, newSpec)

            assertThat(results.success()).isFalse()

            assertThat(results.report()).isEqualToNormalizingNewlines("""
            In scenario "POST /array-body-ref. Response: ok"
            API: POST /array-body-ref -> 200

              >> REQUEST.BODY[0].count (new.yaml:50:11)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /array-items-ref. Response: ok"
            API: POST /array-items-ref -> 200

              >> REQUEST.BODY[0].count (new.yaml:42:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification
            """.trimIndent())
        }

        @Test
        fun `form-urlencoded and multipart bodies annotate the changed field at its source`(@TempDir tempDir: File) {
            val oldSpec = readFixture("composition_multipart_old.yaml")
            val newSpec = readFixture("composition_multipart_new.yaml")

            val (results, _) = runBcc(tempDir, oldSpec, newSpec)

            assertThat(results.success()).isFalse()

            assertThat(results.report()).isEqualToNormalizingNewlines("""
            In scenario "POST /urlencoded-inline. Response: ok"
            API: POST /urlencoded-inline -> 200

              >> REQUEST.FORM-FIELDS.count (new.yaml:21:17)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type string in the old specification

            In scenario "POST /urlencoded-ref. Response: ok"
            API: POST /urlencoded-ref -> 200

              >> REQUEST.FORM-FIELDS.count (new.yaml:72:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type string in the old specification

            In scenario "POST /multipart-inline. Response: ok"
            API: POST /multipart-inline -> 200

              >> REQUEST.MULTIPART-FORMDATA.count (new.yaml:49:17)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification

            In scenario "POST /multipart-ref. Response: ok"
            API: POST /multipart-ref -> 200

              >> REQUEST.MULTIPART-FORMDATA.count (new.yaml:78:9)
              
                  R1001: Type mismatch
                  Documentation: https://docs.specmatic.io/rules#r1001
                  Summary: The value type does not match the expected type defined in the specification
              
                  This is type boolean in the new specification, but type number in the old specification
            """.trimIndent())
        }

        private fun JsonNode.assertRow(key: OperationKey, changeStatus: String, result: String): JsonNode {
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
            return row
        }

        private fun readFixture(name: String): String =
            javaClass.getResource("/openapi/bcc_integration/$name")!!.readText()

        private fun runBcc(tempDir: File, oldSpec: String, newSpec: String): Pair<Results, CtrfReport?> {
            val configFile = tempDir.resolve("specmatic.yaml").apply {
                writeText("""
                version: 2
                reportDirPath: ${tempDir.canonicalPath}/reports
                """.trimIndent())
            }
            val oldFeature = OpenApiSpecification.fromYAML(oldSpec, "old.yaml").toFeature()
            val newFeature = OpenApiSpecification.fromYAML(newSpec, "new.yaml").toFeature()

            return using(CONFIG_FILE_PATH to configFile.canonicalPath) {
                testBackwardCompatibilityWithReport(oldFeature, newFeature)
            }
        }

        private fun operationsFrom(report: CtrfReport?): JsonNode {
            val json = OBJECT_MAPPER.valueToTree<JsonNode>(report)
            return json.path("results").path("summary").path("extra").path("executionDetails").first().path("operations")
        }

        // When a breaking change lives in an externally $ref'd spec, the breadcrumb must be annotated
        // with the line:col in THAT file. Each case is a fixture folder under
        // resources/openapi/bcc-external-ref/<case>/ with an old/ and a new/ tree (entry spec "api.yaml"
        // plus the external files it references). We load each entry via fromFile so the refs resolve
        // off disk, then assert the located breadcrumb points at the exact file:line:col that changed.
        @Nested
        inner class ExternalRefSourceLocations {
            private val fixtures = File("src/test/resources/openapi/bcc-external-ref")

            @Test
            fun `request body is a ref to an external schema`() =
                assertLocated("request-body", ">> REQUEST.BODY.name", "new/common.yaml:10:9")

            @Test
            fun `response body is a ref to an external schema`() =
                assertLocated("response-body", ">> RESPONSE.BODY.name", "new/common.yaml:10:9")

            @Test
            fun `a property is a ref to an external schema`() =
                assertLocated("nested-property", ">> REQUEST.BODY.product.name", "new/common.yaml:10:9")

            @Test
            fun `array items are a ref to an external schema`() =
                assertLocated("array-items", ">> REQUEST.BODY[0].name", "new/common.yaml:10:9")

            @Test
            fun `an allOf constituent is a ref to an external schema`() =
                assertLocated("allof-constituent", ">> REQUEST.BODY.name", "new/common.yaml:10:9")

            @Test
            fun `a ref to an external schema that is itself an allOf of refs`() =
                assertLocated("ref-to-allof", ">> REQUEST.BODY.count", "new/common.yaml:19:9")

            @Test
            fun `a oneOf branch is a ref to an external schema`() =
                assertLocated("oneof-branch", ">> REQUEST.BODY.name", "new/common.yaml:10:9")

            @Test
            fun `an anyOf branch is a ref to an external schema`() =
                assertLocated("anyof-branch", ">> REQUEST.BODY.name", "new/common.yaml:10:9")

            @Test
            fun `the request body is reffed out to an external requestBodies component`() =
                assertLocated("requestbody-component", ">> REQUEST.BODY.name", "new/common.yaml:14:15")

            @Test
            fun `a response is reffed out to an external responses component`() =
                assertLocated("response-component", ">> RESPONSE.BODY.name", "new/common.yaml:14:15")

            @Test
            fun `the whole parameter is reffed out to an external parameters component`() =
                assertLocated("parameter-component", ">> REQUEST.PARAMETERS.QUERY.kind", "new/common.yaml:7:7")

            @Test
            fun `the whole path item is reffed out to an external pathItems component`() =
                assertLocated("path-item", ">> RESPONSE.BODY.state", "new/common.yaml:17:21")

            @Test
            fun `two external path item files sharing a fragment each keep their own source location`() {
                val results = compare("path-item-same-fragment-collision")
                assertThat(results.success()).isFalse()
                val commonA = at("path-item-same-fragment-collision", "new/commonA.yaml")
                val commonB = at("path-item-same-fragment-collision", "new/commonB.yaml")
                assertThat(results.report()).contains("-> $commonA:17:21)")
                assertThat(results.report()).contains("-> $commonB:18:21)")
            }

            // The entry spec owns #/components/pathItems/Status AND also refs an external file's
            // identically-named fragment. A change inside the entry's own path item must stay anchored
            // in the entry file: the external projection must not bleed over the entry component pointer.
            @Test
            fun `an entry path item is not relocated by an external ref sharing its fragment`() {
                val results = compare("entry-owns-path-item-fragment")
                assertThat(results.success()).isFalse()
                val api = at("entry-owns-path-item-fragment", "new/api.yaml")
                val common = at("entry-owns-path-item-fragment", "new/common.yaml")
                // The entry owns this path item, so the change is anchored directly in the entry spec:
                // a single-hop chain with no `->`, not relocated into the external file.
                assertThat(results.report()).contains(">> RESPONSE.BODY.state ($api:21:21)")
                assertThat(results.report()).doesNotContain("$common:18:21)")
            }

            @Test
            fun `two external request body files sharing a fragment each keep their own source location`() {
                val results = compare("requestbody-same-fragment-collision")
                assertThat(results.success()).isFalse()
                val commonA = at("requestbody-same-fragment-collision", "new/commonA.yaml")
                val commonB = at("requestbody-same-fragment-collision", "new/commonB.yaml")
                assertThat(results.report()).contains("-> $commonA:14:15)")
                assertThat(results.report()).contains("-> $commonB:15:15)")
            }

            @Test
            fun `two external response files sharing a fragment each keep their own source location`() {
                val results = compare("response-same-fragment-collision")
                assertThat(results.success()).isFalse()
                val commonA = at("response-same-fragment-collision", "new/commonA.yaml")
                val commonB = at("response-same-fragment-collision", "new/commonB.yaml")
                assertThat(results.report()).contains("-> $commonA:14:15)")
                assertThat(results.report()).contains("-> $commonB:15:15)")
            }

            @Test
            fun `the change is two ref hops away in a transitive chain`() =
                assertLocated("transitive-chain", ">> REQUEST.BODY.detail.value", "new/commonB.yaml:10:9")

            // The full source-location chain is rendered head -> ... -> tail: the use-site in the entry
            // spec, each intermediate $ref use-site, then the actual source of the change. This pins the
            // whole rendering so the chain can be reduced to a single root downstream.
            @Test
            fun `a single external hop renders the entry use-site then the external source`() {
                val results = compare("request-body")
                val api = at("request-body", "new/api.yaml")
                val common = at("request-body", "new/common.yaml")
                assertThat(locationChainFor(results.report(), ">> REQUEST.BODY.name"))
                    .isEqualTo("$api:10:13 -> $common:10:9")
            }

            @Test
            fun `a transitive chain renders every ref hop from the entry spec to the source`() {
                val results = compare("transitive-chain")
                val api = at("transitive-chain", "new/api.yaml")
                val commonA = at("transitive-chain", "new/commonA.yaml")
                val commonB = at("transitive-chain", "new/commonB.yaml")
                assertThat(locationChainFor(results.report(), ">> REQUEST.BODY.detail.value"))
                    .isEqualTo("$api:10:13 -> $commonA:10:9 -> $commonB:10:9")
            }

            @Test
            fun `a transitive ref renamed during import keeps its external source location`() =
                assertLocated("transitive-renamed-import", ">> REQUEST.BODY.detail.value", "new/commonB.yaml:10:9")

            // common.yaml#/Envelope has a LOCAL $ref to common.yaml#/Payload, and the entry spec
            // already defines a Payload, so the parser imports the external one as Payload_1. The
            // local ref is never a cross-file use, so projection has to still map the renamed local
            // target back to its file, or the change inside it loses its external source location.
            @Test
            fun `a local ref renamed during import keeps its external source location`() =
                assertLocated("local-ref-renamed-import", ">> REQUEST.BODY.detail.value", "new/common.yaml:16:9")

            // commonA and commonB $ref each other (Node.next points across the cycle), so the ref
            // graph has a loop. Discovery and the projection fixpoint must terminate rather than
            // chase the cycle forever; the change still resolves to its file. If this hangs, the
            // cycle guard regressed.
            @Test
            fun `cyclic refs between two external files terminate and still locate the change`() =
                assertLocated("cyclic-ref", ">> REQUEST.BODY.value", "new/commonA.yaml:10:9")

            // An external schema (common.yaml#/components/schemas/Node) whose `next` recurses through
            // the document root with $ref: "#" — an unresolvable whole-document self ref with an empty
            // base. An empty-base projection matches every pointer in the file and used to re-feed the
            // projection fixpoint with ever-deeper paths until toFeature hung or exhausted memory. The
            // self ref is now skipped, so the malformed spec surfaces a clean unresolved-reference error
            // instead of hanging. The assertion completing at all is the regression guard.
            @Test
            fun `a recursive ref to the document root terminates instead of hanging`() {
                assertThatThrownBy { compare("root-self-ref") }
                    .isInstanceOf(ContractException::class.java)
                    .hasMessageContaining("Unresolved reference")
            }

            // Two external files that share the same fragment (#/components/schemas/Payload) must each
            // keep their own source location. The bare pointer collides, so the resolved external file
            // has to remain part of the key or one file's change is reported at the other file's line.
            @Test
            fun `two external files sharing a fragment each keep their own source location`() {
                val results = compare("same-fragment-collision")
                assertThat(results.success()).isFalse()
                val commonA = at("same-fragment-collision", "new/commonA.yaml")
                val commonB = at("same-fragment-collision", "new/commonB.yaml")
                assertThat(results.report()).contains("-> $commonA:10:9)")
                assertThat(results.report()).contains("-> $commonB:12:9)")
            }

            @Test
            fun `a newly required property is added in an external schema`() =
                assertLocated("added-required", ">> REQUEST.BODY.token", "new/common.yaml:11:9")

            @Test
            fun `a required response property is removed from an external schema, located in the old file`() =
                assertLocated("removed-response-property", ">> RESPONSE.BODY.name", "old/common.yaml:11:9")

            @Test
            fun `a whole-file ref with no fragment resolves into the bare schema file`() =
                assertLocated("whole-file", ">> REQUEST.BODY.name", "new/ProductBase.yaml:4:3")

            @Test
            fun `a whole-file path item ref with no fragment is located in the path item file`() =
                assertLocated("whole-file-path-item", ">> RESPONSE.BODY.state", "new/pathItem.yaml:11:15")

            @Test
            fun `a whole-file request body ref with no fragment is located in the request body file`() =
                assertLocated("whole-file-request-body", ">> REQUEST.BODY.name", "new/requestBody.yaml:8:9")

            @Test
            fun `a whole-file response ref with no fragment is located in the response file`() =
                assertLocated("whole-file-response", ">> RESPONSE.BODY.name", "new/response.yaml:8:9")

            @Test
            fun `an operation removed behind an external path item, located in the old file`() =
                assertLocated(
                    "removed-operation",
                    "This API exists in the old contract but not in the new contract",
                    "old/common.yaml:7:7"
                )

            // A parameter is always declared in the referring spec, so its breadcrumb anchors on that
            // declaration even when the parameter's schema is an external ref. Uniform across query,
            // path and header parameters: the annotated file is the entry spec, not the external one.
            @Test
            fun `a query parameter whose schema is an external ref is annotated at its declaration in the entry spec`() =
                assertLocated("query-param-schema", ">> REQUEST.PARAMETERS.QUERY.kind", "new/api.yaml:7:11")

            @Test
            fun `a path parameter whose schema is an external ref is annotated at its declaration in the entry spec`() =
                assertLocated("path-param-schema", ">> REQUEST.PARAMETERS.PATH.id", "new/api.yaml:7:11")

            @Test
            fun `a header parameter whose schema is an external ref is annotated at its declaration in the entry spec`() =
                assertLocated("header-param-schema", ">> REQUEST.PARAMETERS.HEADER.X-Tenant", "new/api.yaml:7:11")

            // The breadcrumb now carries a source-location chain `head -> ... -> tail`, where the tail
            // is the actual source of the change. These cases assert tail correctness; the chain's
            // head-to-tail rendering is pinned separately below.
            private fun assertLocated(case: String, breadcrumb: String, fileLineCol: String) {
                val results = compare(case)
                assertThat(results.success()).isFalse()
                val file = at(case, fileLineCol.substringBefore(":"))
                val lineCol = fileLineCol.substringAfter(":")
                assertThat(locationChainFor(results.report(), breadcrumb)).endsWith("$file:$lineCol")
            }

            // Returns the source-location chain rendered inside the parentheses after `breadcrumb`,
            // e.g. "/a/api.yaml:10:13 -> /a/common.yaml:10:9". Chains contain no nested parens.
            private fun locationChainFor(report: String, breadcrumb: String): String {
                val marker = "$breadcrumb ("
                val start = report.indexOf(marker)
                require(start >= 0) { "Located breadcrumb '$breadcrumb' not found in report:\n$report" }
                val open = start + marker.length
                return report.substring(open, report.indexOf(')', open))
            }

            private fun compare(case: String): Results {
                val older = OpenApiSpecification.fromFile(fixtures.resolve("$case/old/api.yaml").canonicalPath).toFeature()
                val newer = OpenApiSpecification.fromFile(fixtures.resolve("$case/new/api.yaml").canonicalPath).toFeature()
                return backwardCompatibilityRecords(older, newer).first
            }

            private fun at(case: String, relativePath: String): String =
                fixtures.resolve("$case/$relativePath").canonicalFile.invariantSeparatorsPath
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

            return using(CONFIG_FILE_PATH to configFile.canonicalPath) {
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
                // Same patch as case 22 (GET /orders/{id}'s path parameter type changes), but
                // asserted on the sibling GET /orders, whose own contract is untouched. Change
                // status must be keyed on each operation's own contract - a path parameter change
                // on one operation must not flip another operation that merely shares the method.
                name = "22b. a path parameter type change on one operation leaves a sibling GET operation UNCHANGED",
                path = "/orders", method = "GET",
                expected = ChangeStatus.UNCHANGED,
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

private fun String.openAPIToContract(fileName: String = ""): Feature {
    return OpenApiSpecification.fromYAML(this, fileName).toFeature()
}
