package io.specmatic.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.flipkart.zjsonpatch.JsonPatch
import io.specmatic.conversions.OpenApiSpecification
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckOpenApiBackwardCompatibilityTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("compatibleChanges")
    fun `should be backward compatible`(testCase: CompatTestCase) {
        val results = runCompatCheck(testCase)
        assertThat(results.success()).withFailMessage { results.report() }.isTrue
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("breakingChanges")
    fun `should be backward incompatible`(testCase: IncompatibleTestCase) {
        val results = runCompatCheck(testCase)
        assertThat(results.success()).isFalse
        assertThat(results.report()).isEqualToNormalizingWhitespace(testCase.expectedReport)
    }

    private fun runCompatCheck(testCase: CompatTestCase): Results {
        val oldSpec = testCase.oldPatch?.let { testCase.baseSpec.applyJsonPatch(it) } ?: testCase.baseSpec
        val newSpec = testCase.newPatch?.let { testCase.baseSpec.applyJsonPatch(it) } ?: testCase.baseSpec
        val older = OpenApiSpecification.fromYAML(oldSpec, "").toFeature()
        val newer = OpenApiSpecification.fromYAML(newSpec, "").toFeature()
        return testBackwardCompatibility(older, newer)
    }

    private fun String.applyJsonPatch(patch: String): String {
        val specNode = yamlMapper.readTree(this)
        val patchNode = yamlMapper.readTree(patch.trimIndent())
        val patched = JsonPatch.apply(patchNode, specNode)
        return yamlMapper.writeValueAsString(patched).trim()
    }

    open class CompatTestCase(
        val name: String,
        val baseSpec: String,
        val oldPatch: String? = null,
        val newPatch: String? = null
    ) {
        override fun toString(): String = name
    }

    class IncompatibleTestCase(
        name: String,
        baseSpec: String,
        oldPatch: String? = null,
        newPatch: String? = null,
        val expectedReport: String
    ) : CompatTestCase(name, baseSpec, oldPatch, newPatch)

    private fun compatibleChanges(): Stream<CompatTestCase> = Stream.of(
        CompatTestCase(
            name = "adding an optional key to the request body",
            baseSpec = baseSpec,
            newPatch = """
                - op: add
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/properties/extra
                  value:
                    type: string
            """
        ),
        CompatTestCase(
            name = "removing a mandatory key from the request body",
            baseSpec = baseSpec,
            oldPatch = """
                - op: add
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/properties/extra
                  value:
                    type: string
                - op: add
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/required/-
                  value: extra
            """
        ),
        CompatTestCase(
            name = "removing an optional key from the request body",
            baseSpec = baseSpec,
            oldPatch = """
                - op: add
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/properties/extra
                  value:
                    type: string
            """
        ),
        CompatTestCase(
            name = "making a mandatory key optional in the request body",
            baseSpec = baseSpec,
            newPatch = """
                - op: replace
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/required
                  value: []
            """
        ),
        CompatTestCase(
            name = "adding a mandatory field to the response body",
            baseSpec = baseSpec,
            newPatch = """
                - op: add
                  path: /paths/~1data/post/responses/200/content/application~1json/schema/properties/extra
                  value:
                    type: string
                - op: add
                  path: /paths/~1data/post/responses/200/content/application~1json/schema/required/-
                  value: extra
            """
        ),
        CompatTestCase(
            name = "adding an optional field to the response body",
            baseSpec = baseSpec,
            newPatch = """
                - op: add
                  path: /paths/~1data/post/responses/200/content/application~1json/schema/properties/extra
                  value:
                    type: string
            """
        ),
        CompatTestCase(
            name = "removing an optional field from the response body",
            baseSpec = baseSpec,
            oldPatch = """
                - op: add
                  path: /paths/~1data/post/responses/200/content/application~1json/schema/properties/extra
                  value:
                    type: string
            """
        ),
        CompatTestCase(
            name = "making an optional field mandatory in the response body",
            baseSpec = baseSpec,
            newPatch = """
                - op: add
                  path: /paths/~1data/post/responses/200/content/application~1json/schema/required/-
                  value: note
            """
        ),
    )

    private fun breakingChanges(): Stream<IncompatibleTestCase> = Stream.of(
        IncompatibleTestCase(
            name = "adding a mandatory key to the request body",
            baseSpec = baseSpec,
            newPatch = """
                - op: add
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/properties/extra
                  value:
                    type: string
                - op: add
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/required/-
                  value: extra
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.BODY.extra

                      R2001: Missing required property
                      Documentation: https://docs.specmatic.io/rules#r2001
                      Summary: A required property defined in the specification is missing

                      New specification expects property "extra" in the request but it is missing from the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "making an optional key mandatory in the request body",
            baseSpec = baseSpec,
            newPatch = """
                - op: add
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/required/-
                  value: note
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.BODY.note

                      R2001: Missing required property
                      Documentation: https://docs.specmatic.io/rules#r2001
                      Summary: A required property defined in the specification is missing

                      New specification expects property "note" in the request but it is missing from the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "removing a mandatory field from the response body",
            baseSpec = baseSpec,
            oldPatch = """
                - op: add
                  path: /paths/~1data/post/responses/200/content/application~1json/schema/properties/extra
                  value:
                    type: string
                - op: add
                  path: /paths/~1data/post/responses/200/content/application~1json/schema/required/-
                  value: extra
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> RESPONSE.BODY.extra

                      R2001: Missing required property
                      Documentation: https://docs.specmatic.io/rules#r2001
                      Summary: A required property defined in the specification is missing

                      The old specification expects property "extra" but it is missing in the new specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "making a mandatory field optional in the response body",
            baseSpec = baseSpec,
            newPatch = """
                - op: replace
                  path: /paths/~1data/post/responses/200/content/application~1json/schema/required
                  value: []
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> RESPONSE.BODY.id

                      R2001: Missing required property
                      Documentation: https://docs.specmatic.io/rules#r2001
                      Summary: A required property defined in the specification is missing

                      The old specification expects property "id" but it is missing in the new specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "changing a field type in the request body",
            baseSpec = baseSpec,
            newPatch = """
                - op: replace
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/properties/id/type
                  value: number
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.BODY.id

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is type number in the new specification, but type string in the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "adding a required query parameter",
            baseSpec = baseSpecWithParamsAndHeaders,
            newPatch = """
                - op: add
                  path: /paths/~1data/post/parameters/-
                  value:
                    name: extra
                    in: query
                    required: true
                    schema:
                      type: string
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.PARAMETERS.QUERY.extra

                      R2001: Missing required property
                      Documentation: https://docs.specmatic.io/rules#r2001
                      Summary: A required property defined in the specification is missing

                      New specification expects query param "extra" in the request but it is missing from the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "making an optional query parameter required",
            baseSpec = baseSpecWithParamsAndHeaders,
            newPatch = """
                - op: replace
                  path: /paths/~1data/post/parameters/1/required
                  value: true
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.PARAMETERS.QUERY.tag

                      R2001: Missing required property
                      Documentation: https://docs.specmatic.io/rules#r2001
                      Summary: A required property defined in the specification is missing

                      New specification expects query param "tag" in the request but it is missing from the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "changing the type of a query parameter",
            baseSpec = baseSpecWithParamsAndHeaders,
            newPatch = """
                - op: replace
                  path: /paths/~1data/post/parameters/0/schema/type
                  value: number
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.PARAMETERS.QUERY.q

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is type number in the new specification, but type string in the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "adding a required request header",
            baseSpec = baseSpecWithParamsAndHeaders,
            newPatch = """
                - op: add
                  path: /paths/~1data/post/parameters/-
                  value:
                    name: X-Extra
                    in: header
                    required: true
                    schema:
                      type: string
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.PARAMETERS.HEADER.X-Extra

                      R2001: Missing required property
                      Documentation: https://docs.specmatic.io/rules#r2001
                      Summary: A required property defined in the specification is missing

                      New specification expects header "X-Extra" in the request but it is missing from the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "making an optional request header required",
            baseSpec = baseSpecWithParamsAndHeaders,
            newPatch = """
                - op: replace
                  path: /paths/~1data/post/parameters/3/required
                  value: true
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.PARAMETERS.HEADER.X-Optional

                      R2001: Missing required property
                      Documentation: https://docs.specmatic.io/rules#r2001
                      Summary: A required property defined in the specification is missing

                      New specification expects header "X-Optional" in the request but it is missing from the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "changing the type of a request header",
            baseSpec = baseSpecWithParamsAndHeaders,
            newPatch = """
                - op: replace
                  path: /paths/~1data/post/parameters/2/schema/type
                  value: number
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.PARAMETERS.HEADER.X-Required

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is type number in the new specification, but type string in the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "changing the type of a response header",
            baseSpec = baseSpecWithParamsAndHeaders,
            newPatch = """
                - op: replace
                  path: /paths/~1data/post/responses/200/headers/X-Resp/schema/type
                  value: number
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> RESPONSE.HEADER.X-Resp

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is number in the new specification response but string in the old specification
            """.trimIndent()
        ),
    )

    companion object {
        private val yamlMapper = ObjectMapper(YAMLFactory())

        private val baseSpec = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data:
                post:
                  summary: submit data
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - id
                          properties:
                            id:
                              type: string
                            note:
                              type: string
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: string
                              note:
                                type: string
        """.trimIndent()

        private val baseSpecWithParamsAndHeaders = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data:
                post:
                  summary: submit data
                  parameters:
                    - name: q
                      in: query
                      required: true
                      schema:
                        type: string
                    - name: tag
                      in: query
                      required: false
                      schema:
                        type: string
                    - name: X-Required
                      in: header
                      required: true
                      schema:
                        type: string
                    - name: X-Optional
                      in: header
                      required: false
                      schema:
                        type: string
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - id
                          properties:
                            id:
                              type: string
                  responses:
                    '200':
                      description: ok
                      headers:
                        X-Resp:
                          schema:
                            type: string
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: string
        """.trimIndent()
    }
}
