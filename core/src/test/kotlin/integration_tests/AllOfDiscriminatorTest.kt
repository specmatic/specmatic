package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.function.Consumer

class AllOfDiscriminatorTest {
    @Test
    fun `when the discriminator property is missing from the spec the issue should be mentioned explicitly`() {
        val feature = OpenApiSpecification.fromYAML("""
            ---
            openapi: 3.0.3
            info:
              title: Vehicle API
              version: 1.0.0
            paths:
              /vehicle:
                post:
                  summary: Add a new vehicle
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Vehicle'
                  responses:
                    '201':
                      description: Vehicle created successfully
                      content:
                        text/plain:
                          schema:
                            type: string

            components:
              schemas:
                VehicleType:
                  type: object

                Vehicle:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/VehicleType'
                    - type: object
                      properties:
                        seatingCapacity:
                          type: integer
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "car": "#/components/schemas/Car"
                      "bike": "#/components/schemas/Bike"

                Car:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      requried:
                        - gearType
                      properties:
                        gearType:
                          type: string
                Bike:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      requried:
                        - headlampStyle
                      properties:
                        headlampStyle:
                          type: string
        """.trimIndent(), "").toFeature()

        val result = feature.matchResult(
            HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "car", "seatingCapacity": 4, "gearType": "MT"}""")),
            HttpResponse(201, body = StringValue("success"))
        )

        assertThat(result.reportString()).doesNotContain("car, bike")
        assertThat(result.reportString()).contains("missing from the spec")
    }

    @Test
    fun `when the discriminator property is missing from the object the issue should be mentioned explicitly`() {
        val feature = OpenApiSpecification.fromYAML("""
            ---
            openapi: 3.0.3
            info:
              title: Vehicle API
              version: 1.0.0
            paths:
              /vehicle:
                post:
                  summary: Add a new vehicle
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Vehicle'
                  responses:
                    '201':
                      description: Vehicle created successfully
                      content:
                        text/plain:
                          schema:
                            type: string

            components:
              schemas:
                VehicleType:
                  type: object
                  required:
                    - type
                  properties:
                    type:
                      type: string
                Vehicle:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/VehicleType'
                    - type: object
                      properties:
                        seatingCapacity:
                          type: integer
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "car": "#/components/schemas/Car"
                      "bike": "#/components/schemas/Bike"

                Car:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      requried:
                        - gearType
                      properties:
                        gearType:
                          type: string
                Bike:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      requried:
                        - headlampStyle
                      properties:
                        headlampStyle:
                          type: string
        """.trimIndent(), "").toFeature()

        val result = feature.matchResult(
            HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"seatingCapacity": 4, "gearType": "MT"}""")),
            HttpResponse(201, body = StringValue("success"))
        )

        val discriminatorProperty = "type"
        assertThat(result.reportString()).contains("Discriminator property $discriminatorProperty is missing from the object")
    }

    @Test
    fun `discriminator property with incorrect value should be caught as an error`() {
        val feature = OpenApiSpecification.fromYAML("""
            ---
            openapi: 3.0.3
            info:
              title: Vehicle API
              version: 1.0.0
            paths:
              /vehicle:
                post:
                  summary: Add a new vehicle
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Vehicle'
                  responses:
                    '201':
                      description: Vehicle created successfully
                      content:
                        text/plain:
                          schema:
                            type: string

            components:
              schemas:
                VehicleType:
                  type: object
                  required:
                    - type
                  properties:
                    type:
                      type: string
                Vehicle:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/VehicleType'
                    - type: object
                      properties:
                        seatingCapacity:
                          type: integer
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "car": "#/components/schemas/Car"
                      "bike": "#/components/schemas/Bike"

                Car:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      requried:
                        - gearType
                      properties:
                        gearType:
                          type: string
                Bike:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      requried:
                        - headlampStyle
                      properties:
                        headlampStyle:
                          type: string
        """.trimIndent(), "").toFeature()

        val result = feature.matchResult(
            HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "airplane", "seatingCapacity": 4, "gearType": "MT"}""")),
            HttpResponse(201, body = StringValue("success"))
        )

        assertThat(result.reportString()).contains("car, bike")
    }

    @Nested
    inner class DiscriminatorDetails {
        private val openAPIText = """
            ---
            openapi: 3.0.3
            info:
              title: Vehicle API
              version: 1.0.0
            paths:
              /car:
                post:
                  summary: Add a new vehicle
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Car'
                  responses:
                    '201':
                      description: Vehicle created successfully
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: string
                                description: Unique identifier for the newly created vehicle
                              type:
                                type: string
                                description: Type of the vehicle (car or bike)
              /bike:
                post:
                  summary: Add a new vehicle
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Bike'
                  responses:
                    '201':
                      description: Vehicle created successfully
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: string
                                description: Unique identifier for the newly created vehicle
                              type:
                                type: string
                                description: Type of the vehicle (car or bike)

            components:
              schemas:
                VehicleType:
                  type: object
                  properties:
                    type:
                      type: string
            
                Car:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/VehicleType'
                    - type: object
                      properties:
                        type:
                          type: string
                        seatingCapacity:
                          type: integer
                        trunkSize:
                          type: string
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "car": "#/components/schemas/Car"
            
                Bike:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/VehicleType'
                    - type: object
                      properties:
                        type:
                          type: string
                        hasCarrier:
                          type: boolean
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "bike": "#/components/schemas/Bike"
        """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(openAPIText, "").toFeature()

        @ParameterizedTest
        @CsvSource(
            value = [
                "path, type",
                "/car, plane",
                "/bike, plane",
                "/car, bike",
                "/bike, car"
            ],
            useHeadersInDisplayName = true,
        )
        fun `discriminator mismatch`(path: String, type: String) {
            val body = requestBody(path, type)

            assertThatThrownBy {
                feature.matchingStub(
                    HttpRequest(
                        "POST",
                        path,
                        body = body
                    ),
                    HttpResponse(
                        201,
                        headers = mapOf("Content-Type" to "application/json"),
                        parsedJSONObject("""{"id": "abc123", "type": "$type"}""")
                    )
                )
            }.isInstanceOf(NoMatchingScenario::class.java)
        }

        @Test
        fun `error when discriminator key is missing from a value`() {
            assertThatThrownBy {
                feature.matchingStub(
                    HttpRequest(
                        "POST",
                        "/car",
                        body = parsedJSON("""{"seatingCapacity": 4, "trunkSize": "large"}""")
                    ),
                    HttpResponse(
                        201,
                        headers = mapOf("Content-Type" to "application/json"),
                        parsedJSONObject("""{"id": "abc123", "type": "car"}""")
                    )
                )
            }.satisfies(Consumer {
                assertThat(exceptionCauseMessage(it)).contains("property type is missing from the object")
            })
        }

        @Test
        fun `error when discriminator key has the wrong value`() {
            assertThatThrownBy {
                feature.matchingStub(
                    HttpRequest(
                        "POST",
                        "/car",
                        body = parsedJSON("""{"type": "airplane", "seatingCapacity": 4, "trunkSize": "large"}""")
                    ),
                    HttpResponse(
                        201,
                        headers = mapOf("Content-Type" to "application/json"),
                        parsedJSONObject("""{"id": "abc123", "type": "car"}""")
                    )
                )
            }.satisfies(Consumer {
                assertThat(exceptionCauseMessage(it)).contains("discriminator property to be car")
            })
        }

        @Test
        fun `error when discriminator key matches but there is some other mismatch`() {
            assertThatThrownBy {
                feature.matchingStub(
                    HttpRequest(
                        "POST",
                        "/car",
                        body = parsedJSON("""{"type": "car", "seatingCapacity": "four", "trunkSize": "large"}""")
                    ),
                    HttpResponse(
                        201,
                        headers = mapOf("Content-Type" to "application/json"),
                        parsedJSONObject("""{"id": "abc123", "type": "car"}""")
                    )
                )
            }.satisfies(Consumer {
                assertThat(exceptionCauseMessage(it)).contains("REQUEST.BODY.seatingCapacity")
            })
        }

        private fun requestBody(path: String, type: String): Value {
            val body = when (path) {
                "/car" -> parsedJSON("""{"type": "$type", "seatingCapacity": 4, "trunkSize": "large"}""")
                "/bike" -> parsedJSON("""{"type": "$type", "hasCarrier": false}""")
                else -> fail("Path $path not recognized in this test")
            }
            return body
        }

        @ParameterizedTest
        @CsvSource(
            value = [
                "path, type",
                "/car, car",
                "/bike, bike"
            ],
            useHeadersInDisplayName = true,
        )
        fun `happy path tests using discriminator as enum`(path: String, type: String) {
            val body = requestBody(path, type)

            assertThat(
                feature.matchingStub(
                    HttpRequest(
                        "POST",
                        path,
                        body = body
                    ),
                    HttpResponse(
                        201,
                        headers = mapOf("Content-Type" to "application/json"),
                        parsedJSONObject("""{"id": "abc123", "type": "$type"}""")
                    )
                ).response.headers["X-Specmatic-Result"]
            ).isEqualTo("success")
        }
    }

    @Test
    fun `discriminator can extend an allOf`() {
        val feature = OpenApiSpecification.fromYAML("""
            ---
            openapi: 3.0.3
            info:
              title: Vehicle API
              version: 1.0.0
            paths:
              /vehicle:
                post:
                  summary: Add a new vehicle
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Vehicle'
                  responses:
                    '201':
                      description: Vehicle created successfully
                      content:
                        text/plain:
                          schema:
                            type: string

            components:
              schemas:
                VehicleType:
                  type: object
                  properties:
                    type:
                      type: string

                Vehicle:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/VehicleType'
                    - type: object
                      properties:
                        seatingCapacity:
                          type: integer
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "car": "#/components/schemas/Transmission"
                      "bike": "#/components/schemas/SideCar"

                Transmission:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      requried:
                        - gearType
                      properties:
                        gearType:
                          type: string

                SideCar:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      requried:
                        - sidecarAvailable
                      properties:
                        sidecarAvailable:
                          type: boolean
        """.trimIndent(), "").toFeature()

        HttpStub(feature).use { stub ->
            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "car", "seatingCapacity": 4, "gearType": "MT"}"""))).let {
                assertThat(it.status).isEqualTo(201)
            }

            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "bike", "seatingCapacity": 2, "sidecarAvailable": true}"""))).let {
                assertThat(it.status).isEqualTo(201)
            }

            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "car", "seatingCapacity": 2, "sidecarAvailable": true}"""))).let {
                assertThat(it.status).isEqualTo(400)
            }

            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "bike", "seatingCapacity": 4, "gearType": "MT"}"""))).let {
                assertThat(it.status).isEqualTo(400)
            }
        }
    }

    @Test
    fun `the specific discriminator value in an allOf can be in the child schema`() {
        val feature = OpenApiSpecification.fromYAML("""
            ---
            openapi: 3.0.3
            info:
              title: Vehicle API
              version: 1.0.0
            paths:
              /vehicle:
                post:
                  summary: Add a new vehicle
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Vehicle'
                  responses:
                    '201':
                      description: Vehicle created successfully
                      content:
                        text/plain:
                          schema:
                            type: string

            components:
              schemas:
                VehicleType:
                  type: object
                  properties:
                    type:
                      type: string

                Vehicle:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/VehicleType'
                    - type: object
                      properties:
                        seatingCapacity:
                          type: integer
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "car": "#/components/schemas/Transmission"
                      "bike": "#/components/schemas/SideCar"

                Transmission:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      requried:
                        - gearType
                      properties:
                        gearType:
                          type: string
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "car": "#/components/schemas/Transmission"

                SideCar:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      requried:
                        - sidecarAvailable
                      properties:
                        sidecarAvailable:
                          type: boolean
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "bike": "#/components/schemas/SideCar"
        """.trimIndent(), "").toFeature()

        HttpStub(feature).use { stub ->
            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "car", "seatingCapacity": 4, "gearType": "MT"}"""))).let {
                assertThat(it.status).isEqualTo(201)
            }

            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "bike", "seatingCapacity": 2, "sidecarAvailable": true}"""))).let {
                assertThat(it.status).isEqualTo(201)
            }

            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "car", "seatingCapacity": 2, "sidecarAvailable": true}"""))).let {
                assertThat(it.status).isEqualTo(400)
            }

            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "bike", "seatingCapacity": 4, "gearType": "MT"}"""))).let {
                assertThat(it.status).isEqualTo(400)
            }
        }
    }

    @Test
    @Disabled
    fun `two discriminator at different levels can extend an allOf`() {
        val feature = OpenApiSpecification.fromYAML("""
            ---
            openapi: 3.0.3
            info:
              title: Vehicle API
              version: 1.0.0
            paths:
              /vehicle:
                post:
                  summary: Add a new vehicle
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Vehicle'
                  responses:
                    '201':
                      description: Vehicle created successfully
                      content:
                        text/plain:
                          schema:
                            type: string

            components:
              schemas:
                VehicleType:
                  type: object
                  properties:
                    type:
                      type: string

                Vehicle:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/VehicleType'
                    - type: object
                      properties:
                        seatingCapacity:
                          type: integer
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "car": "#/components/schemas/Transmission"
                      "bike": "#/components/schemas/SideCar"

                Transmission:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      required:
                        - gearType
                      properties:
                        gearType:
                          type: string
                  discriminator:
                    propertyName: "gearType"
                    mapping:
                      "MT": '#/components/schemas/ManualTransmission'
                      "AT": '#/components/schemas/AutomaticTransmission'

                ManualTransmission:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Transmission'
                    - type: object
                      required:
                        - gearCount
                      properties:
                        gearCount:
                          type: integer

                AutomaticTransmission:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Transmission'
                    - type: object
                      required:
                        - hasSportsMode
                      properties:
                        hasSportsMode:
                          type: boolean

                SideCar:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      requried:
                        - sidecarAvailable
                      properties:
                        sidecarAvailable:
                          type: boolean
        """.trimIndent(), "").toFeature()

        HttpStub(feature).use { stub ->
            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "car", "seatingCapacity": 4, "gearType": "MT", "gearCount": 4}"""))).let {
                assertThat(it.status).withFailMessage(it.toLogString()).isEqualTo(201)
            }

            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "car", "seatingCapacity": 4, "gearType": "AT", "hasSportsMode": true}"""))).let {
                assertThat(it.status).withFailMessage(it.toLogString()).isEqualTo(201)
            }

            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "bike", "seatingCapacity": 2, "sidecarAvailable": true}"""))).let {
                assertThat(it.status).withFailMessage(it.toLogString()).isEqualTo(201)
            }

            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "car", "seatingCapacity": 2, "sidecarAvailable": true}"""))).let {
                assertThat(it.status).withFailMessage(it.toLogString()).isEqualTo(400)
            }

            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "bike", "seatingCapacity": 4, "gearType": "MT", "gearCount": 4}"""))).let {
                assertThat(it.status).withFailMessage(it.toLogString()).isEqualTo(400)
            }

            stub.client.execute(HttpRequest("POST", "/vehicle", body = parsedJSONObject("""{"type": "car", "seatingCapacity": 4, "gearType": "AT", "gearCount": 4}"""))).let {
                assertThat(it.status).withFailMessage(it.toLogString()).isEqualTo(400)
            }
        }
    }

    @Test
    fun `test generation with allOf discriminator and no examples`() {
        val feature = OpenApiSpecification.fromYAML("""
            ---
            openapi: 3.0.3
            info:
              title: Vehicle API
              version: 1.0.0
            paths:
              /vehicle:
                post:
                  summary: Add a new vehicle
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Vehicle'
                  responses:
                    '201':
                      description: Vehicle created successfully
                      content:
                        text/plain:
                          schema:
                            type: string

            components:
              schemas:
                VehicleType:
                  type: object
                  required:
                  - type
                  properties:
                    type:
                      type: string

                Vehicle:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/VehicleType'
                    - type: object
                      required:
                      - seatingCapacity
                      properties:
                        seatingCapacity:
                          type: integer
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "car": "#/components/schemas/Transmission"
                      "bike": "#/components/schemas/SideCar"

                Transmission:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      required:
                        - gearType
                      properties:
                        gearType:
                          type: string

                SideCar:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      required:
                        - sidecarAvailable
                      properties:
                        sidecarAvailable:
                          type: boolean
        """.trimIndent(), "").toFeature()

        val vehicleTypes = mutableSetOf<String>()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.toLogString())
                val vehicleType = request.body.let { (it as JSONObjectValue).findFirstChildByPath("type")?.toStringLiteral() ?: "" }

                vehicleTypes.add(vehicleType)

                return HttpResponse.ok("success")
            }
        })

        assertThat(vehicleTypes).containsAll(listOf("car", "bike"))
        assertThat(results.testCount).isEqualTo(6)
    }

    @Test
    fun `test execution with allOf discriminator and one example`() {
        val feature = OpenApiSpecification.fromYAML("""
            ---
            openapi: 3.0.3
            info:
              title: Vehicle API
              version: 1.0.0
            paths:
              /vehicle:
                post:
                  summary: Add a new vehicle
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Vehicle'
                        examples:
                          car:
                            value:
                              type: "car"
                              seatingCapacity: 4
                              gearType: "MT"
                  responses:
                    '201':
                      description: Vehicle created successfully
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            car:
                              value: "success"

            components:
              schemas:
                VehicleType:
                  type: object
                  required:
                  - type
                  properties:
                    type:
                      type: string

                Vehicle:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/VehicleType'
                    - type: object
                      required:
                      - seatingCapacity
                      properties:
                        seatingCapacity:
                          type: integer
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "car": "#/components/schemas/Transmission"
                      "bike": "#/components/schemas/SideCar"

                Transmission:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      required:
                        - gearType
                      properties:
                        gearType:
                          type: string

                SideCar:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      required:
                        - sidecarAvailable
                      properties:
                        sidecarAvailable:
                          type: boolean
        """.trimIndent(), "").toFeature()

        val vehicleTypes = mutableSetOf<String>()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.toLogString())

                val requestJSON = request.body as JSONObjectValue

                val vehicleType = request.body.let { requestJSON.findFirstChildByPath("type")?.toStringLiteral() ?: "" }

                vehicleTypes.add(vehicleType)

                if(vehicleType == "car") {
                    assertThat(requestJSON.findFirstChildByPath("gearType")?.toStringLiteral()).isEqualTo("MT")
                    assertThat(requestJSON.findFirstChildByPath("seatingCapacity")?.toStringLiteral()).isEqualTo("4")
                }

                return HttpResponse(201, "success")
            }
        })

        assertThat(vehicleTypes).containsOnly("car")
        assertThat(results.testCount).isEqualTo(1)
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @Test
    fun `generative tests with allOf discriminator and one example`() {
        val feature = OpenApiSpecification.fromYAML("""
            ---
            openapi: 3.0.3
            info:
              title: Vehicle API
              version: 1.0.0
            paths:
              /vehicle:
                post:
                  summary: Add a new vehicle
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Vehicle'
                        examples:
                          car:
                            value:
                              type: "car"
                              seatingCapacity: 4
                              gearType: "MT"
                  responses:
                    '201':
                      description: Vehicle created successfully
                      content:
                        text/plain:
                          schema:
                            type: string
                          examples:
                            car:
                              value: "success"

            components:
              schemas:
                VehicleType:
                  type: object
                  required:
                  - type
                  properties:
                    type:
                      type: string

                Vehicle:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/VehicleType'
                    - type: object
                      required:
                      - seatingCapacity
                      properties:
                        seatingCapacity:
                          type: integer
                  discriminator:
                    propertyName: "type"
                    mapping:
                      "car": "#/components/schemas/Transmission"
                      "bike": "#/components/schemas/SideCar"

                Transmission:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      required:
                        - gearType
                      properties:
                        gearType:
                          type: string

                SideCar:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Vehicle'
                    - type: object
                      required:
                        - sidecarAvailable
                      properties:
                        sidecarAvailable:
                          type: boolean
        """.trimIndent(), "").toFeature().enableGenerativeTesting(onlyPositive = true)

        val vehicleTypes = mutableSetOf<String>()

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                println(request.toLogString())
                val requestJSON = request.body as JSONObjectValue

                val vehicleType = request.body.let {
                    requestJSON.findFirstChildByPath("type")?.toStringLiteral() ?: "" }

                vehicleTypes.add(vehicleType)

                if(vehicleType == "car") {
                    assertThat(requestJSON.findFirstChildByPath("gearType")?.toStringLiteral()).isEqualTo("MT")
                    assertThat(requestJSON.findFirstChildByPath("seatingCapacity")).isNotNull()
                }

                return HttpResponse(201, body = "success")
            }
        })

        assertThat(vehicleTypes).containsAll(listOf("car", "bike"))
        assertThat(results.testCount).isEqualTo(6)
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }
}