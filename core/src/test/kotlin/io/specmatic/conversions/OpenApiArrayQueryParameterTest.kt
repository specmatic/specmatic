package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.value.Value
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiArrayQueryParameterTest {

    @Test
    fun `should generate request with multiple values for an array query parameter`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_mandatory_array_query_parameter.yaml").toFeature()
        var queryParameterCount = 0
        contract.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if(request.queryParams.paramPairs.isNotEmpty()) {
                    queryParameterCount = request.queryParams.paramPairs.count { it.first == "brand_ids" }
                }
                return HttpResponse.ok("success")
            }
        })
        assertThat(queryParameterCount).isGreaterThan(1)
    }

    @Test
    fun `should generate request with integer array query parameter based on examples in spec`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_mandatory_array_query_parameter_with_examples.yaml").toFeature()
        var brandIds = emptyList<String>()
        contract.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if(request.queryParams.paramPairs.isNotEmpty()) {
                    brandIds = request.queryParams.paramPairs.filter { it.first == "brand_ids" }.map { it.second }
                }
                return HttpResponse.ok("success")
            }
        })
        assertThat(brandIds).isEqualTo(listOf("1", "2", "3"))
    }

    @Test
    fun `should generate request with integer query parameter based on externalized examples`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_mandatory_array_query_parameter.yaml").toFeature().loadExternalisedExamples()
        var brandIds = emptyList<String>()
        contract.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if(request.queryParams.paramPairs.isNotEmpty()) {
                    brandIds = request.queryParams.paramPairs.filter { it.first == "brand_ids" }.map { it.second }
                }
                return HttpResponse.ok("success")
            }
        })
        assertThat(brandIds).isEqualTo(listOf("1", "2", "3"))
    }

    @Test
    fun `should generate request with string array query parameter based on examples in spec`() {
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_string_array_query_parameter_with_examples.yaml").toFeature()
        var brandIds = emptyList<String>()
        contract.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if(request.queryParams.paramPairs.isNotEmpty()) {
                    brandIds = request.queryParams.paramPairs.filter { it.first == "category" }.map { it.second }
                }
                return HttpResponse.ok("success")
            }
        })
        assertThat(brandIds).isEqualTo(listOf("Laptop", "Mobile", "TV"))
    }

    @Test
    fun `should generate request with string array query parameter based on externalized examples`(){
        val contract = OpenApiSpecification.fromFile("src/test/resources/openapi/spec_with_string_array_query_parameter.yaml").toFeature().loadExternalisedExamples()
        var brandIds = emptyList<String>()
        contract.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if(request.queryParams.paramPairs.isNotEmpty()) {
                    brandIds = request.queryParams.paramPairs.filter { it.first == "category" }.map { it.second }
                }
                return HttpResponse.ok("success")
            }
        })
        assertThat(brandIds).isEqualTo(listOf("Book", "Headphone", "Camera"))
    }
}