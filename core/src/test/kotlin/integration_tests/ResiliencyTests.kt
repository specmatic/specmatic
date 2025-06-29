package integration_tests

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.specmatic.GENERATION
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.AttributeSelectionPattern
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Results
import io.specmatic.core.Scenario
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.Flags.Companion.ONLY_POSITIVE
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_GENERATIVE_TESTS
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

internal val Results.testCount: Int
    get() {
        return this.successCount + this.failureCount
    }

@Tag(GENERATION)
class GenerativeTests {

    @BeforeEach
    fun setup() {
        unmockkAll()
    }

    @Test
    fun `generative tests for enums when an example is provided`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: Create person record
                  requestBody:
                    content:
                      application/json:
                        examples:
                          CREATE_PERSON:
                            value:
                              status: "active"
                        schema:
                          required:
                          - status
                          properties:
                            status:
                              type: string
                              enum:
                                - active
                                - inactive
                  responses:
                    200:
                      description: Person record created
                      content:
                        text/plain:
                          schema:
                            type: "string"
                          examples:
                            CREATE_PERSON:
                              value:
                                "Person record created"
            """.trimIndent(), ""
        ).toFeature()

        val statusesSeen = mutableSetOf<String>()

        feature.enableGenerativeTesting().executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val body = request.body as JSONObjectValue
                println(body.toStringLiteral())

                when (body.jsonObject["status"]!!) {
                    is StringValue -> statusesSeen.add(body.jsonObject["status"]!!.toStringLiteral())
                    else -> statusesSeen.add(body.jsonObject["status"]!!.displayableType())
                }

                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(statusesSeen).isEqualTo(setOf("active", "inactive", "null", "boolean", "number"))
    }

    @Test
    fun `generative tests for an optional key given a payload example without the optional key`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: Create person record
                  requestBody:
                    required: true
                    content:
                      application/json:
                        examples:
                          CREATE_PERSON:
                            value:
                              name: "John Doe"
                        schema:
                          required:
                          - name
                          properties:
                            name:
                              type: string
                            description:
                              type: string
                  responses:
                    200:
                      description: Person record created
                      content:
                        text/plain:
                          schema:
                            type: "string"
                          examples:
                            CREATE_PERSON:
                              value:
                                "Person record created"
            """.trimIndent(), ""
        ).toFeature()

        val fromExample = 1
        val positiveGenerated = 1
        val negativeGenerativeAll = 3 + 3
        val negativeGenerativeNothing = 3

        var optionalKeyOccurrence = 0

        val OPTIONAL_KEY = "description"

        try {
            val results = try {
                feature.enableGenerativeTesting().executeTests(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        val jsonRequestBody = request.body as JSONObjectValue

                        if(OPTIONAL_KEY in jsonRequestBody.jsonObject)
                            optionalKeyOccurrence += 1

                        return HttpResponse.OK
                    }

                    override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                        println(scenario.testDescription())
                        println(request.toLogString())
                        println()
                    }
                })
            } finally {
                System.clearProperty(ONLY_POSITIVE)
            }

            assertThat(optionalKeyOccurrence).isEqualTo(7)

            val expectedCountOfTests =
                fromExample + positiveGenerated + negativeGenerativeAll + negativeGenerativeNothing

            assertThat(results.results).hasSize(expectedCountOfTests)
        } catch (e: ContractException) {
            println(e.report())

            throw e
        }
    }

    @Test
    fun `generative tests for headers`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: Create person record
                  parameters:
                    - name: X-Request-Id
                      in: header
                      required: true
                      schema:
                        type: integer
                      examples:
                        CREATE_PERSON:
                          value: 987
                  requestBody:
                    required: true
                    content:
                      application/json:
                        examples:
                          CREATE_PERSON:
                            value:
                              name: "John Doe"
                        schema:
                          required:
                          - name
                          properties:
                            name:
                              type: string
                  responses:
                    200:
                      description: Person record created
                      content:
                        text/plain:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: integer
                          examples:
                            CREATE_PERSON:
                              value:
                                id: 123
            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(7)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }

    @Test
    fun `generative tests for headers that are optional`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: Create person record
                  parameters:
                    - name: X-Request-Id
                      in: header
                      schema:
                        type: integer
                      examples:
                        CREATE_PERSON:
                          value: 987
                  requestBody:
                    required: true
                    content:
                      application/json:
                        examples:
                          CREATE_PERSON:
                            value:
                              name: "John Doe"
                        schema:
                          required:
                          - name
                          properties:
                            name:
                              type: string
                  responses:
                    200:
                      description: Person record created
                      content:
                        text/plain:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: integer
                          examples:
                            CREATE_PERSON:
                              value:
                                id: 123
            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(7)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }

    @Test
    fun `handle request payloads with the same key in header and request payload with different types`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Result
              version: 1.0.0
            paths:
              /result:
                post:
                  parameters:
                    - name: status
                      in: header
                      schema:
                        type: boolean
                      examples:
                        SUCCESS:
                          value: true
                  requestBody:
                    required: true
                    content:
                      application/json:
                        examples:
                          SUCCESS:
                            value:
                              status: success
                        schema:
                          type: object
                          required:
                            - status
                          properties:
                            status:
                              type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            SUCCESS:
                              value: OK
        """.trimIndent(), ""
        ).toFeature()

        val statusValuesSeen = mutableSetOf<String>()

        val results = feature.enableGenerativeTesting(onlyPositive = true).executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val body = request.body as JSONObjectValue
                println(body.toStringLiteral())
                statusValuesSeen.add(body.findFirstChildByPath("status")!!.toStringLiteral() + " in body")
                statusValuesSeen.add(request.headers["status"]?.let { "$it in headers" } ?: "status not in headers")

                return HttpResponse.ok("OK")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        if (results.failureCount > 0)
            println(results.report())

        assertThat(results.failureCount).isEqualTo(0)
        assertThat(results.successCount).isGreaterThan(0)

        assertThat(statusValuesSeen).containsExactlyInAnyOrder(
            "true in headers",
            "status not in headers",
            "success in body"
        )
    }

    @Test
    fun `generative mandatory tests for query params with an example`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: id
                      in: query
                      required: true
                      schema:
                        type: integer
                      examples:
                        DETAILS:
                          value: 987
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                          examples:
                            DETAILS:
                              value:
                                id: 123
                                name: "John Doe"
            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(4)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }

    @Test
    fun `generative tests for mandatory query params with no examples`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: id
                      in: query
                      required: true
                      schema:
                        type: integer
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(4)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }

    @Test
    fun `generative tests for optional query params with no examples`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: id
                      in: query
                      required: false
                      schema:
                        type: integer
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(4)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }

    @Test
    fun `generative tests with one optional and one mandatory query param with no examples`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: category
                      in: query
                      required: true
                      schema:
                        type: integer
                    - name: status
                      in: query
                      required: false
                      schema:
                        type: integer
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(9)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }

    @Test
    fun `generative tests with one optional query param with no example and one mandatory query param with an example`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: category
                      in: query
                      required: false
                      schema:
                        type: integer
                    - name: status
                      in: query
                      required: true
                      schema:
                        type: integer
                      examples:
                        FETCH:
                          value: 10
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                          examples:
                            FETCH:
                              value:
                                id: 123
                                name: "John Doe"
            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(9)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }


    @Test
    fun `generative tests with one optional header with no example and one mandatory header with an example`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: X-Category
                      in: header
                      required: false
                      schema:
                        type: integer
                    - name: X-Status
                      in: header
                      required: true
                      schema:
                        type: integer
                      examples:
                        FETCH:
                          value: 10
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                          examples:
                            FETCH:
                              value:
                                id: 123
                                name: "John Doe"
            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(9)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }

    @Test
    fun `generative tests with one optional query param that is an enum and has no examples`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Products API"
              version: "1"
            paths:
              /products:
                get:
                  summary: GET Products based on type
                  parameters:
                    - name: type
                      in: query
                      schema:
                        type: string
                        enum:
                          - gadget
                          - book
                  responses:
                    "200":
                      description: List of products in the response
                      content:
                        text/plain:
                          schema:
                            type: string

            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(5)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }

    @Test
    fun `generative tests with one optional query param that is an enum and has an example`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: category
                      in: query
                      schema:
                        type: string
                        enum:
                          - active
                          - inactive
                      examples:
                        FETCH:
                          value: active
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                          examples:
                            FETCH:
                              value:
                                id: 123
                                name: "John Doe"
            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(5)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }

    @Test
    fun `generative tests with one optional header that is an enum and has no examples`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Products API"
              version: "1"
            paths:
              /products:
                get:
                  summary: GET Products based on type
                  parameters:
                    - name: type
                      in: header
                      schema:
                        type: string
                        enum:
                          - gadget
                          - book
                  responses:
                    "200":
                      description: List of products in the response
                      content:
                        text/plain:
                          schema:
                            type: string

            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(5)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }

    @Test
    fun `generative tests with one optional header that is an enum and has an example`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: category
                      in: header
                      schema:
                        type: string
                        enum:
                          - active
                          - inactive
                      examples:
                        FETCH:
                          value: active
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                          examples:
                            FETCH:
                              value:
                                id: 123
                                name: "John Doe"
            """.trimIndent(), ""
        ).toFeature()

        try {
            val results = runGenerativeTests(feature)
            assertThat(results.results).hasSize(5)
        } catch (e: ContractException) {
            println(e.report())
            throw e
        }
    }

    @Test
    fun `generative tests should correctly generate values of the right type where the same key appears with different types in the request payload`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Addresses
              version: 1.0.0
            paths:
              /addresses:
                post:
                  requestBody:
                    content:
                      application/json:
                        examples:
                          SUCCESS:
                            value:
                              person:
                                address:
                                  building: 1
                              company:
                                address:
                                  building: "Bldg no 1"
                        schema:
                          type: object
                          required:
                            - person
                            - company
                          properties:
                            person:
                              type: object
                              required:
                                - address
                              properties:
                                address:
                                  type: object
                                  required:
                                    - building
                                  properties:
                                    building:
                                      type: integer
                            company:
                              type: object
                              required:
                                - address
                              properties:
                                address:
                                  type: object
                                  required:
                                    - building
                                  properties:
                                    building:
                                      type: string
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            SUCCESS:
                              value: OK
                    '400':
                      description: Bad Request
                      content:
                        text/plain:
                          schema:
                            type: string
        """.trimIndent(), ""
        ).toFeature()

        val buildingValuesSeen = mutableSetOf<String>()

        try {
            feature.enableGenerativeTesting().executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val body = request.body as JSONObjectValue
                    when (val personBuilding = body.findFirstChildByPath("person.address.building")!!) {
                        is NullValue -> buildingValuesSeen.add("null in person.address.building")
                        is NumberValue -> buildingValuesSeen.add("Value ${personBuilding.toStringLiteral()} in person.address.building")
                        else -> buildingValuesSeen.add("Value type ${personBuilding.displayableType()} in person.address.building")
                    }

                    when (val companyBuilding = body.findFirstChildByPath("company.address.building")!!) {
                        is NullValue -> buildingValuesSeen.add("null in company.address.building")
                        is StringValue -> buildingValuesSeen.add("Value ${companyBuilding.toStringLiteral()} in company.address.building")
                        else -> buildingValuesSeen.add("Value type ${companyBuilding.displayableType()} in company.address.building")
                    }

                    if (body.findFirstChildByPath("person.address.building")!! !is NumberValue ||
                        body.findFirstChildByPath("company.address.building")!! !is StringValue
                    )
                        return HttpResponse.ERROR_400

                    return HttpResponse.ok("OK")
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            })

            assertThat(buildingValuesSeen).containsExactlyInAnyOrder(
                "Value 1 in person.address.building",
                "null in person.address.building",
                "Value type boolean in person.address.building",
                "Value type string in person.address.building",
                "Value Bldg no 1 in company.address.building",
                "null in company.address.building",
                "Value type number in company.address.building",
                "Value type boolean in company.address.building"
            )
        } catch (e: ContractException) {
            Assertions.fail("Should not have got this error:\n${e.report()}")
        }
    }


    @Test
    fun `generative positive-only tests with REQUEST-BODY example`() {
        val specification = OpenApiSpecification.fromYAML(
            """
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: "Get person by id"
                  parameters: []
                  requestBody:
                    required: true
                    content:
                      application/json:
                        examples:
                          SUCCESS:
                            value:
                              address:
                                - street: "1"

                        schema:
                          required:
                          - "address"
                          properties:
                            address:
                              type: "array"
                              items:
                                ${'$'}ref: "#/components/schemas/Address"
                  responses:
                    200:
                      description: "Get person by id"
                      content:
                        text/plain:
                          schema:
                            type: "string"
                          examples:
                            SUCCESS:
                              value: success
            components:
              schemas:
                Address:
                  type: object
                  properties:
                    street:
                      type: "string"
        """, ""
        ).toFeature()

        val requestBodiesSeen = mutableListOf<Value>()

        val results = specification.enableGenerativeTesting(onlyPositive = true).executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.body)
                requestBodiesSeen.add(request.body)
                return HttpResponse.ok("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })
        println(results.report())

        assertThat(requestBodiesSeen).hasSize(2)
    }

    @Test
    fun `specification with 2 levels of depth having inline examples and only one of the keys optional`() {
        val feature = OpenApiSpecification.fromYAML(
            """
        openapi: 3.0.0
        info:
          title: Product API
          version: 1.0.0
        paths:
          /products:
            post:
              summary: Create a new product
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required:
                        - productDetails
                      properties:
                        productDetails:
                          type: object
                          required:
                            - productId
                          properties:
                            productId:
                              type: string
                              example: 'product123'
                        discountCoupons:
                          type: array
                          items:
                            type: string
                            example: 'coupon890'
              responses:
                '200':
                  description: Product created successfully
                  content:
                    text/plain:
                      schema:
                        type: string
                '400':
                  description: Invalid request payload
                '500':
                  description: Internal server error
    """.trimIndent(), ""
        ).toFeature()

        val seenRequestBodies = mutableListOf<Value>()

        val updatedFeature = feature.enableGenerativeTesting().enableSchemaExampleDefault()

        val results = updatedFeature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.body)
                seenRequestBodies.add(request.body)

                return HttpResponse.ok("success")
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(results.results).hasSize(11)
    }

    private fun runGenerativeTests(
        feature: Feature
    ): Results {
        try {
            return feature.enableGenerativeTesting().executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    println(request.toLogString())
                    return HttpResponse.OK
                }

                override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                    println(scenario.testDescription())
                }
            })
        } finally {
            System.clearProperty(ONLY_POSITIVE)
        }
    }

    @Test
    fun `the flag SPECMATIC_GENERATIVE_TESTS should be used`() {
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true) {
            every { isResiliencyTestingEnabled() } returns true
            every { getWorkflowDetails() } returns null
        }
        mockkObject(SpecmaticConfig.Companion)
        every { SpecmaticConfig.Companion.getAttributeSelectionPattern(any()) } returns AttributeSelectionPattern()

        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              version: 1.0.0
              title: Product API
              description: API for creating a product
            paths:
              /products:
                post:
                  summary: Create a product
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Product'
                  responses:
                    '200':
                      description: Product created successfully
                      content:
                        text/plain:
                          schema:
                            type: string
                    '400':
                      description: Bad request
                      content:
                        text/plain:
                          schema:
                            type: string
            components:
              schemas:
                Product:
                  type: object
                  required:
                    - name
                  properties:
                    name:
                      type: string
                      description: The name of the product
                      example: 'Soap'
                """, "",
            specmaticConfig = specmaticConfig
        ).toFeature()

        val testType = mutableListOf<String>()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val body = request.body as JSONObjectValue

                if (body.jsonObject["name"] !is StringValue) {
                    testType.add("name mutated to " + body.jsonObject["name"]!!.displayableType())
                    return HttpResponse.ERROR_400
                }

                testType.add("name not mutated")

                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {

            }
        })

        assertThat(testType).containsExactlyInAnyOrder(
            "name not mutated",
            "name mutated to null",
            "name mutated to boolean",
            "name mutated to number"
        )

        assertThat(results.failureCount).isEqualTo(0)

        assertThat(results.results).hasSize(testType.size)
    }

    @Test
    fun `the flag ONLY_POSITIVE should be used`() {
        try {
            val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true) {
                every { isResiliencyTestingEnabled() } returns true
                every { isOnlyPositiveTestingEnabled() } returns true
                every { getWorkflowDetails() } returns null
            }
            mockkObject(SpecmaticConfig.Companion)
            every { SpecmaticConfig.Companion.getAttributeSelectionPattern(any()) } returns AttributeSelectionPattern()


            val feature = OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.0
                info:
                  version: 1.0.0
                  title: Product API
                  description: API for creating a product
                paths:
                  /products:
                    post:
                      summary: Create a product
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              ${"$"}ref: '#/components/schemas/Product'
                            examples:
                              SUCCESS:
                                value:
                                  name: 'Soap'
                      responses:
                        '200':
                          description: Product created successfully
                          content:
                            text/plain:
                              schema:
                                type: string
                              examples:
                                SUCCESS:
                                  value: 'Product created successfully'
                components:
                  schemas:
                    Product:
                      type: object
                      required:
                        - name
                      properties:
                        name:
                          type: string
                          description: The name of the product
                          example: 'Soap'
                        price:
                          type: number
                          description: The price of the product
                    """, "",
                specmaticConfig = specmaticConfig
            ).toFeature()

            val testType = mutableListOf<String>()

            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val body = request.body as JSONObjectValue

                    if (body.jsonObject["name"] !is StringValue) {
                        testType.add("name mutated to " + body.jsonObject["name"]!!.displayableType())
                        return HttpResponse.ERROR_400
                    }

                    if ("price" in body.jsonObject && body.jsonObject["price"] !is NumberValue) {
                        testType.add("price mutated to " + body.jsonObject["price"]!!.displayableType())
                        return HttpResponse.ERROR_400
                    }

                    if ("price" in body.jsonObject)
                        testType.add("price is present")
                    else
                        testType.add("price is absent")

                    return HttpResponse.OK
                }

                override fun setServerState(serverState: Map<String, Value>) {

                }
            })

            assertThat(testType).containsExactlyInAnyOrder(
                "price is present", "price is absent"
            )

            assertThat(results.results).hasSize(testType.size)

            assertThat(results.failureCount).isEqualTo(0)

        } finally {
            System.clearProperty(SPECMATIC_GENERATIVE_TESTS)
            System.clearProperty(ONLY_POSITIVE)
        }
    }

    @Test
    fun `generative tests when the example goes 2 levels deep`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: Create person record
                  requestBody:
                    content:
                      application/json:
                        examples:
                          CREATE_PERSON:
                            value:
                              name: "John Doe"
                              address:
                                building:
                                  flat: 10
                                  name: "Mason Apartments"
                                street: "1st Street"
                        schema:
                          required:
                          - name
                          - address
                          properties:
                            name:
                              type: string
                            address:
                              type: object
                              properties:
                                building:
                                  type: object
                                  properties:
                                    flat:
                                      type: integer
                                    name:
                                      type: string
                                street:
                                  type: string
                                propertyType:
                                  type: string
                                  enum:
                                    - residential
                                    - commercial
                  responses:
                    200:
                      description: Person record created
                      content:
                        text/plain:
                          schema:
                            type: "string"
                          examples:
                            CREATE_PERSON:
                              value:
                                "Person record created"
                    400:
                      description: Bad Request
                      content:
                        text/plain:
                          schema:
                            type: "string"
            """.trimIndent(), ""
        ).toFeature()

        val notes = mutableListOf<String>()

        val results = feature.enableGenerativeTesting().executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val body = request.body as JSONObjectValue

                body.findFirstChildByPath("name")?.let {
                    if (it !is StringValue) {
                        notes.add("name mutated to ${it.displayableType()}")
                        return HttpResponse.ERROR_400
                    }

                    assertThat(it).isEqualTo(StringValue("John Doe"))
                }

                body.findFirstChildByPath("address.building.name")?.let {
                    if (it !is StringValue) {
                        notes.add("address.building.name mutated to ${it.displayableType()}")
                        return HttpResponse.ERROR_400
                    }

                    assertThat(it).isEqualTo(StringValue("Mason Apartments"))
                }

                body.findFirstChildByPath("address.building.flat")?.let {
                    if (it !is NumberValue) {
                        notes.add("address.building.flat mutated to ${it.displayableType()}")
                        return HttpResponse.ERROR_400
                    }

                    assertThat(it).isEqualTo(NumberValue(10))
                }

                body.findFirstChildByPath("address.street")?.let {
                    if (it !is StringValue) {
                        notes.add("address.street mutated to ${it.displayableType()}")
                        return HttpResponse.ERROR_400
                    }

                    assertThat(it).isEqualTo(StringValue("1st Street"))
                }

                body.findFirstChildByPath("address.propertyType")?.let {
                    if (it == StringValue("residential") || it == StringValue("commercial")) {
                        notes.add("address.propertyType is ${it.toStringLiteral()}")
                    } else {
                        notes.add("address.propertyType mutated to ${it.displayableType()}")
                        return HttpResponse.ERROR_400
                    }
                }

                body.findFirstChildByPath("address")?.let {
                    it as JSONObjectValue

                    if (it.jsonObject.isEmpty()) {
                        notes.add("address object is empty")
                    }
                }

                body.findFirstChildByPath("building")?.let {
                    it as JSONObjectValue

                    if (it.jsonObject.isEmpty()) {
                        notes.add("building object is empty")
                    }
                }

                notes.add("request matches the specification")

                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                println("Scenario: ${scenario.testDescription()} + ${scenario.httpResponsePattern.status}")
                println(request.toLogString())
            }
        })

        if (results.failureCount > 0)
            println(results.report())

        assertThat(notes.sorted()).isEqualTo(
            listOf(
                "address object is empty",
                "address.building.flat mutated to boolean",
                "address.building.flat mutated to boolean",
                "address.building.flat mutated to null",
                "address.building.flat mutated to null",
                "address.building.flat mutated to string",
                "address.building.flat mutated to string",
                "address.building.name mutated to boolean",
                "address.building.name mutated to boolean",
                "address.building.name mutated to null",
                "address.building.name mutated to null",
                "address.building.name mutated to number",
                "address.building.name mutated to number",
                "address.propertyType is commercial",
                "address.propertyType is commercial",
                "address.propertyType is residential",
                "address.propertyType is residential",
                "address.propertyType mutated to boolean",
                "address.propertyType mutated to boolean",
                "address.propertyType mutated to null",
                "address.propertyType mutated to null",
                "address.propertyType mutated to number",
                "address.propertyType mutated to number",
                "address.street mutated to boolean",
                "address.street mutated to boolean",
                "address.street mutated to boolean",
                "address.street mutated to boolean",
                "address.street mutated to null",
                "address.street mutated to null",
                "address.street mutated to null",
                "address.street mutated to null",
                "address.street mutated to number",
                "address.street mutated to number",
                "address.street mutated to number",
                "address.street mutated to number",
                "name mutated to boolean",
                "name mutated to boolean",
                "name mutated to boolean",
                "name mutated to boolean",
                "name mutated to boolean",
                "name mutated to null",
                "name mutated to null",
                "name mutated to null",
                "name mutated to null",
                "name mutated to null",
                "name mutated to number",
                "name mutated to number",
                "name mutated to number",
                "name mutated to number",
                "name mutated to number",
                "request matches the specification",
                "request matches the specification",
                "request matches the specification",
                "request matches the specification",
                "request matches the specification",
                "request matches the specification"
            ).sorted()
        )
    }

    @Test
    fun `generative tests for path params`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person/{id}:
                parameters:
                  - name: id
                    in: path
                    schema:
                      type: integer
                    examples:
                      CREATE_PERSON:
                        value: 100
                post:
                  summary: Create person record
                  responses:
                    200:
                      description: Person record created
                      content:
                        text/plain:
                          schema:
                            type: "string"
                          examples:
                            CREATE_PERSON:
                              value: "Person record created"
            """.trimIndent(), ""
        ).toFeature()

        val pathsSeen = mutableListOf<String>()

        val results = feature.enableGenerativeTesting().executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                pathsSeen.add(request.path!!)

                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(pathsSeen).withFailMessage(results.report()).satisfiesExactlyInAnyOrder(
            { assertThat(it).isEqualTo("/person/100") },
            { assertThat(it).matches("^/person/(false|true)") },
            { assertThat(it).matches("/person/[A-Z]+$") }
        )
    }

    @Test
    fun `generative tests for path params and a request body`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person/{id}:
                parameters:
                  - name: id
                    in: path
                    schema:
                      type: integer
                    examples:
                      CREATE_PERSON:
                        value: 100
                post:
                  summary: Create person record
                  requestBody:
                    content:
                      application/json:
                        examples:
                          CREATE_PERSON:
                            value:
                              name: "John Doe"
                        schema:
                          required:
                          - name
                          properties:
                            name:
                              type: string
                  responses:
                    200:
                      description: Person record created
                      content:
                        text/plain:
                          schema:
                            type: "string"
                          examples:
                            CREATE_PERSON:
                              value: "Person record created"
            """.trimIndent(), ""
        ).toFeature()

        val pathsSeen = mutableListOf<String>()

        val results = feature.enableGenerativeTesting().executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.toLogString())
                pathsSeen.add(request.path!!)
                assertThat(request.body).isInstanceOf(JSONObjectValue::class.java)

                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertThat(pathsSeen.distinct()).withFailMessage("${pathsSeen.joinToString("\n")}\n${results.report()}").satisfiesExactlyInAnyOrder(
            { assertThat(it).isEqualTo("/person/100") },
            { assertThat(it).matches("^/person/(false|true)") },
            { assertThat(it).matches("/person/[A-Z]+$") }
        )
    }

    @Test
    fun `should not run generative tests for body examples for non-200 scenarios`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /status:
                post:
                  summary: Create person record
                  requestBody:
                    content:
                      application/json:
                        examples:
                          SERVER_ERROR:
                            value:
                              status: enabled
                          BAD_REQUEST:
                            value:
                              status: enabled
                        schema:
                          required:
                          - status
                          properties:
                            status:
                              type: string
                              enum:
                                - enabled
                                - disabled
                  responses:
                    200:
                      description: Person record created
                      content:
                        text/plain:
                          schema:
                            type: "string"
                    400:
                      description: Server error
                      content:
                        text/plain:
                          schema:
                            type: "string"
                          examples:
                            BAD_REQUEST:
                              value: "Bad request"
                    500:
                      description: Server error
                      content:
                        text/plain:
                          schema:
                            type: "string"
                          examples:
                            SERVER_ERROR:
                              value: "Server error"
            """.trimIndent(), ""
        ).toFeature().enableGenerativeTesting()

        val testsSeen: MutableList<Pair<String, String?>> = mutableListOf()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                val testType = if(scenario.isNegative) "-ve" else "+ve"
                val exampleName = scenario.exampleName

                testsSeen.add(Pair(testType, exampleName))
            }
        })

        assertThat(results.testCount).isEqualTo(7)
        assertThat(testsSeen).doesNotContain("-ve" to "BAD_REQUEST")
        assertThat(testsSeen).doesNotContain("-ve" to "SERVER_ERROR")
    }

    @Test
    fun `should not run generative tests for query param examples for non-200 scenarios`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: id
                      in: query
                      required: true
                      schema:
                        type: integer
                      examples:
                        BAD_REQUEST:
                          value: 100
                        SERVER_ERROR:
                          value: 200
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                    400:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            BAD_REQUEST:
                              value: "Bad request"
                    500:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            SERVER_ERROR:
                              value: "Server error"
            """.trimIndent(), ""
        ).toFeature().enableGenerativeTesting()

        val testsSeen: MutableList<Pair<String, String?>> = mutableListOf()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                println(scenario.testDescription())
                println(request.toLogString())
                val testType = if(scenario.isNegative) "-ve" else "+ve"
                val exampleName = scenario.exampleName

                testsSeen.add(Pair(testType, exampleName))
            }
        })

        assertThat(results.testCount).isEqualTo(6)
        assertThat(testsSeen).doesNotContain("-ve" to "BAD_REQUEST")
        assertThat(testsSeen).doesNotContain("-ve" to "SERVER_ERROR")
    }

    @Test
    fun `should not run generative tests for enum query param examples for non-200 scenarios`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: category
                      in: query
                      required: true
                      schema:
                        type: string
                        enum:
                          - enabled
                          - disabled
                      examples:
                        BAD_REQUEST:
                          value: enabled
                        SERVER_ERROR:
                          value: enabled
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                    400:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            BAD_REQUEST:
                              value: "Bad request"
                    500:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            SERVER_ERROR:
                              value: "Server error"
            """.trimIndent(), ""
        ).toFeature().enableGenerativeTesting()

        val testsSeen: MutableList<Pair<String, String?>> = mutableListOf()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                println(scenario.testDescription())
                println(request.toLogString())
                val testType = if(scenario.isNegative) "-ve" else "+ve"
                val exampleName = scenario.exampleName

                testsSeen.add(Pair(testType, exampleName))
            }
        })

        assertThat(testsSeen).doesNotContain("-ve" to "BAD_REQUEST")
        assertThat(testsSeen).doesNotContain("-ve" to "SERVER_ERROR")
        assertThat(results.testCount).isEqualTo(7)
    }

    @Test
    fun `should not run generative tests for enum header examples for non-200 scenarios`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: category
                      in: header
                      required: true
                      schema:
                        type: string
                        enum:
                          - enabled
                          - disabled
                      examples:
                        BAD_REQUEST:
                          value: enabled
                        SERVER_ERROR:
                          value: enabled
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                    400:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            BAD_REQUEST:
                              value: "Bad request"
                    500:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            SERVER_ERROR:
                              value: "Server error"
            """.trimIndent(), ""
        ).toFeature().enableGenerativeTesting()

        val testsSeen: MutableList<Pair<String, String?>> = mutableListOf()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                println(scenario.testDescription())
                println(request.toLogString())
                val testType = if(scenario.isNegative) "-ve" else "+ve"
                val exampleName = scenario.exampleName

                testsSeen.add(Pair(testType, exampleName))
            }
        })

        assertThat(testsSeen).doesNotContain("-ve" to "BAD_REQUEST")
        assertThat(testsSeen).doesNotContain("-ve" to "SERVER_ERROR")
        assertThat(results.testCount).isEqualTo(7)
    }

    @Test
    fun `should not run generative tests for path param examples for non-200 scenarios`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person/{id}:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: integer
                      examples:
                        BAD_REQUEST:
                          value: 100
                        SERVER_ERROR:
                          value: 200
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                    400:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            BAD_REQUEST:
                              value: "Bad request"
                    500:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            SERVER_ERROR:
                              value: "Server error"
            """.trimIndent(), ""
        ).toFeature().enableGenerativeTesting()

        val testsSeen: MutableList<Pair<String, String?>> = mutableListOf()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                println(scenario.testDescription())
                println(request.toLogString())
                val testType = if(scenario.isNegative) "-ve" else "+ve"
                val exampleName = scenario.exampleName

                testsSeen.add(Pair(testType, exampleName))
            }
        })

        assertThat(results.testCount).isEqualTo(5)
        assertThat(testsSeen).doesNotContain("-ve" to "BAD_REQUEST")
        assertThat(testsSeen).doesNotContain("-ve" to "SERVER_ERROR")
    }

    @Test
    fun `should not run generative tests for enum path param examples for non-200 scenarios`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person/{category}:
                get:
                  summary: Fetch person's record
                  parameters:
                    - name: category
                      in: path
                      required: true
                      schema:
                        type: string
                        enum:
                          - active
                          - inactive
                      examples:
                        BAD_REQUEST:
                          value: active
                        SERVER_ERROR:
                          value: active
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                              - name
                            properties:
                              id:
                                type: integer
                              name:
                                type: string
                    400:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            BAD_REQUEST:
                              value: "Bad request"
                    500:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            SERVER_ERROR:
                              value: "Server error"
            """.trimIndent(), ""
        ).toFeature().enableGenerativeTesting()

        val testsSeen: MutableList<Pair<String, String?>> = mutableListOf()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                println(scenario.testDescription())
                println(request.toLogString())
                val testType = if(scenario.isNegative) "-ve" else "+ve"
                val exampleName = scenario.exampleName

                testsSeen.add(Pair(testType, exampleName))
            }
        })

        assertThat(results.testCount).isEqualTo(6)

        assertThat(testsSeen).doesNotContain("-ve" to "BAD_REQUEST")
        assertThat(testsSeen).doesNotContain("-ve" to "SERVER_ERROR")

        assertThat(testsSeen).containsOnlyOnce("+ve" to "BAD_REQUEST")
        assertThat(testsSeen).containsOnlyOnce("+ve" to "SERVER_ERROR")
    }

    @Test
    fun `tests for 2xx 4xx and 5xx should all have a prefix when generative tests are switched on`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: Store a persons details
                  requestBody:
                    content:
                      application/json:
                        examples:
                          CREATE_PERSON:
                            value:
                              status: "active"
                          BAD_REQUEST:
                            value:
                              status: "invalid-status"
                          SERVER_ERROR:
                            value:
                              status: "inactive"
                        schema:
                          required:
                          - status
                          properties:
                            status:
                              type: string
                              enum:
                                - active
                                - inactive
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: integer
                    400:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            BAD_REQUEST:
                              value: "Bad request"
                    500:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            SERVER_ERROR:
                              value: "Server error"
            """.trimIndent(), ""
        ).toFeature().enableGenerativeTesting()

        val testDescriptions: MutableList<String> = mutableListOf()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                println(scenario.testDescription())
                println(request.toLogString())

                testDescriptions.add(scenario.testDescription())
            }
        })

        assertThat(results.testCount).isPositive()

        val testDescriptionsWithNoPrefix = testDescriptions.filterNot { it.startsWith("+ve") || it.startsWith("-ve") }

        assertThat(testDescriptionsWithNoPrefix).isEmpty()
    }

    @Test
    fun `should generate negative tests for a specification where the request body is an array and there exists a named example for the same`() {
        val feature = OpenApiSpecification.fromFile(
            "src/test/resources/openapi/spec_with_array_request_body_and_named_example.yaml"
        ).toFeature().enableGenerativeTesting()

        val testDescriptions: MutableList<String> = mutableListOf()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                println(scenario.testDescription())
                println(request.toLogString())

                testDescriptions.add(scenario.descriptionFromPlugin.orEmpty())
            }
        })

        assertThat(results.testCount).isPositive()

        assertThat(testDescriptions.sorted().distinct()).containsExactlyInAnyOrder(
            "POST /items -> 201",
            "POST /items -> 4xx [REQUEST.BODY.[].name string mutated to boolean]",
            "POST /items -> 4xx [REQUEST.BODY.[].name string mutated to null]",
            "POST /items -> 4xx [REQUEST.BODY.[].name string mutated to number]",
            "POST /items -> 4xx [REQUEST.BODY.[].quantity number mutated to boolean]",
            "POST /items -> 4xx [REQUEST.BODY.[].quantity number mutated to null]",
            "POST /items -> 4xx [REQUEST.BODY.[].quantity number mutated to string]"
        )
    }

    @Test
    fun `no tests should have any prefix when generative tests are NOT switched on`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: Store a persons details
                  requestBody:
                    content:
                      application/json:
                        examples:
                          CREATE_PERSON:
                            value:
                              status: "active"
                          BAD_REQUEST:
                            value:
                              status: "invalid-status"
                          SERVER_ERROR:
                            value:
                              status: "inactive"
                        schema:
                          required:
                          - status
                          properties:
                            status:
                              type: string
                              enum:
                                - active
                                - inactive
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: integer
                    400:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            BAD_REQUEST:
                              value: "Bad request"
                    500:
                      description: Person's record
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            SERVER_ERROR:
                              value: "Server error"
            """.trimIndent(), ""
        ).toFeature()

        val testDescriptions: MutableList<String> = mutableListOf()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK
            }

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                println(scenario.testDescription())
                println(request.toLogString())

                testDescriptions.add(scenario.testDescription())
            }
        })

        assertThat(results.testCount).isPositive()

        val testDescriptionsWithNoPrefix = testDescriptions.filter { it.startsWith("+ve") || it.startsWith("-ve") }

        assertThat(testDescriptionsWithNoPrefix).isEmpty()
    }

    @Test
    fun `tests with bad examples should have the appropriate prefix when generative tests is switched on`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            ---
            openapi: "3.0.1"
            info:
              title: "Person API"
              version: "1"
            paths:
              /person:
                post:
                  summary: Store a persons details
                  requestBody:
                    content:
                      application/json:
                        schema:
                          required:
                          - name
                          properties:
                            name:
                              type: string
                        examples:
                          CREATE_PERSON:
                            value:
                              name: 10
                  responses:
                    200:
                      description: Person's record
                      content:
                        application/json:
                          schema:
                            type: object
                            required:
                              - id
                            properties:
                              id:
                                type: integer
                          examples:
                            CREATE_PERSON:
                              value:
                                id: 10
            """.trimIndent(), ""
        ).toFeature().enableGenerativeTesting()

        val testDescriptions = feature.generateContractTests(emptyList()).map {
            it.testDescription()
        }.toList()

        val testDescriptionsWithNoPrefix = testDescriptions.filterNot { it.startsWith("+ve") || it.startsWith("-ve") }

        assertThat(testDescriptionsWithNoPrefix).withFailMessage(testDescriptionsWithNoPrefix.joinToString(System.lineSeparator())).isEmpty()
    }
}
