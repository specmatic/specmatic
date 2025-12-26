package io.specmatic.conversions

import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.ListPattern
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.pattern.resolvedHop
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.toViolationReportString
import io.specmatic.core.DefaultMismatchMessages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OverlayTest {
    companion object {
        fun assertKeyIsRequired(pattern: JSONObjectPattern, key: String) {
            assertThat(pattern.pattern.keys).contains(key)
            assertThat(pattern.pattern.keys).doesNotContain("$key?")
        }

        fun assertKeyIsOptional(pattern: JSONObjectPattern, key: String) {
            assertThat(pattern.pattern.keys).doesNotContain(key)
            assertThat(pattern.pattern.keys).contains("$key?")
        }
    }

    @Test
    fun `should be applied to resolved schema when properties are multi-level deep`() {
        val specContent = """
        openapi: 3.0.3
        info:
          title: Product API
          version: 1.0.0
        paths:
          /product:
            get:
              responses:
                '200':
                  description: Product retrieved successfully
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/ProductFinal'
        components:
          schemas:
            BaseProduct:
              type: object
              properties:
                price:
                  type: number
                category:
                  type: string
            Product:
              allOf:
                - ${'$'}ref: "#/components/schemas/BaseProduct"
                - type: object
                  properties:
                    id:
                      type: string
            ProductFinal:
              allOf:
                - ${'$'}ref: '#/components/schemas/Product'
                - type: object
                  properties:
                    name:
                      type: string
        """.trimIndent()
        val overlayContent = """
        overlay: 1.0.0
        actions:
        - target: ${'$'}.components.schemas
          update:
            AnyValue: {}
        - target: ${'$'}.components.schemas.ProductFinal.allOf
          update:
            required:
            - name
            - id 
            - price
            properties:
              name:
                type: '#/components/schemas/AnyValue'
              id:
                type: '#/components/schemas/AnyValue'
              price:
                type: '#/components/schemas/AnyValue'
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specContent, "", overlayContent = overlayContent).toFeature()
        val scenario = feature.scenarios.first { it.path == "/product" }
        val resolvedBodyPattern = resolvedHop(scenario.httpResponsePattern.body, scenario.resolver) as JSONObjectPattern

        assertThat(listOf("id", "price", "name")).allSatisfy {
            assertKeyIsRequired(resolvedBodyPattern, it)
        }
        assertKeyIsOptional(resolvedBodyPattern, "category")
    }

    @Test
    fun `data type mismatch errors shouldn't repeat on an overlay applied specification`() {
        val specContent = """
        openapi: 3.0.3
        info:
          title: Product API
          version: 1.0.0
        paths:
          /products:
            get:
              responses:
                '200':
                  description: Retrieve products
                  content:
                    application/json:
                      schema:
                        type: array
                        items:
                          ${'$'}ref: '#/components/schemas/Product'
          /product:
            get:
              responses:
                '200':
                  description: Retrieve a product by its ID
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/Product'
        components:
          schemas:
            BaseProduct:
              type: object
              properties:
                description:
                  type: string
            Product:
              allOf:
                - ${'$'}ref: '#/components/schemas/BaseProduct'
        """.trimIndent()
        val overlayContent = """
            overlay: 1.0.0
            actions:
            - target: ${'$'}.components.schemas
              update:
                AnyValue: {}
            - target: ${'$'}.components.schemas.Product.allOf
              update:
                type: object
                required:
                - description
                properties:
                  description:
                    type: '#/components/schemas/AnyValue'
            """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(specContent, "", overlayContent = overlayContent).toFeature()
        val incorrectDataTypeValue = parsedJSONObject("""{
            "description": 123
        }""".trimIndent())

        val objectScenario = feature.scenarios.first { it.path == "/product" }
        val objectResponseBody = resolvedHop(objectScenario.httpResponsePattern.body, objectScenario.resolver) as JSONObjectPattern

        val objectResult = objectResponseBody.matches(incorrectDataTypeValue, objectScenario.resolver)
        assertThat(objectResult).isInstanceOf(Result.Failure::class.java)
        assertThat(objectResult.reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "description",
                details = DefaultMismatchMessages.typeMismatch("string", "123", "number"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        )

        val arrayScenario = feature.scenarios.first { it.path == "/products" }
        val arrayResponseBody = resolvedHop(arrayScenario.httpResponsePattern.body, objectScenario.resolver) as ListPattern

        val arrayResult = arrayResponseBody.matches(JSONArrayValue(listOf(incorrectDataTypeValue, incorrectDataTypeValue)), arrayScenario.resolver)
        assertThat(arrayResult).isInstanceOf(Result.Failure::class.java)
        assertThat(arrayResult.reportString()).isEqualToIgnoringWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "[0].description",
                details = DefaultMismatchMessages.typeMismatch("string", "123", "number"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "[1].description",
                details = DefaultMismatchMessages.typeMismatch("string", "123", "number"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Nested
    inner class UpdateTest {
        @Test
        fun `should mark properties as required previously optional in the resolved schema for Object`() {
            val specContent = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /product:
                get:
                  responses:
                    '200':
                      description: Product retrieved successfully
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/Product'
            components:
              schemas:
                BaseProduct:
                  type: object
                  properties:
                    id:
                      type: string
                    description:
                      type: string
                Product:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/BaseProduct'
                    - type: object
                      properties:
                        name:
                          type: string
            """.trimIndent()
            val overlayContent = """
            overlay: 1.0.0
            actions:
            - target: ${'$'}.components.schemas
              update:
                AnyValue: {}
            - target: ${'$'}.components.schemas.Product.allOf
              update:
                type: object
                required:
                - id
                properties:
                  id:
                    type: '#/components/schemas/AnyValue'
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(specContent, "", overlayContent = overlayContent).toFeature()
            val scenario = feature.scenarios.first { it.path == "/product" }
            val resolvedBodyPattern = resolvedHop(scenario.httpResponsePattern.body, scenario.resolver) as JSONObjectPattern

            assertKeyIsRequired(resolvedBodyPattern, "id")
            assertThat(listOf("name", "description")).allSatisfy {
                assertKeyIsOptional(resolvedBodyPattern, it)
            }
        }

        @Test
        fun `should mark properties as required previously optional in the resolved schema for Array`() {
            val specContent = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /products:
                get:
                  responses:
                    '200':
                      description: Products retrieved successfully
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              ${'$'}ref: '#/components/schemas/Product'
            components:
              schemas:
                BaseProduct:
                  type: object
                  properties:
                    description:
                      type: string
                    id:
                      type: string
                Product:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/BaseProduct'
            """.trimIndent()
            val overlayContent = """
            overlay: 1.0.0
            actions:
            - target: ${'$'}.components.schemas
              update:
                AnyValue: {}
            - target: ${'$'}.components.schemas.Product.allOf
              update:
                type: object
                required:
                - id
                properties:
                  id:
                    type: '#/components/schemas/AnyValue'
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(specContent, "", overlayContent = overlayContent).toFeature()
            val scenario = feature.scenarios.first { it.path == "/products" }
            val bodyPattern = scenario.httpResponsePattern.body as ListPattern
            val resolvedBodyPattern = resolvedHop(bodyPattern.pattern, scenario.resolver) as JSONObjectPattern

            assertKeyIsRequired(resolvedBodyPattern, "id")
            assertKeyIsOptional(resolvedBodyPattern, "description")
        }

        @Test
        fun `should keep properties as required previously also required in the resolved schema`() {
            val specContent = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /product:
                get:
                  responses:
                    '200':
                      description: Product retrieved successfully
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/Product'
            components:
              schemas:
                BaseProduct:
                  type: object
                  properties:
                    description:
                      type: string
                    id:
                      type: string
                  required:
                    - description
                    - id
                Product:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/BaseProduct'
                    - type: object
                      properties:
                        name:
                          type: string
            """.trimIndent()
            val overlayContent = """
            overlay: 1.0.0
            actions:
            - target: ${'$'}.components.schemas
              update:
                AnyValue: {}
            - target: ${'$'}.components.schemas.Product.allOf
              update:
                type: object
                required:
                - id
                properties:
                  id:
                    type: '#/components/schemas/AnyValue'
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(specContent, "", overlayContent = overlayContent).toFeature()
            val scenario = feature.scenarios.first { it.path == "/product" }
            val resolvedBodyPattern = resolvedHop(scenario.httpResponsePattern.body, scenario.resolver) as JSONObjectPattern

            assertThat(listOf("description", "id")).allSatisfy {
                assertKeyIsRequired(resolvedBodyPattern, it)
            }
            assertKeyIsOptional(resolvedBodyPattern, "name")
        }

        @Test
        fun `should be applied to all matching json paths specified in the target`() {
            val specContent = """
            openapi: 3.0.3
            info:
              title: Product and Order API
              version: 1.0.0
            paths:
              /product:
                get:
                  responses:
                    '200':
                      description: Product retrieved successfully
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/Product'
              /order:
                get:
                  responses:
                    '200':
                      description: Order retrieved successfully
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/Order'
            components:
              schemas:
                Base:
                  type: object
                  properties:
                    description:
                      type: string
                    id:
                      type: string
                Product:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Base'
                    - type: object
                      properties:
                        name:
                          type: string
                Order:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Base'
                    - type: object
                      properties:
                        quantity:
                          type: integer
            """.trimIndent()
            val overlayContent = """
            overlay: 1.0.0
            actions:
            - target: ${'$'}.components.schemas
              update:
                AnyValue: {}
            - target: ${'$'}.components.schemas.['Product','Order'].allOf
              update:
                type: object
                required:
                  - description
                  - id
                properties:
                  description:
                    type: "#/components/schemas/AnyValue"
                  id:
                    type: "#/components/schemas/AnyValue"
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(specContent, "", overlayContent = overlayContent).toFeature()
            val requiredKeys = listOf("description", "id")

            val productScenario = feature.scenarios.first { it.path == "/product" }
            val productResolvedBodyPattern = resolvedHop(productScenario.httpResponsePattern.body, productScenario.resolver) as JSONObjectPattern
            assertThat(requiredKeys).allSatisfy {
                assertKeyIsRequired(productResolvedBodyPattern, it)
            }
            assertKeyIsOptional(productResolvedBodyPattern, "name")


            val orderScenario = feature.scenarios.first { it.path == "/order" }
            val orderResolvedBodyPattern = resolvedHop(orderScenario.httpResponsePattern.body, orderScenario.resolver) as JSONObjectPattern
            assertThat(requiredKeys).allSatisfy {
                assertKeyIsRequired(orderResolvedBodyPattern, it)
            }
            assertKeyIsOptional(orderResolvedBodyPattern, "quantity")
        }
    }

    @Nested
    inner class RemoveTest {
        @Test
        fun `should mark properties as optional previously required in the resolved allOf schema`() {
            val specContent = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /product:
                get:
                  responses:
                    '200':
                      description: Product retrieved successfully
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/Product'
            components:
              schemas:
                BaseProduct:
                  type: object
                  properties:
                    description:
                      type: string
                    id:
                      type: string
                  required:
                    - description
                    - id
                Product:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/BaseProduct'
            """.trimIndent()
            val overlayContent = """
            overlay: 1.0.0
            actions:
            - target: ${'$'}.components.schemas.BaseProduct.required[?(@ == 'description')]
              remove: true
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(specContent, "", overlayContent = overlayContent).toFeature()
            val scenario = feature.scenarios.first { it.path == "/product" }
            val resolvedBodyPattern = resolvedHop(scenario.httpResponsePattern.body, scenario.resolver) as JSONObjectPattern

            assertKeyIsRequired(resolvedBodyPattern, "id")
            assertKeyIsOptional(resolvedBodyPattern, "description")
        }

        @Test
        fun `should mark properties as optional previously required in the resolved schema for Array`() {
            val specContent = """
            openapi: 3.0.3
            info:
              title: Product API
              version: 1.0.0
            paths:
              /products:
                get:
                  responses:
                    '200':
                      description: Products retrieved successfully
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              ${'$'}ref: '#/components/schemas/Product'
            components:
              schemas:
                BaseProduct:
                  type: object
                  properties:
                    description:
                      type: string
                    id:
                      type: string
                  required:
                    - description
                    - id
                Product:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/BaseProduct'
                    - type: object
                      properties:
                        name:
                          type: string
            """.trimIndent()
            val overlayContent = """
            overlay: 1.0.0
            actions:
            - target: ${'$'}.components.schemas.BaseProduct.required[?(@ == 'description')]
              remove: true
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(specContent, "", overlayContent = overlayContent).toFeature()
            val scenario = feature.scenarios.first { it.path == "/products" }
            val bodyPattern = scenario.httpResponsePattern.body as ListPattern
            val resolvedBodyPattern = resolvedHop(bodyPattern.pattern, scenario.resolver) as JSONObjectPattern

            assertKeyIsRequired(resolvedBodyPattern, "id")
            assertKeyIsOptional(resolvedBodyPattern, "description")
        }

        @Test
        fun `should be applied to all matching json paths specified in the target`() {
            val specContent = """
            openapi: 3.0.3
            info:
              title: Product and Order API
              version: 1.0.0
            paths:
              /product:
                get:
                  responses:
                    '200':
                      description: Product retrieved successfully
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/Product'
              /order:
                get:
                  responses:
                    '200':
                      description: Order retrieved successfully
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/Order'
            components:
              schemas:
                Base:
                  type: object
                  properties:
                    description:
                      type: string
                    id:
                      type: string
                  required:
                    - description
                    - id
                Product:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Base'
                    - type: object
                      properties:
                        status:
                          type: string
                      required:
                        - status
                Order:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/Base'
                    - type: object
                      properties:
                        status:
                          type: string
                      required:
                        - status
                """.trimIndent()
            val overlayContent = """
            overlay: 1.0.0
            actions:
            - target: ${'$'}.components.schemas.['Product','Order'].allOf[1].required[?(@ == 'status')]
              remove: true
            """.trimIndent()

            val feature = OpenApiSpecification.fromYAML(specContent, "", overlayContent = overlayContent).toFeature()
            val optionalKeys = listOf("status")
            val requiredKeys = listOf("description", "id")

            val productScenario = feature.scenarios.first { it.path == "/product" }
            val productResolvedBodyPattern = resolvedHop(productScenario.httpResponsePattern.body, productScenario.resolver) as JSONObjectPattern
            assertThat(requiredKeys).allSatisfy {
                assertKeyIsRequired(productResolvedBodyPattern, it)
            }
            assertThat(optionalKeys).allSatisfy {
                assertKeyIsOptional(productResolvedBodyPattern, it)
            }

            val orderScenario = feature.scenarios.first { it.path == "/order" }
            val orderResolvedBodyPattern = resolvedHop(orderScenario.httpResponsePattern.body, orderScenario.resolver) as JSONObjectPattern
            assertThat(requiredKeys).allSatisfy {
                assertKeyIsRequired(orderResolvedBodyPattern, it)
            }
            assertThat(optionalKeys).allSatisfy {
                assertKeyIsOptional(orderResolvedBodyPattern, it)
            }
        }
    }
}