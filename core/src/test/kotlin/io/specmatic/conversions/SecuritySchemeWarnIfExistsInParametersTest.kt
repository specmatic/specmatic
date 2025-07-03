package io.specmatic.conversions

import io.specmatic.stub.captureStandardOutput
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.QueryParameter
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecuritySchemeWarnIfExistsInParametersTest {
    @Test
    fun `APIKeyInHeaderSecurityScheme should print warning if header param exists`() {
        val scheme = APIKeyInHeaderSecurityScheme("X-API-KEY", null)
        val parameters = listOf(HeaderParameter().apply { name = "X-API-KEY" })
        val (output, _) = captureStandardOutput {
            scheme.warnIfExistsInParameters(parameters, "GET", "/path")
        }
        assertTrue(output.contains("API key with header X-API-KEY"))
    }

    @Test
    fun `APIKeyInQueryParamSecurityScheme should print warning if query param exists`() {
        val scheme = APIKeyInQueryParamSecurityScheme("api_key", null)
        val parameters = listOf(QueryParameter().apply { name = "api_key" })
        val (output, _) = captureStandardOutput {
            scheme.warnIfExistsInParameters(parameters, "POST", "/path")
        }
        assertTrue(output.contains("API key with query parameter api_key"))
    }

    @Test
    fun `BasicAuthSecurityScheme should print warning if Authorization header param exists`() {
        val scheme = BasicAuthSecurityScheme()
        val parameters = listOf(HeaderParameter().apply { name = AUTHORIZATION })
        val (output, _) = captureStandardOutput {
            scheme.warnIfExistsInParameters(parameters, "PUT", "/path")
        }
        assertTrue(output.contains("Basic Auth"))
    }

    @Test
    fun `BearerSecurityScheme should print warning if Authorization header param exists`() {
        val scheme = BearerSecurityScheme()
        val parameters = listOf(HeaderParameter().apply { name = AUTHORIZATION })
        val (output, _) = captureStandardOutput {
            scheme.warnIfExistsInParameters(parameters, "DELETE", "/path")
        }
        assertTrue(output.contains("Bearer Authorization"))
    }

    @Test
    fun `CompositeSecurityScheme should print warnings for all child schemes`() {
        val headerScheme = APIKeyInHeaderSecurityScheme("X-API-KEY", null)
        val bearerScheme = BearerSecurityScheme()
        val composite = CompositeSecurityScheme(listOf(headerScheme, bearerScheme))
        val parameters = listOf(HeaderParameter().apply { name = "X-API-KEY" }, HeaderParameter().apply { name = AUTHORIZATION })
        val (output, _) = captureStandardOutput {
            composite.warnIfExistsInParameters(parameters, "PATCH", "/path")
        }
        assertTrue(output.contains("API key with header X-API-KEY"))
        assertTrue(output.contains("Bearer Authorization"))
    }

    @Test
    fun `no warning if header security scheme and query param have same name`() {
        val scheme = APIKeyInHeaderSecurityScheme("X-API-KEY", null)
        val parameters = listOf(QueryParameter().apply { name = "X-API-KEY" })
        val (output, _) = captureStandardOutput {
            scheme.warnIfExistsInParameters(parameters, "GET", "/path")
        }
        assertTrue(output.isBlank(), "Expected no warning, but got: $output")
    }

    @Test
    fun `no warning if there are no security schemes`() {
        val composite = NoSecurityScheme()
        val parameters = listOf(HeaderParameter().apply { name = "X-API-KEY" })
        val (output, _) = captureStandardOutput {
            composite.warnIfExistsInParameters(parameters, "GET", "/path")
        }
        assertTrue(output.isBlank(), "Expected no warning, but got: $output")
    }

    @Test
    fun `no warning if there are security schemes and no parameters`() {
        val composite = CompositeSecurityScheme(listOf(
            APIKeyInHeaderSecurityScheme("X-API-KEY", null),
            APIKeyInQueryParamSecurityScheme("api_key", null),
            BearerSecurityScheme(),
            BasicAuthSecurityScheme()
        ))
        val parameters = emptyList<Parameter>()
        val (output, _) = captureStandardOutput {
            composite.warnIfExistsInParameters(parameters, "GET", "/path")
        }
        assertTrue(output.isBlank(), "Expected no warning, but got: $output")
    }

    @Test
    fun `no warning if there are security schemes and parameters but no naming collisions`() {
        val composite = CompositeSecurityScheme(listOf(
            APIKeyInHeaderSecurityScheme("X-API-KEY", null),
            APIKeyInQueryParamSecurityScheme("api_key", null),
            BearerSecurityScheme(),
            BasicAuthSecurityScheme()
        ))
        val parameters = listOf(
            HeaderParameter().apply { name = "SOME-OTHER-HEADER" },
            QueryParameter().apply { name = "some_other_query" }
        )
        val (output, _) = captureStandardOutput {
            composite.warnIfExistsInParameters(parameters, "GET", "/path")
        }
        assertTrue(output.isBlank(), "Expected no warning, but got: $output")
    }
}
