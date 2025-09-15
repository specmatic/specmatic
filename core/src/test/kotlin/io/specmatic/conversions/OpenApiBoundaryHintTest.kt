package io.specmatic.conversions

import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.NumberPattern
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class OpenApiBoundaryHintTest {
    @Test
    fun `x-specmatic-hint enables boundary testing only where present`() {
        val spec = OpenApiSpecification.fromYAML(
            """
                openapi: 3.0.1
                info:
                  title: Test
                  version: 1
                components:
                  schemas:
                    Person:
                      type: object
                      required: [id, age]
                      properties:
                        id:
                          type: integer
                        age:
                          type: integer
                          x-specmatic-hint: boundary_testing_enabled
                paths: {}
            """.trimIndent(),
            ""
        )

        val schemas = spec.parseUnreferencedSchemas()
        val person = schemas["(Person)"] as JSONObjectPattern

        val idPattern = person.pattern.getValue("id") as NumberPattern
        val agePattern = person.pattern.getValue("age") as NumberPattern

        assertThat(idPattern.boundaryTestingEnabled).isFalse()
        assertThat(agePattern.boundaryTestingEnabled).isTrue()
    }
}

