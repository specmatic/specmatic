package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarningsForRefWithSiblings {
    @Test
    fun `should warn about object property refs`() {
        val spec = """
            openapi: 3.0.3
            info:
              description: A simple API with 401 response
              title: Simple API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /test:
                get:
                  description: Test endpoint
                  operationId: test
                  responses:
                    "200":
                      description: Successful response
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Employee'
            components:
              schemas:
                Employee:
                  type: object
                  properties:
                    name:
                      type: string
                    address:
                      ${"$"}ref: '#/components/schemas/Address'
                Address:
                  type: object
                  properties:
                    building:
                      ${"$"}ref: '#/components/schemas/BuildingDetails'
                      type: object
                      properties:
                        name:
                          type: string
                        wing:
                          type: string
                    street:
                      type: string
                    city:
                      type: string
                BuildingDetails:
                  type: object
                  properties:
                    name:
                      type: string
                    wing:
                      type: string
        """.trimIndent()

        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains("Address.building")
    }

    @Test
    fun `should warn about nested object property refs`() {
        val spec = """
            openapi: 3.0.3
            info:
              description: A simple API with 401 response
              title: Simple API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /test:
                get:
                  description: Test endpoint
                  operationId: test
                  responses:
                    "200":
                      description: Successful response
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Employee'
            components:
              schemas:
                Employee:
                  type: object
                  properties:
                    name:
                      type: string
                    address:
                      ${"$"}ref: '#/components/schemas/Address'
                Address:
                  type: object
                  properties:
                    building:
                      type: object
                      properties:
                        details:
                          ${"$"}ref: '#/components/schemas/BuildingDetails'
                          type: object
                          properties:
                            name:
                              type: string
                            wing:
                              type: string
                    street:
                      type: string
                    city:
                      type: string
                BuildingDetails:
                  type: object
                  properties:
                    name:
                      type: string
                    wing:
                      type: string
        """.trimIndent()

        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains("Address.building.details")
    }

    @Test
    fun `should warn about arrays refs`() {
        val spec = """
            openapi: 3.0.3
            info:
              description: A simple API with 401 response
              title: Simple API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /test:
                get:
                  description: Test endpoint
                  operationId: test
                  responses:
                    "200":
                      description: Successful response
                      content:
                        application/json:
                          schema:
                            ${"$"}ref: '#/components/schemas/Employee'
            components:
              schemas:
                Employee:
                  type: object
                  properties:
                    name:
                      type: string
                    phoneNumbers:
                      type: array
                      items:
                        ${"$"}ref: '#/components/schemas/PhoneNumber'
                        type: object
                        properties:
                          countryCode:
                            type: string
                          mainNumber:
                            type: string
                PhoneNumber:
                  type: object
                  properties:
                    countryCode:
                      type: string
                    mainNumber:
                      type: string
        """.trimIndent()

        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains("Employee.phoneNumbers[]")
    }

    @Test
    fun `should warn about refs inline in response body`() {
        val spec = """
            openapi: 3.0.3
            info:
              description: A simple API with 401 response
              title: Simple API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /test:
                get:
                  description: Test endpoint
                  operationId: test
                  responses:
                    "200":
                      description: Successful response
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              name:
                                type: string
                              address:
                                type: object
                                properties:
                                  building:
                                    ${"$"}ref: '#/components/schemas/BuildingDetails'
                                    type: object
                                    properties:
                                      name:
                                        type: string
                                      wing:
                                        type: string
                                  street:
                                    type: string
                                  city:
                                    type: string
            components:
              schemas:
                BuildingDetails:
                  type: object
                  properties:
                    name:
                      type: string
                    wing:
                      type: string
        """.trimIndent()

        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains("GET /test -> 200 (application/json).address.building")
    }

    @Test
    fun `should warn about refs inline in request body`() {
        val spec = """
            openapi: 3.0.3
            info:
              description: A simple API with 401 response
              title: Simple API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /test:
                post:
                  description: Test endpoint
                  operationId: test
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          properties:
                            name:
                              type: string
                            address:
                              type: object
                              properties:
                                building:
                                  ${"$"}ref: '#/components/schemas/BuildingDetails'
                                  type: object
                                  properties:
                                    name:
                                      type: string
                                    wing:
                                      type: string
                                street:
                                  type: string
                                city:
                                  type: string
                  responses:
                    "201":
                      description: Created response
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: string
            components:
              schemas:
                BuildingDetails:
                  type: object
                  properties:
                    name:
                      type: string
                    wing:
                      type: string
        """.trimIndent()

        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains("POST /test (application/json).address.building")
    }

    @Test
    fun `should warn about refs in query params`() {
        val spec = """
            openapi: 3.0.3
            info:
              description: A simple API with 401 response
              title: Simple API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /test:
                parameters:
                  - name: cityType
                    in: query
                    required: true
                    schema:
                      type: string
                      enum: [metropolitan, urban, rural]
                      ${"$"}ref: '#/components/schemas/CityType'
                get:
                  description: Test endpoint
                  operationId: test
                  responses:
                    "200":
                      description: City details
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              type: string
                              
            components:
              schemas:
                CityType:
                  type: string
                  enum: [metropolitan, urban, rural]
        """.trimIndent()

        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains("GET /test.PARAMETERS.QUERY.cityType")
    }

    @Test
    fun `should warn about refs in path params`() {
        val spec = """
            openapi: 3.0.3
            info:
              description: A simple API with 401 response
              title: Simple API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /test/{cityType}:
                parameters:
                  - name: cityType
                    in: path
                    required: true
                    schema:
                      type: string
                      enum: [metropolitan, urban, rural]
                      ${"$"}ref: '#/components/schemas/CityType'
                get:
                  description: Test endpoint
                  operationId: test
                  responses:
                    "200":
                      description: City details
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              type: string
                              
            components:
              schemas:
                CityType:
                  type: string
                  enum: [metropolitan, urban, rural]
        """.trimIndent()

        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains("GET /test/{cityType}.PARAMETERS.PATH.cityType")
    }

    @Test
    fun `should warn about refs in request header params`() {
        val spec = """
            openapi: 3.0.3
            info:
              description: A simple API with 401 response
              title: Simple API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /test:
                parameters:
                  - name: cityType
                    in: header
                    required: true
                    schema:
                      type: string
                      enum: [metropolitan, urban, rural]
                      ${"$"}ref: '#/components/schemas/CityType'
                get:
                  description: Test endpoint
                  operationId: test
                  responses:
                    "200":
                      description: City details
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              type: string
                              
            components:
              schemas:
                CityType:
                  type: string
                  enum: [metropolitan, urban, rural]
        """.trimIndent()

        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains("GET /test.PARAMETERS.HEADER.cityType")
    }

    @Test
    fun `should warn about refs in response headers`() {
        val spec = """
            openapi: 3.0.3
            info:
              description: A simple API with 401 response
              title: Simple API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /test:
                get:
                  description: Test endpoint
                  operationId: test
                  responses:
                    "200":
                      description: City details
                      headers:
                        X-Unique-ID:
                          description: Type of the city
                          schema:
                            type: string
                            ${"$"}ref: "#/components/schemas/UniqueIDType"
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              type: string
            components:
              schemas:
                UniqueIDType:
                  type: string
                  description: Unique identifier for the city
        """.trimIndent()

        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains("GET /test -> 200.RESPONSE.HEADER.X-Unique-ID")
    }
}
