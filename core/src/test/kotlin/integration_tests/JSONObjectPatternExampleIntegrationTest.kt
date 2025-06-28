package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import io.specmatic.core.HttpResponse
import io.specmatic.core.value.Value
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested

class JSONObjectPatternExampleIntegrationTest {

    @Nested
    inner class RequestExampleTests {

        @Test
        fun `should use JSON object example in request when running contract tests`() {
            val contract = OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.0
                info:
                  title: User API
                  version: "1.0"
                paths:
                  /users:
                    post:
                      summary: Create user
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                                - name
                                - email
                              properties:
                                name:
                                  type: string
                                email:
                                  type: string
                                age:
                                  type: integer
                              example:
                                name: "John Doe"
                                email: "john@example.com"
                                age: 30
                      responses:
                        '201':
                          description: User created
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  id:
                                    type: integer
                                  message:
                                    type: string
                                example:
                                  id: 123
                                  message: "User created successfully"
                """.trimIndent(), ""
            ).toFeature()

            val contractTests = contract.generateContractTests(emptyList())
            var requestUsedExample = false
            
            contractTests.forEach { scenario ->
                val result = scenario.runTest(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        // Verify that the request body uses the example
                        val requestBody = request.body
                        if (requestBody is io.specmatic.core.value.JSONObjectValue) {
                            val name = requestBody.findFirstChildByPath("name")?.toStringLiteral()
                            val email = requestBody.findFirstChildByPath("email")?.toStringLiteral()
                            val age = requestBody.findFirstChildByPath("age")
                            
                            if (name == "John Doe" && email == "john@example.com" && age?.toStringLiteral() == "30") {
                                requestUsedExample = true
                            }
                        }
                        
                        return HttpResponse(201, parsedJSONObject("""{"id": 123, "message": "User created successfully"}"""))
                    }

                    override fun setServerState(serverState: Map<String, Value>) {}
                }).first
                
                assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
            }
            
            // Verify that at least one test used the example
            assertThat(requestUsedExample).withFailMessage("Expected request to use the JSON object example").isTrue()
        }

        @Test
        fun `should use JSON object example with nested objects in request when running contract tests`() {
            val contract = OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.0
                info:
                  title: Order API
                  version: "1.0"
                paths:
                  /orders:
                    post:
                      summary: Create order
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                                - customer
                                - items
                              properties:
                                customer:
                                  type: object
                                  properties:
                                    name:
                                      type: string
                                    id:
                                      type: integer
                                items:
                                  type: array
                                  items:
                                    type: object
                                    properties:
                                      productId:
                                        type: integer
                                      quantity:
                                        type: integer
                              example:
                                customer:
                                  name: "Alice Smith"
                                  id: 456
                                items:
                                  - productId: 101
                                    quantity: 2
                                  - productId: 102
                                    quantity: 1
                      responses:
                        '201':
                          description: Order created
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  orderId:
                                    type: integer
                                example:
                                  orderId: 789
                """.trimIndent(), ""
            ).toFeature()

            val contractTests = contract.generateContractTests(emptyList())
            var requestUsedExample = false
            
            contractTests.forEach { scenario ->
                val result = scenario.runTest(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        // Verify that the request body uses the nested object example
                        val requestBody = request.body
                        if (requestBody is io.specmatic.core.value.JSONObjectValue) {
                            val customer = requestBody.findFirstChildByPath("customer")
                            val customerName = customer?.let { it as? io.specmatic.core.value.JSONObjectValue }
                                ?.findFirstChildByPath("name")?.toStringLiteral()
                            
                            if (customerName == "Alice Smith") {
                                requestUsedExample = true
                            }
                        }
                        
                        return HttpResponse(201, parsedJSONObject("""{"orderId": 789}"""))
                    }

                    override fun setServerState(serverState: Map<String, Value>) {}
                }).first
                
                assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
            }
            
            // Verify that at least one test used the example
            assertThat(requestUsedExample).withFailMessage("Expected request to use the nested JSON object example").isTrue()
        }
    }

    @Nested
    inner class ResponseExampleTests {

        @Test
        fun `should use JSON object example in response when running as stub`() {
            val contract = OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.0
                info:
                  title: Product API
                  version: "1.0"
                paths:
                  /products/{id}:
                    get:
                      summary: Get product
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema:
                            type: integer
                          examples:
                            GET_PRODUCT:
                              value: 42
                      responses:
                        '200':
                          description: Product details
                          content:
                            application/json:
                              schema:
                                type: object
                                required:
                                  - id
                                  - name
                                  - price
                                properties:
                                  id:
                                    type: integer
                                  name:
                                    type: string
                                  price:
                                    type: number
                                  description:
                                    type: string
                                  category:
                                    type: object
                                    properties:
                                      id:
                                        type: integer
                                      name:
                                        type: string
                                example:
                                  id: 42
                                  name: "Premium Laptop"
                                  price: 1299.99
                                  description: "High-performance laptop for professionals"
                                  category:
                                    id: 5
                                    name: "Electronics"
                """.trimIndent(), ""
            ).toFeature()

            HttpStub(contract).use { stub ->
                val response = stub.client.execute(HttpRequest("GET", "/products/42"))
                
                assertThat(response.status).isEqualTo(200)
                
                val responseBody = response.body as io.specmatic.core.value.JSONObjectValue
                
                // Verify that the response uses the JSON object example
                assertThat(responseBody.findFirstChildByPath("id")?.toStringLiteral()).isEqualTo("42")
                assertThat(responseBody.findFirstChildByPath("name")?.toStringLiteral()).isEqualTo("Premium Laptop")
                assertThat(responseBody.findFirstChildByPath("price")?.toStringLiteral()).isEqualTo("1299.99")
                assertThat(responseBody.findFirstChildByPath("description")?.toStringLiteral())
                    .isEqualTo("High-performance laptop for professionals")
                
                // Verify nested object in example
                val category = responseBody.findFirstChildByPath("category") as io.specmatic.core.value.JSONObjectValue
                assertThat(category.findFirstChildByPath("id")?.toStringLiteral()).isEqualTo("5")
                assertThat(category.findFirstChildByPath("name")?.toStringLiteral()).isEqualTo("Electronics")
            }
        }

        @Test
        fun `should use JSON object example in array response when running as stub`() {
            val contract = OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.0
                info:
                  title: Users API
                  version: "1.0"
                paths:
                  /users/{department}:
                    get:
                      summary: List users by department
                      parameters:
                        - name: department
                          in: path
                          required: true
                          schema:
                            type: string
                          examples:
                            LIST_ENGINEERING_USERS:
                              value: "engineering"
                      responses:
                        '200':
                          description: List of users
                          content:
                            application/json:
                              schema:
                                type: array
                                items:
                                  type: object
                                  required:
                                    - id
                                    - username
                                  properties:
                                    id:
                                      type: integer
                                    username:
                                      type: string
                                    profile:
                                      type: object
                                      properties:
                                        firstName:
                                          type: string
                                        lastName:
                                          type: string
                                        email:
                                          type: string
                                example:
                                  - id: 1
                                    username: "john_doe"
                                    profile:
                                      firstName: "John"
                                      lastName: "Doe"
                                      email: "john@example.com"
                                  - id: 2
                                    username: "jane_smith"
                                    profile:
                                      firstName: "Jane"
                                      lastName: "Smith"
                                      email: "jane@example.com"
                """.trimIndent(), ""
            ).toFeature()

            HttpStub(contract).use { stub ->
                val response = stub.client.execute(HttpRequest("GET", "/users/engineering"))
                
                assertThat(response.status).isEqualTo(200)
                
                val responseBody = response.body as io.specmatic.core.value.JSONArrayValue
                
                // Verify that the response uses the JSON array with object examples
                assertThat(responseBody.list).hasSize(2)
                
                val firstUser = responseBody.list[0] as io.specmatic.core.value.JSONObjectValue
                assertThat(firstUser.findFirstChildByPath("id")?.toStringLiteral()).isEqualTo("1")
                assertThat(firstUser.findFirstChildByPath("username")?.toStringLiteral()).isEqualTo("john_doe")
                
                val firstUserProfile = firstUser.findFirstChildByPath("profile") as io.specmatic.core.value.JSONObjectValue
                assertThat(firstUserProfile.findFirstChildByPath("firstName")?.toStringLiteral()).isEqualTo("John")
                assertThat(firstUserProfile.findFirstChildByPath("email")?.toStringLiteral()).isEqualTo("john@example.com")
                
                val secondUser = responseBody.list[1] as io.specmatic.core.value.JSONObjectValue
                assertThat(secondUser.findFirstChildByPath("username")?.toStringLiteral()).isEqualTo("jane_smith")
            }
        }

        @Test
        fun `should use JSON object example with all mandatory keys when allowOnlyMandatoryKeysInJsonObject is true`() {
            val contract = OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.0
                info:
                  title: Basic API
                  version: "1.0"
                paths:
                  /items:
                    post:
                      summary: Create item
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                                - name
                                - type
                              properties:
                                name:
                                  type: string
                                type:
                                  type: string
                                description:
                                  type: string
                              example:
                                name: "Test Item"
                                type: "widget"
                                description: "This is a test item"
                      responses:
                        '201':
                          description: Item created
                          content:
                            application/json:
                              schema:
                                type: object
                                required:
                                  - id
                                  - status
                                properties:
                                  id:
                                    type: integer
                                  status:
                                    type: string
                                  metadata:
                                    type: object
                                    properties:
                                      createdAt:
                                        type: string
                                example:
                                  id: 456
                                  status: "created"
                                  metadata:
                                    createdAt: "2023-12-01T10:00:00Z"
                """.trimIndent(), ""
            ).toFeature()

            // When testing contracts, examples should be used regardless of allowOnlyMandatoryKeysInJsonObject setting
            val contractTests = contract.generateContractTests(emptyList())
            var requestUsedExample = false
            
            contractTests.forEach { scenario ->
                val result = scenario.runTest(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        val requestBody = request.body
                        if (requestBody is io.specmatic.core.value.JSONObjectValue) {
                            val name = requestBody.findFirstChildByPath("name")?.toStringLiteral()
                            val type = requestBody.findFirstChildByPath("type")?.toStringLiteral()
                            
                            if (name == "Test Item" && type == "widget") {
                                requestUsedExample = true
                            }
                        }
                        
                        return HttpResponse(201, parsedJSONObject("""{"id": 456, "status": "created", "metadata": {"createdAt": "2023-12-01T10:00:00Z"}}"""))
                    }

                    override fun setServerState(serverState: Map<String, Value>) {}
                }).first
                
                assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
            }
            
            assertThat(requestUsedExample).withFailMessage("Expected request to use the JSON object example").isTrue()
            
            // Test as stub to verify response examples work
            HttpStub(contract).use { stub ->
                val response = stub.client.execute(HttpRequest(
                    "POST", 
                    "/items",
                    body = parsedJSONObject("""{"name": "Test Item", "type": "widget", "description": "This is a test item"}""")
                ))
                
                assertThat(response.status).isEqualTo(201)
                
                val responseBody = response.body as io.specmatic.core.value.JSONObjectValue
                assertThat(responseBody.findFirstChildByPath("id")?.toStringLiteral()).isEqualTo("456")
                assertThat(responseBody.findFirstChildByPath("status")?.toStringLiteral()).isEqualTo("created")
                
                val metadata = responseBody.findFirstChildByPath("metadata") as io.specmatic.core.value.JSONObjectValue
                assertThat(metadata.findFirstChildByPath("createdAt")?.toStringLiteral()).isEqualTo("2023-12-01T10:00:00Z")
            }
        }
    }
}