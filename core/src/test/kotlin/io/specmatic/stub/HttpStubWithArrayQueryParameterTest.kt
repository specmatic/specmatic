package io.specmatic.stub

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.QueryParameters
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.trimmedLinesList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HttpStubWithArrayQueryParameterTest {

    @Test
    fun `should match stub for mandatory query parameter with no expectations set`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_mandatory_array_query_parameter.yaml").toFeature()

        HttpStub(contract).use { stub ->
            val queryParameters = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "3"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).isNotEmpty
        }
    }

    @Test
    fun `should match stub for mandatory query parameter with dynamic expectation set`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_mandatory_array_query_parameter.yaml").toFeature()

        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/products",
                         "query": {
                            "brand_ids": [1,2,3]
                        }

                    },
                    "http-response": {
                        "status": 200,
                        "body": "product list"
                    }
                }
            """.trimIndent())

            val queryParameters = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "3"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isEqualTo("product list")
        }
    }

    @Test
    fun `should match stub for mandatory query parameter with externalized json expectation`() {
        createStubFromContracts(listOf("src/test/resources/openapi/spec_with_mandatory_array_query_parameter.yaml"), timeoutMillis = 0).use { stub ->
            val queryParameters = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "3"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isEqualTo("product list from externalized json")
        }
    }

    @Test
    fun `should match stub for mandatory query parameter with externalized json expectation from _data dir`() {
        createStubFromContracts(listOf("src/test/resources/openapi/spec_without_stub_data_in_examples_dir.yaml"), timeoutMillis = 0).use { stub ->
            val queryParameters = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "3"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isEqualTo("product list from externalized json")
        }
    }

    @Test
    fun `should match stub for mandatory query parameter with expectation from examples in spec`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_mandatory_array_query_parameter_with_examples.yaml").toFeature()
        HttpStub(contract).use { stub ->
            val queryParameters = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "3"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isEqualTo("product list")
        }
    }

    @Test
    fun `should match stub for string query parameter with dynamic expectation set`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_string_array_query_parameter.yaml").toFeature()

        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/products",
                         "query": {
                            "category": ["Laptop","Tablet","Phone"]
                        }

                    },
                    "http-response": {
                        "status": 200,
                        "body": "electronic product list"
                    }
                }
            """.trimIndent())

            val queryParameters = QueryParameters(paramPairs = listOf("category" to "Laptop", "category" to "Tablet", "category" to "Phone"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isEqualTo("electronic product list")
        }
    }

    @Test
    fun `should match stub for string query parameter with externalized json expectation`() {
        createStubFromContracts(listOf("src/test/resources/openapi/spec_with_string_array_query_parameter_with_stub.yaml"), timeoutMillis = 0).use { stub ->
            val queryParameters = QueryParameters(paramPairs = listOf("category" to "Pen", "category" to "Pencil", "category" to "Marker"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isEqualTo("product list from externalized stub json")
        }
    }

    @Test
    fun `should match stub for string query parameter with expectation from examples in spec`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_string_array_query_parameter_with_examples.yaml").toFeature()
        HttpStub(contract).use { stub ->
            val queryParameters = QueryParameters(paramPairs = listOf("category" to "Laptop", "category" to "Mobile", "category" to "TV"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isEqualTo("product list")
        }
    }

    @Test
    fun `should not match stub for query parameter when request does not contain all stub values and also a value which is not defined in the stub`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_mandatory_array_query_parameter.yaml").toFeature()
        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/products",
                         "query": {
                            "brand_ids": [1,2,3]
                        }

                    },
                    "http-response": {
                        "status": 200,
                        "body": "product list"
                    }
                }
            """.trimIndent())

            val queryParameters = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "4"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isNotEqualTo("product list")
            assertThat(response.headers["X-Specmatic-Type"]).isEqualTo("random")
        }
    }

    @Test
    fun `should not match stub for query parameter when request contains all stub values and also a value which is not defined in the stub`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_mandatory_array_query_parameter.yaml").toFeature()
        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/products",
                         "query": {
                            "brand_ids": [1,2,3]
                        }

                    },
                    "http-response": {
                        "status": 200,
                        "body": "product list"
                    }
                }
            """.trimIndent())

            val queryParameters = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "3", "brand_ids" to "4"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isNotEqualTo("product list")
            assertThat(response.headers["X-Specmatic-Type"]).isEqualTo("random")
        }
    }

    @Test
    fun `should not match stub for query parameter when request does not contain all the values defined in the stub`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_mandatory_array_query_parameter.yaml").toFeature()
        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/products",
                         "query": {
                            "brand_ids": [1,2,3]
                        }

                    },
                    "http-response": {
                        "status": 200,
                        "body": "product list"
                    }
                }
            """.trimIndent())

            val queryParameters = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isNotEqualTo("product list")
            assertThat(response.headers["X-Specmatic-Type"]).isEqualTo("random")
        }
    }

    @Test
    fun `should match stub for optional array query parameter with dynamic expectation set`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_optional_array_query_parameter.yaml").toFeature()

        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/products",
                         "query": {
                            "brand_ids": [1,2,3]
                        }

                    },
                    "http-response": {
                        "status": 200,
                        "body": "product list"
                    }
                }
            """.trimIndent())

            val queryParameters = QueryParameters(paramPairs = listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "3"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isEqualTo("product list")
        }
    }

    @Test
    fun `should match request which does not contain an optional array query parameter with no expectations set`() {
        val contract =
            OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_optional_array_query_parameter.yaml")
                .toFeature()

        HttpStub(contract).use { stub ->
            val response = stub.client.execute(HttpRequest("GET", "/products"))
            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isNotEmpty
        }
    }

    @Test
    fun `should not match request which contains unknown query parameter`() {
        val contract =
            OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_mandatory_array_query_parameter.yaml")
                .toFeature()

        HttpStub(contract).use { stub ->
            val queryParameters = QueryParameters(paramPairs = listOf("category_id" to "1"))
            val response = stub.client.execute(HttpRequest("GET", "/products", queryParams = queryParameters))
            assertThat(response.status).isEqualTo(400)
            assertThat(response.body.toStringLiteral().trimmedLinesList()).isEqualTo(
                """
            In scenario "get products. Response: OK"
            API: GET /products -> 200
            
              >> REQUEST.PARAMETERS.QUERY.brand_ids
              
                 Query param named brand_ids in the contract was not found in the request
              
              >> REQUEST.PARAMETERS.QUERY.category_id
              
                 Query param named category_id in the request was not in the contract
            """.trimIndent().trimmedLinesList())
        }
    }

    @Test
    fun `should match stub for enum query parameter with dynamic expectation set`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_enum_based_array_query_parameter.yaml").toFeature()

        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/orders",
                         "query": {
                            "status": ["pending","complete"]
                        }

                    },
                    "http-response": {
                        "status": 200,
                        "body": "order list"
                    }
                }
            """.trimIndent())

            val queryParameters = QueryParameters(paramPairs = listOf("status" to "pending", "status" to "complete"))
            val response = stub.client.execute(HttpRequest("GET", "/orders", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isEqualTo("order list")
        }
    }

    @Test
    fun `should not accept expectation for enum query parameter when the values supplied are not in the enum list`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_enum_based_array_query_parameter.yaml").toFeature()

        HttpStub(contract).use { stub ->
            val exception = assertThrows<NoMatchingScenario> {
                stub.setExpectation(
                    """
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/orders",
                         "query": {
                            "status": ["cancelled", "suspended"]
                        }

                    },
                    "http-response": {
                        "status": 200,
                        "body": "order list"
                    }
                }
            """.trimIndent()
                )

            }
            assertThat(exception.message).isEqualTo("""
                Error from contract src/test/resources/openapi/spec_with_enum_based_array_query_parameter.yaml

                  In scenario "get orders. Response: OK"
                  API: GET /orders -> 200
                  
                    >> REQUEST.PARAMETERS.QUERY.status
                  
                       Contract expected ("pending" or "complete") but stub contained "cancelled"
                  
                    >> REQUEST.PARAMETERS.QUERY.status
                  
                       Contract expected ("pending" or "complete") but stub contained "suspended"
            """.trimIndent())
        }
    }

    @Test
    fun `should not match stub for enum query parameter with dynamic expectation set when request does not contain all the values`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_enum_based_array_query_parameter.yaml").toFeature()

        HttpStub(contract).use { stub ->
            stub.setExpectation("""
                {
                    "http-request": {
                        "method": "GET",
                        "path": "/orders",
                         "query": {
                            "status": ["pending","complete"]
                        }

                    },
                    "http-response": {
                        "status": 200,
                        "body": "order list"
                    }
                }
            """.trimIndent())

            val queryParameters = QueryParameters(paramPairs = listOf("status" to "pending"))
            val response = stub.client.execute(HttpRequest("GET", "/orders", queryParams = queryParameters) )

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toString()).isNotEqualTo("order list")
        }
    }

}