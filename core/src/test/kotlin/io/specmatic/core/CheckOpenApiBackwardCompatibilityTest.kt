package io.specmatic.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.flipkart.zjsonpatch.JsonPatch
import io.specmatic.conversions.OpenApiSpecification
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
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

    @Test
    fun `all breaking changes from the fixture pair are detected and annotated`() {
        val oldSpec = File("src/test/resources/openapi/bcc-breaking-changes/old.yaml").readText()
        val newSpec = File("src/test/resources/openapi/bcc-breaking-changes/new.yaml").readText()

        val older = OpenApiSpecification.fromYAML(oldSpec, "old.yaml").toFeature()
        val newer = OpenApiSpecification.fromYAML(newSpec, "new.yaml").toFeature()

        val results = testBackwardCompatibility(older, newer)

        assertThat(results.success()).isFalse
        assertThat(results.distinctReport()).isEqualToNormalizingNewlines(
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
      
      >> RESPONSE.BODY.extra (new.yaml:102:15)
      
          R2001: Missing required property
          Documentation: https://docs.specmatic.io/rules#r2001
          Summary: A required property defined in the specification is missing
      
          The old specification expects property "extra" but it is missing in the new specification
      
      >> RESPONSE.BODY.code (new.yaml:117:19)
      
          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification
      
          This is number in the new specification response but string in the old specification
    
    In scenario "foo. Response: ok"
    API: GET /foo -> 200
    
          This API exists in the old contract but not in the new contract
    
    In scenario "bar. Response: ok"
    API: GET /bar -> 200
    
          This API exists in the old contract but not in the new contract
    
    In scenario "create baz. Response: ok"
    API: POST /baz -> 200
    
          This API exists in the old contract but not in the new contract
    
    In scenario "register pet. Response: ok"
    API: POST /pets -> 200
    
      >> REQUEST.BODY (when Dog object).species (new.yaml:211:9)
      
          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification
      
          This is type number in the new specification, but type string in the old specification
      
      >> REQUEST.BODY (when Dog object).sound (new.yaml:226:13)
      
          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification
      
          This is type number in the new specification, but type string in the old specification
      
      >> REQUEST.BODY (when Cat object).species (new.yaml:211:9)
      
          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification
      
          This is type number in the new specification, but type string in the old specification
      
      >> REQUEST.BODY (when Cat object).sound (new.yaml:237:13)
      
          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification
      
          This is type boolean in the new specification, but type string in the old specification
    
    In scenario "submit. Response: ok"
    API: POST /submissions -> 200
    
      >> REQUEST.BODY.id (new.yaml:196:9)
      
          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification
      
          This is type number in the new specification, but type string in the old specification
      
      >> REQUEST.BODY.kind (new.yaml:199:9)
      
          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification
      
          This is type string in the new specification, but type number in the old specification
      
      >> REQUEST.BODY.category (new.yaml:203:9)
      
          R1002: Value mismatch
          Documentation: https://docs.specmatic.io/rules#r1002
          Summary: The value does not match the expected value defined in the specification
      
          This is ("A") in the new specification, but "B" in the old specification
      
      >> RESPONSE.BODY.status (new.yaml:185:19)
      
          R1001: Type mismatch
          Documentation: https://docs.specmatic.io/rules#r1001
          Summary: The value type does not match the expected type defined in the specification
      
          This is ("accepted" or "pending") in the new specification response but ("accepted") in the old specification
            """.trimIndent()
        )
    }

    private fun runCompatCheck(testCase: CompatTestCase): Results {
        val oldSpec = testCase.oldPatch?.let { testCase.baseSpec.applyJsonPatch(it) } ?: testCase.baseSpec
        val newSpec = testCase.newPatch?.let { testCase.baseSpec.applyJsonPatch(it) } ?: testCase.baseSpec
        val older = OpenApiSpecification.fromYAML(oldSpec, "old.yaml").toFeature()
        val newer = OpenApiSpecification.fromYAML(newSpec, "new.yaml").toFeature()
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

                  >> REQUEST.BODY.extra (new.yaml:24:17)

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

                  >> REQUEST.BODY.note (new.yaml:22:17)

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

                  >> RESPONSE.BODY.extra (new.yaml:27:15)

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

                  >> RESPONSE.BODY.id (new.yaml:32:19)

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

                  >> REQUEST.BODY.id (new.yaml:19:17)

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

                  >> REQUEST.PARAMETERS.QUERY.extra (new.yaml:31:9)

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

                  >> REQUEST.PARAMETERS.QUERY.tag (new.yaml:16:9)

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

                  >> REQUEST.PARAMETERS.QUERY.q (new.yaml:11:9)

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

                  >> REQUEST.PARAMETERS.HEADER.X-Extra (new.yaml:31:9)

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

                  >> REQUEST.PARAMETERS.HEADER.X-Optional (new.yaml:26:9)

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

                  >> REQUEST.PARAMETERS.HEADER.X-Required (new.yaml:21:9)

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

                  >> RESPONSE.HEADER.X-Resp (new.yaml:46:13)

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is number in the new specification response but string in the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "changing the type of a nested inline object property",
            baseSpec = baseSpec,
            oldPatch = """
                - op: add
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/properties/address
                  value:
                    type: object
                    required:
                      - street
                    properties:
                      street:
                        type: string
            """,
            newPatch = """
                - op: add
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/properties/address
                  value:
                    type: object
                    required:
                      - street
                    properties:
                      street:
                        type: number
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.BODY.address.street (new.yaml:28:21)

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is type number in the new specification, but type string in the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "changing the type of an array item property",
            baseSpec = baseSpec,
            oldPatch = """
                - op: add
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/properties/tags
                  value:
                    type: array
                    items:
                      type: object
                      required:
                        - name
                      properties:
                        name:
                          type: string
            """,
            newPatch = """
                - op: add
                  path: /paths/~1data/post/requestBody/content/application~1json/schema/properties/tags
                  value:
                    type: array
                    items:
                      type: object
                      required:
                        - name
                      properties:
                        name:
                          type: number
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.BODY.tags[0].name (new.yaml:30:23)

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is type number in the new specification, but type string in the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "changing the type of a property inherited via allOf from another component",
            baseSpec = baseSpec,
            oldPatch = """
                - op: add
                  path: /components
                  value:
                    schemas:
                      Identifiable:
                        type: object
                        required:
                          - ref
                        properties:
                          ref:
                            type: string
                - op: replace
                  path: /paths/~1data/post/requestBody/content/application~1json/schema
                  value:
                    allOf:
                      - ${'$'}ref: '#/components/schemas/Identifiable'
                      - type: object
                        required:
                          - id
                        properties:
                          id:
                            type: string
            """,
            newPatch = """
                - op: add
                  path: /components
                  value:
                    schemas:
                      Identifiable:
                        type: object
                        required:
                          - ref
                        properties:
                          ref:
                            type: number
                - op: replace
                  path: /paths/~1data/post/requestBody/content/application~1json/schema
                  value:
                    allOf:
                      - ${'$'}ref: '#/components/schemas/Identifiable'
                      - type: object
                        required:
                          - id
                        properties:
                          id:
                            type: string
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                  >> REQUEST.BODY.ref (new.yaml:14:13)

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is type number in the new specification, but type string in the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "removing an existing path",
            baseSpec = baseSpecWithPaths,
            newPatch = """
                - op: remove
                  path: /paths/~1data
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                      This API exists in the old contract but not in the new contract

                In scenario "list data. Response: ok"
                API: GET /data -> 200

                      This API exists in the old contract but not in the new contract
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "removing an HTTP method on an existing path",
            baseSpec = baseSpecWithPaths,
            newPatch = """
                - op: remove
                  path: /paths/~1data/post
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                      This API exists in the old contract but not in the new contract
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "renaming a path",
            baseSpec = baseSpecWithPaths,
            newPatch = """
                - op: move
                  from: /paths/~1data
                  path: /paths/~1data-v2
            """,
            expectedReport = """
                In scenario "submit data. Response: ok"
                API: POST /data -> 200

                      This API exists in the old contract but not in the new contract

                In scenario "list data. Response: ok"
                API: GET /data -> 200

                      This API exists in the old contract but not in the new contract
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "changing the type of a path parameter",
            baseSpec = baseSpecWithPaths,
            newPatch = """
                - op: replace
                  path: /paths/~1items~1{id}/get/parameters/0/schema/type
                  value: integer
            """,
            expectedReport = """
                In scenario "get item. Response: ok"
                API: GET /items/(id:number) -> 200

                  >> REQUEST.PARAMETERS.PATH.id (new.yaml:51:9)

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is type number in the new specification, but type string in the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "narrowing an enum in the request body",
            baseSpec = baseSpecWithComposition,
            newPatch = """
                - op: remove
                  path: /components/schemas/Submission/properties/category/enum/1
            """,
            expectedReport = """
                In scenario "submit. Response: ok"
                API: POST /submissions -> 200

                  >> REQUEST.BODY.category (new.yaml:45:9)

                      R1002: Value mismatch
                      Documentation: https://docs.specmatic.io/rules#r1002
                      Summary: The value does not match the expected value defined in the specification

                      This is ("A") in the new specification, but "B" in the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "widening an enum in the response body",
            baseSpec = baseSpecWithComposition,
            newPatch = """
                - op: add
                  path: /paths/~1submissions/post/responses/200/content/application~1json/schema/properties/status/enum/-
                  value: pending
            """,
            expectedReport = """
                In scenario "submit. Response: ok"
                API: POST /submissions -> 200

                  >> RESPONSE.BODY.status (new.yaml:26:19)

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is ("accepted" or "pending") in the new specification response but ("accepted") in the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "changing a property type inside a shared \$ref schema",
            baseSpec = baseSpecWithComposition,
            newPatch = """
                - op: replace
                  path: /components/schemas/Submission/properties/id/type
                  value: number
            """,
            expectedReport = """
                In scenario "submit. Response: ok"
                API: POST /submissions -> 200

                  >> REQUEST.BODY.id (new.yaml:39:9)

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is type number in the new specification, but type string in the old specification
            """.trimIndent()
        ),
        // TODO: Test temporarily disabled because we can't reliably match this output until the changes
        // in https://github.com/specmatic/specmatic/pull/2486 land that makes sure that only 1 item per
        // array is generated
//        IncompatibleTestCase(
//            name = "adding a required field inside an array item schema in the request body",
//            baseSpec = baseSpecWithComposition,
//            newPatch = """
//                - op: add
//                  path: /components/schemas/Submission/properties/items/items/required
//                  value:
//                    - name
//            """,
//            expectedReport = """
//                In scenario "submit. Response: ok"
//                API: POST /submissions -> 200
//
//                  >> REQUEST.BODY.items[0].name
//
//                      R2001: Missing required property
//                      Documentation: https://docs.specmatic.io/rules#r2001
//                      Summary: A required property defined in the specification is missing
//
//                      New specification expects property "name" in the request but it is missing from the old specification
//
//                  >> REQUEST.BODY.items[1].name
//
//                      R2001: Missing required property
//                      Documentation: https://docs.specmatic.io/rules#r2001
//                      Summary: A required property defined in the specification is missing
//
//                      New specification expects property "name" in the request but it is missing from the old specification
//            """.trimIndent()
//        ),
        IncompatibleTestCase(
            name = "adding a required property inside a shared \$ref schema",
            baseSpec = baseSpecWithComposition,
            newPatch = """
                - op: add
                  path: /components/schemas/Submission/properties/note
                  value:
                    type: string
                - op: add
                  path: /components/schemas/Submission/required/-
                  value: note
            """,
            expectedReport = """
                In scenario "submit. Response: ok"
                API: POST /submissions -> 200

                  >> REQUEST.BODY.note (new.yaml:58:9)

                      R2001: Missing required property
                      Documentation: https://docs.specmatic.io/rules#r2001
                      Summary: A required property defined in the specification is missing

                      New specification expects property "note" in the request but it is missing from the old specification
            """.trimIndent()
        ),
        IncompatibleTestCase(
            name = "removing a branch from a oneOf in the request body",
            baseSpec = baseSpecWithComposition,
            newPatch = """
                - op: remove
                  path: /components/schemas/Submission/properties/kind/oneOf/1
            """,
            expectedReport = """
                In scenario "submit. Response: ok"
                API: POST /submissions -> 200

                  >> REQUEST.BODY.kind (new.yaml:41:9)

                      R1001: Type mismatch
                      Documentation: https://docs.specmatic.io/rules#r1001
                      Summary: The value type does not match the expected type defined in the specification

                      This is type string in the new specification, but type number in the old specification
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

        private val baseSpecWithPaths = """
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /data:
                get:
                  summary: list data
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
              /items/{id}:
                get:
                  summary: get item
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
                            required:
                              - id
                            properties:
                              id:
                                type: string
        """.trimIndent()

        private val baseSpecWithComposition = $$"""
            openapi: 3.0.0
            info:
              title: Sample API
              version: 0.1.9
            paths:
              /submissions:
                post:
                  summary: submit
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/Submission'
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - status
                            properties:
                              status:
                                type: string
                                enum:
                                  - accepted
            components:
              schemas:
                Submission:
                  type: object
                  required:
                    - id
                    - kind
                    - category
                  properties:
                    id:
                      type: string
                    kind:
                      oneOf:
                        - type: string
                        - type: number
                    category:
                      type: string
                      enum:
                        - A
                        - B
                    items:
                      type: array
                      items:
                        type: object
                        properties:
                          name:
                            type: string
        """.trimIndent()
    }
}
