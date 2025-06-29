package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.utilities.Flags.Companion.SCHEMA_EXAMPLE_DEFAULT
import io.specmatic.core.value.*
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class DefaultValuesInOpenapiSpecification {
    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            System.setProperty("SCHEMA_EXAMPLE_DEFAULT", "true")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            System.setProperty("SCHEMA_EXAMPLE_DEFAULT", "false")
        }
    }

    @Test
    fun `schema examples should be used as default values`() {
        val specification = OpenApiSpecification.fromYAML(
            """
        openapi: 3.0.1
        info:
          title: Employee API
          version: 1.0.0
        paths:
          /employees:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      ${'$'}ref: '#/components/schemas/Employee'
              responses:
                '200':
                  description: OK
                  content:
                    text/plain:
                      schema:
                        type: string
        components:
          schemas:
            Employee:
              type: object
              properties:
                name:
                  type: string
                  example: 'Jane Doe'
                age:
                  type: integer
                  format: int32
                  example: 35
                salary:
                  type: number
                  format: double
                  nullable: true
                  example: 50000
                salary_history:
                  type: array
                  items:
                    type: number
                    example: 1000
                years_employed:
                  type: array
                  items:
                    type: number
                  example:
                    - 2021
                    - 2022
                    - 2023
              required:
                - name
                - age
                - salary
                - years_employed
        """.trimIndent(), ""
        ).toFeature()

        val withGenerativeTestsEnabled = specification.enableGenerativeTesting().enableSchemaExampleDefault()

        val testTypes = mutableListOf<String>()

        val results = withGenerativeTestsEnabled.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val body = request.body as JSONObjectValue

                println(body.toStringLiteral())

                if ("salary" in body.jsonObject && body.jsonObject["salary"] !is NumberValue) {
                    testTypes.add("salary mutated to ${body.jsonObject["salary"]!!.displayableType()}")
                    return HttpResponse.ERROR_400
                }

                if (body.jsonObject["name"] !is StringValue) {
                    testTypes.add("name mutated to ${body.jsonObject["name"]!!.displayableType()}")
                    return HttpResponse.ERROR_400
                }

                if (body.jsonObject["age"] !is NumberValue) {
                    testTypes.add("age mutated to ${body.jsonObject["age"]!!.displayableType()}")
                    return HttpResponse.ERROR_400
                }

                if("salary_history" in body.jsonObject && body.jsonObject["salary_history"] !is JSONArrayValue) {
                    testTypes.add("salary_history mutated to ${body.jsonObject["salary_history"]!!.displayableType()}")
                    return HttpResponse.ERROR_400
                }

                if("salary_history" in body.jsonObject && body.jsonObject["salary_history"] is JSONArrayValue && (body.jsonObject["salary_history"]!! as JSONArrayValue).list.any { it !is NumberValue }) {
                    val item = (body.jsonObject["salary_history"]!! as JSONArrayValue).list.first { it !is NumberValue }
                    testTypes.add("salary_history[item] mutated to ${item.displayableType()}")
                    return HttpResponse.ERROR_400
                }

                if("years_employed" in body.jsonObject && body.jsonObject["years_employed"] !is JSONArrayValue) {
                    testTypes.add("years_employed mutated to ${body.jsonObject["years_employed"]!!.displayableType()}")
                    return HttpResponse.ERROR_400
                }

                if("years_employed" in body.jsonObject && body.jsonObject["years_employed"] is JSONArrayValue && (body.jsonObject["years_employed"]!! as JSONArrayValue).list.any { it !is NumberValue }) {
                    val item = (body.jsonObject["years_employed"]!! as JSONArrayValue).list.first { it !is NumberValue }
                    testTypes.add("years_employed[item] mutated to ${item.displayableType()}")
                    return HttpResponse.ERROR_400
                }

                assertThat(body.jsonObject["name"]).isEqualTo(StringValue("Jane Doe"))
                assertThat(body.jsonObject["age"]).isEqualTo(NumberValue(35))

                if ("salary" in body.jsonObject) {
                    testTypes.add("salary is present")
                } else {
                    testTypes.add("salary is absent")
                }

                if ("salary" in body.jsonObject) {
                    assertThat(body.jsonObject["salary"]).isEqualTo(NumberValue(50000))
                }

                if("salary_history" in body.jsonObject) {
                    assertThat((body.jsonObject["salary_history"] as JSONArrayValue).list).containsOnly(NumberValue(1000))
                }

                if("years_employed" in body.jsonObject) {
                    assertThat((body.jsonObject["years_employed"] as JSONArrayValue).list).contains(NumberValue(2021), NumberValue(2022), NumberValue(2023))
                }

                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {

            }
        })

        assertThat(testTypes).containsExactlyInAnyOrder(
            "salary is present",
            "salary is present",
            "name mutated to null",
            "name mutated to number",
            "name mutated to boolean",
            "age mutated to null",
            "age mutated to boolean",
            "age mutated to string",
            "salary mutated to boolean",
            "salary mutated to string",
            "salary_history[item] mutated to null",
            "salary_history[item] mutated to boolean",
            "salary_history[item] mutated to string",
            "years_employed[item] mutated to null",
            "years_employed[item] mutated to boolean",
            "years_employed[item] mutated to string",
            "name mutated to null",
            "name mutated to number",
            "name mutated to boolean",
            "age mutated to null",
            "age mutated to boolean",
            "age mutated to string",
            "salary mutated to boolean",
            "salary mutated to string",
            "years_employed[item] mutated to null",
            "years_employed[item] mutated to boolean",
            "years_employed[item] mutated to string"
        )
        assertThat(results.results).hasSize(testTypes.size)
    }

    @Test
    fun `named examples should be given preference over schema examples`() {
        val specification = OpenApiSpecification.fromYAML(
            """
        openapi: 3.0.1
        info:
          title: Employee API
          version: 1.0.0
        paths:
          /employees:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      ${'$'}ref: '#/components/schemas/Employee'
                    examples:
                      SUCCESS:
                        value:
                          name: 'John Doe'
                          age: 30
              responses:
                '200':
                  description: OK
                  content:
                    text/plain:
                      schema:
                        type: string
                      examples:
                        SUCCESS:
                          value: 'success'
                '400':
                    description: Bad Request
                    content:
                      text/plain:
                        schema:
                          type: string
        components:
          schemas:
            Employee:
              type: object
              properties:
                name:
                  type: string
                  example: 'Jane Doe'
                age:
                  type: integer
                  format: int32
                  example: 35
                salary:
                  type: number
                  format: double
                  nullable: true
                  example: 50000
              required:
                - name
                - age
        """.trimIndent(), ""
        ).toFeature()

        val withGenerativeTestsEnabled = specification.enableGenerativeTesting().enableSchemaExampleDefault()

        val testTypes = mutableListOf<String>()

        val results = withGenerativeTestsEnabled.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val body = request.body as JSONObjectValue

                if ("salary" in body.jsonObject && body.jsonObject["salary"] !is NumberValue) {
                    testTypes.add("salary mutated to ${body.jsonObject["salary"]!!.displayableType()}")
                    return HttpResponse.ERROR_400
                }

                if (body.jsonObject["name"] !is StringValue) {
                    testTypes.add("name mutated to ${body.jsonObject["name"]!!.displayableType()}")
                    return HttpResponse.ERROR_400
                }

                if (body.jsonObject["age"] !is NumberValue) {
                    testTypes.add("age mutated to ${body.jsonObject["age"]!!.displayableType()}")
                    return HttpResponse.ERROR_400
                }

                assertThat(body.jsonObject["name"]).isEqualTo(StringValue("John Doe"))
                assertThat(body.jsonObject["age"]).isEqualTo(NumberValue(30))

                if ("salary" in body.jsonObject) {
                    testTypes.add("salary is present")
                } else {
                    testTypes.add("salary is absent")
                }

                if ("salary" in body.jsonObject) {
                    assertThat(body.jsonObject["salary"]).isEqualTo(NumberValue(50000))
                }

                return HttpResponse.OK
            }

            override fun setServerState(serverState: Map<String, Value>) {

            }
        })

        assertThat(testTypes).containsExactlyInAnyOrder(
            "salary is present",
            "salary is absent",
            "name mutated to null",
            "name mutated to number",
            "name mutated to boolean",
            "age mutated to null",
            "age mutated to boolean",
            "age mutated to string",
            "salary mutated to boolean",
            "salary mutated to string",
            "name mutated to null",
            "name mutated to number",
            "name mutated to boolean",
            "age mutated to null",
            "age mutated to boolean",
            "age mutated to string"
        )
        assertThat(results.results).hasSize(testTypes.size)
    }

    @Test
    fun `SCHEMA_EXAMPLE_DEFAULT should switch on the schema example default feature`() {
        System.setProperty(SCHEMA_EXAMPLE_DEFAULT, "true")

        try {
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
            components:
              schemas:
                Product:
                  type: object
                  required:
                    - name
                    - price
                  properties:
                    name:
                      type: string
                      description: The name of the product
                      example: 'Soap'
                    price:
                      type: number
                      format: float
                      description: The price of the product
                      example: 10
                """, "",
            ).toFeature()

            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val body = request.body as JSONObjectValue
                    assertThat(body.jsonObject["name"]).isEqualTo(StringValue("Soap"))
                    assertThat(body.jsonObject["price"]).isEqualTo(NumberValue(10))
                    return HttpResponse.OK
                }

                override fun setServerState(serverState: Map<String, Value>) {

                }
            })

            assertThat(results.successCount).isEqualTo(1)
            assertThat(results.failureCount).isEqualTo(0)
        } finally {
            System.clearProperty(SCHEMA_EXAMPLE_DEFAULT)
        }
    }
}
