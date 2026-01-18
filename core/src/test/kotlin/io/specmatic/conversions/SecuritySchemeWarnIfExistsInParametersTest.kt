package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.Result
import io.specmatic.toViolationReportString
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.QueryParameter
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SecuritySchemeWarnIfExistsInParametersTest {
    @Test
    fun `APIKeyInHeaderSecurityScheme should collect error if header param exists`() {
        val scheme = APIKeyInHeaderSecurityScheme("X-API-KEY", null)
        val context = CollectorContext()
        val parameters = listOf(
            IndexedValue(index = 0, value = HeaderParameter().apply { name = "X-API-KEY" }),
            IndexedValue(index = 1, value = QueryParameter().apply { name = "apiKey" })
        )

        scheme.collectErrorIfExistsInParameters(parameters, context)
        assertThat(context.toCollector().getEntries()).hasSize(1)
        assertThat(context.toCollector().toResult().reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "parameters[0].name",
                details = "The header/query param named \"X-API-KEY\" for security scheme named \"X-API-KEY\" was explicitly re-defined as a parameter. The parameter will be ignored, and should be removed.",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        )
    }

    @Test
    fun `APIKeyInQueryParamSecurityScheme should print warning if query param exists`() {
        val scheme = APIKeyInQueryParamSecurityScheme("apiKey", null)
        val context = CollectorContext()
        val parameters = listOf(
            IndexedValue(index = 0, value = HeaderParameter().apply { name = "X-API-KEY" }),
            IndexedValue(index = 1, value = QueryParameter().apply { name = "apiKey" })
        )

        scheme.collectErrorIfExistsInParameters(parameters, context)
        assertThat(context.toCollector().getEntries()).hasSize(1)
        assertThat(context.toCollector().toResult().reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "parameters[1].name",
                details = "The header/query param named \"apiKey\" for security scheme named \"apiKey\" was explicitly re-defined as a parameter. The parameter will be ignored, and should be removed.",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        )
    }

    @Test
    fun `BasicAuthSecurityScheme should print warning if Authorization header param exists`() {
        val scheme = BasicAuthSecurityScheme()
        val context = CollectorContext()
        val parameters = listOf(
            IndexedValue(index = 0, value = HeaderParameter().apply { name = AUTHORIZATION }),
            IndexedValue(index = 1, value = HeaderParameter().apply { name = "X-API-KEY" }),
            IndexedValue(index = 2, value = QueryParameter().apply { name = "apiKey" })
        )

        scheme.collectErrorIfExistsInParameters(parameters, context)
        assertThat(context.toCollector().getEntries()).hasSize(1)
        assertThat(context.toCollector().toResult().reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "parameters[0].name",
                details = "The header/query param named \"Authorization\" for security scheme named \"basic\" was explicitly re-defined as a parameter. The parameter will be ignored, and should be removed.",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        )
    }

    @Test
    fun `BearerSecurityScheme should print warning if Authorization header param exists`() {
        val scheme = BearerSecurityScheme()
        val context = CollectorContext()
        val parameters = listOf(
            IndexedValue(index = 0, value = HeaderParameter().apply { name = AUTHORIZATION }),
            IndexedValue(index = 1, value = HeaderParameter().apply { name = "X-API-KEY" }),
            IndexedValue(index = 2, value = QueryParameter().apply { name = "apiKey" })
        )

        scheme.collectErrorIfExistsInParameters(parameters, context)
        assertThat(context.toCollector().getEntries()).hasSize(1)
        assertThat(context.toCollector().toResult().reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "parameters[0].name",
                details = "The header/query param named \"Authorization\" for security scheme named \"bearer\" was explicitly re-defined as a parameter. The parameter will be ignored, and should be removed.",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        )
    }

    @Test
    fun `CompositeSecurityScheme should print warnings for all child schemes`() {
        val headerScheme = APIKeyInHeaderSecurityScheme("X-API-KEY", null)
        val bearerScheme = BearerSecurityScheme()
        val composite = CompositeSecurityScheme(listOf(headerScheme, bearerScheme))
        val context = CollectorContext()
        val parameters = listOf(
            IndexedValue(index = 0, value = HeaderParameter().apply { name = AUTHORIZATION }),
            IndexedValue(index = 1, value = HeaderParameter().apply { name = "X-API-KEY" }),
            IndexedValue(index = 2, value = QueryParameter().apply { name = "apiKey" })
        )

        composite.collectErrorIfExistsInParameters(parameters, context)
        assertThat(context.toCollector().getEntries()).hasSize(2)
        assertThat(context.toCollector().toResult().reportString()).isEqualToIgnoringWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "parameters[1].name",
                details = "The header/query param named \"X-API-KEY\" for security scheme named \"X-API-KEY\" was explicitly re-defined as a parameter. The parameter will be ignored, and should be removed.",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "parameters[0].name",
                details = "The header/query param named \"Authorization\" for security scheme named \"bearer\" was explicitly re-defined as a parameter. The parameter will be ignored, and should be removed.",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        }
        """.trimIndent())
    }

    @Test
    fun `no warning if header security scheme and query param have same name`() {
        val scheme = APIKeyInHeaderSecurityScheme("X-API-KEY", null)
        val context = CollectorContext()
        val parameters = listOf(IndexedValue(index = 2, value = QueryParameter().apply { name = "X-API-KEY" }))

        scheme.collectErrorIfExistsInParameters(parameters, context)
        assertThat(context.toCollector().getEntries()).isEmpty()
        assertThat(context.toCollector().toResult()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `no warning if there are no security schemes`() {
        val scheme = NoSecurityScheme()
        val context = CollectorContext()
        val parameters = listOf(
            IndexedValue(index = 0, value = HeaderParameter().apply { name = AUTHORIZATION }),
            IndexedValue(index = 1, value = HeaderParameter().apply { name = "X-API-KEY" }),
            IndexedValue(index = 2, value = QueryParameter().apply { name = "apiKey" })
        )

        scheme.collectErrorIfExistsInParameters(parameters, context)
        assertThat(context.toCollector().getEntries()).isEmpty()
        assertThat(context.toCollector().toResult()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `no warning if there are security schemes and no parameters`() {
        val context = CollectorContext()
        val parameters = emptyList<IndexedValue<Parameter>>()
        val composite = CompositeSecurityScheme(listOf(
            APIKeyInHeaderSecurityScheme("X-API-KEY", null),
            APIKeyInQueryParamSecurityScheme("api_key", null),
            BearerSecurityScheme(),
            BasicAuthSecurityScheme()
        ))

        composite.collectErrorIfExistsInParameters(parameters, context)
        assertThat(context.toCollector().getEntries()).isEmpty()
        assertThat(context.toCollector().toResult()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `no warning if there are security schemes and parameters but no naming collisions`() {
        val composite = CompositeSecurityScheme(listOf(
            APIKeyInHeaderSecurityScheme("X-API-KEY", null),
            APIKeyInQueryParamSecurityScheme("api_key", null),
            BearerSecurityScheme(),
            BasicAuthSecurityScheme()
        ))

        val context = CollectorContext()
        val parameters = listOf(
            IndexedValue(index = 1, value = HeaderParameter().apply { name = "SOME-OTHER-HEADER" }),
            IndexedValue(index = 2, value = QueryParameter().apply { name = "some_other_query" })
        )

        composite.collectErrorIfExistsInParameters(parameters, context)
        assertThat(context.toCollector().getEntries()).isEmpty()
        assertThat(context.toCollector().toResult()).isInstanceOf(Result.Success::class.java)
    }
}
