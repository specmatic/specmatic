package io.specmatic.rules

import io.specmatic.backwardCompatibleWith
import io.specmatic.notBackwardCompatibleWith
import io.specmatic.reporter.commands.InsightsReportOptions
import org.junit.jupiter.api.Test

class JSONBackwardCompatibilityModel {
    private val oldContract = """
Feature: User API
  Scenario: Add user
    Given json User
    | name | (string) |
    And json Status
    | status | (string) |
    When POST /user
    And request-body (User)
    Then status 200
    And response-body (Status)
""".trimIndent()

    @Test
    fun `adding non-optional key to the request body is backward incompatible`() {
        val newContract = """
Feature: User API
  Scenario: Add user
    Given json User
    | name    | (string) |
    | address | (string) |
    And json Status
    | status | (string) |
    When POST /user
    And request-body (User)
    Then status 200
    And response-body success
    And response-body (Status)
""".trimIndent()

        newContract.notBackwardCompatibleWith(oldContract, InsightsReportOptions())
    }

    @Test
    fun `adding optional key in the request body is backward compatible`() {
        val newContract = """
Feature: User API
  Scenario: Add user
    Given json User
    | name     | (string) |
    | address? | (string) |
    And json Status
    | status | (string) |
    When POST /user
    And request-body (User)
    Then status 200
    And response-body success
    And response-body (Status)
""".trimIndent()

        newContract.backwardCompatibleWith(oldContract, InsightsReportOptions())
    }

    @Test
    fun `make type optional in request body is backward compatible`() {
        val newContract = """
Feature: User API
  Scenario: Add user
    Given json User
    | name     | (string?) |
    And json Status
    | status | (string) |
    When POST /user
    And request-body (User)
    Then status 200
    And response-body success
    And response-body (Status)
""".trimIndent()

        newContract.backwardCompatibleWith(oldContract, InsightsReportOptions())
    }

    @Test
    fun `change number in string to string in request body is backward compatible`() {
        val oldContract = """
Feature: User API
  Scenario: Add user
    Given json User
    | id | (number in string) |
    And json Status
    | status | (string) |
    When POST /user
    And request-body (User)
    Then status 200
    And response-body success
    And response-body (Status)
        """.trimIndent()

        val newContract = """
Feature: User API
  Scenario: Add user
    Given json User
    | id | (string) |
    And json Status
    | status | (string) |
    When POST /user
    And request-body (User)
    Then status 200
    And response-body success
    And response-body (Status)
""".trimIndent()

        newContract.backwardCompatibleWith(oldContract, InsightsReportOptions())
    }

    @Test
    fun `change from string to number in string in request body is backward incompatible`() {
        val oldContract = """
Feature: User API
  Scenario: Add user
    Given json User
    | id | (string) |
    And json Status
    | status | (string) |
    When POST /user
    And request-body (User)
    Then status 200
    And response-body success
    And response-body (Status)
        """.trimIndent()

        val newContract = """
Feature: User API
  Scenario: Add user
    Given json User
    | id | (number in string) |
    And json Status
    | status | (string) |
    When POST /user
    And request-body (User)
    Then status 200
    And response-body success
    And response-body (Status)
""".trimIndent()

        newContract.notBackwardCompatibleWith(oldContract, InsightsReportOptions())
    }

    @Test
    fun `adding an optional key to the response body is backward compatible`() {
        val newContract = """
Feature: User API
  Scenario: Add user
    Given json User
    | name | (string) |
    And json Status
    | status | (string) |
    | data?  | (string) |
    When POST /user
    And request-body (User)
    Then status 200
    And response-body (Status)
""".trimIndent()

        newContract.backwardCompatibleWith(oldContract, InsightsReportOptions())
    }
}