package io.specmatic.core

import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueryParametersTest {

    @Test
    fun `should preserve null literal as a string value`() {
        val queryParameters = QueryParameters(mapOf("q" to "null"))

        assertThat(queryParameters.asValueMap()).containsEntry("q", StringValue("null"))
    }

    @Test
    fun `should return map with value as json array for numeric array query parameters`() {
        val queryParameters = QueryParameters(listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "3", "category_id" to "10"))
        assertThat(queryParameters.asMap()).isEqualTo(mapOf("brand_ids" to "[1, 2, 3]", "category_id" to "10"))
    }

    @Test
    fun `should return map with value as json array for query parameters`() {
        val queryParameters = QueryParameters(
            listOf("brand_ids" to "1", "brand_ids" to "2", "brand_ids" to "3", "category_id" to "10")
        )
        assertThat(queryParameters.asJsonMap()).isEqualTo(
            mapOf("brand_ids" to listOf("1", "2", "3"), "category_id" to "10")
        )
    }
}
