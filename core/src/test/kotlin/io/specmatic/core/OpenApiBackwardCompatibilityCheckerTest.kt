package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.resolvedHop
import io.specmatic.core.value.StringValue
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiBackwardCompatibilityCheckerTest {
    @Test
    fun `run should remove failure reasons from generated records without hiding mismatch`() {
        val oldSpec = OpenApiSpecification.fromYAML("""
        openapi: 3.0.1
        info:
          title: Orders API
          version: 1.0.0
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: string
              responses:
                '200':
                  description: ok
        """.trimIndent(), "old.yaml").toFeature()

        val newSpec = OpenApiSpecification.fromYAML("""
        openapi: 3.0.1
        info:
          title: Orders API
          version: 1.0.1
        paths:
          /orders:
            post:
              requestBody:
                required: true
                content:
                  text/plain:
                    schema:
                      type: string
              responses:
                '200':
                  description: ok
        """.trimIndent(), "new.yaml").toFeature()

        val records = OpenApiBackwardCompatibilityChecker(oldSpec, newSpec).run()
        val failure = records.map { it.compatResult }.filterIsInstance<Result.Failure>().single()

        assertThat(failure.isFluffy()).isFalse()
        assertThat(failure.traverseFailureReason()).isNull()
        assertThat(Results(listOf(failure)).report()).isNotEqualTo(PATH_NOT_RECOGNIZED_ERROR)
        assertThat(failure.reportString()).isEqualToIgnoringWhitespace("""
        In scenario "POST /orders. Response: ok"
        API: POST /orders -> 200
        ${
            toViolationReportString(
                breadCrumb = "REQUEST.PARAMETERS.HEADER.Content-Type",
                details = "This is text/plain in the new specification, but application/json in the old specification",
                StandardRuleViolation.VALUE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `request body password pattern is compatible when only a path description changes`() {
        val passwordRegex = """^(?=.*[A-Z])(?=.*[a-z])(?=.*\d)(?=.*[^A-Za-z0-9]).*$"""
        val oldSpec = OpenApiSpecification.fromYAML(passwordRecoverySpec("Sets a new password."), "old.yaml", lenientMode = true).toFeature()
        val newSpec = OpenApiSpecification.fromYAML(passwordRecoverySpec("Sets a new password for a staff account."), "new.yaml", lenientMode = true).toFeature()

        val oldPassword = passwordPatternFrom(oldSpec)
        val newPassword = passwordPatternFrom(newSpec)
        assertThat(oldPassword.regex).isEqualTo(passwordRegex)
        assertThat(newPassword.regex).isEqualTo(passwordRegex)

        val generatedPassword = oldPassword.regExSpec.generateShortestStringOrRandom(5)
        assertThat(
            oldPassword.matches(
                StringValue(generatedPassword),
                oldSpec.scenarios.first().resolver,
            ).isSuccess()
        )
            .withFailMessage("old spec generated $generatedPassword which does not match $passwordRegex")
            .isTrue

        val records = OpenApiBackwardCompatibilityChecker(oldSpec, newSpec).run()
        val failures = records.map { it.compatResult }.filterIsInstance<Result.Failure>()

        assertThat(failures.map { it.reportString() }).isEmpty()
    }

    private fun passwordPatternFrom(feature: Feature): StringPattern {
        val scenario = feature.scenarios.first()
        val body = resolvedHop(scenario.httpRequestPattern.body, scenario.resolver) as JSONObjectPattern
        return body.pattern.getValue("password") as StringPattern
    }

    private fun passwordRecoverySpec(pathDescription: String): String {
        return """
        openapi: 3.0.3
        info:
          title: Pet Shelter API
          version: 1.0.0
        paths:
          /shelters/{shelterId}/staff/password-recovery:
            post:
              description: $pathDescription
              parameters:
                - name: shelterId
                  in: path
                  required: true
                  schema:
                    type: string
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      ${'$'}ref: '#/components/schemas/StaffPasswordRecoveryRequest'
              responses:
                '204':
                  description: Shelter account password reset successfully
        components:
          schemas:
            StaffPasswordRecoveryRequest:
              type: object
              required:
                - password
              properties:
                password:
                  type: string
                  minLength: 5
                  maxLength: 401
                  pattern: '^(?=.*[A-Z])(?=.*[a-z])(?=.*\d)(?=.*[^A-Za-z0-9]).*$'
        """.trimIndent()
    }
}
