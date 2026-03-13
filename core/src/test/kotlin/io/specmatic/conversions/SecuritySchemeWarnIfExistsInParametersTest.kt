package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.Result
import io.specmatic.toViolationReportString
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.parameters.QueryParameter
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SecuritySchemeWarnIfExistsInParametersTest {
    private fun parameterWithContext(parameter: io.swagger.v3.oas.models.parameters.Parameter, collectorContext: CollectorContext): ParameterWithContext<io.swagger.v3.oas.models.parameters.Parameter> {
        return ParameterWithContext(parameter, collectorContext)
    }

    @Test
    fun `APIKeyInHeaderSecurityScheme should collect error if header param exists`() {
        val scheme = APIKeyInHeaderSecurityScheme("X-API-KEY", null)
        val context = CollectorContext()
        val parameters = listOf(
            parameterWithContext(parameter = HeaderParameter().apply { name = "X-API-KEY" }, collectorContext = context.at("parameters").at(0)),
            parameterWithContext(parameter = QueryParameter().apply { name = "apiKey" }, collectorContext = context.at("parameters").at(1))
        )

        scheme.collectErrorIfExistsInParameters(parameters)
        assertThat(context.toCollector().getEntries()).hasSize(1)
        assertThat(context.toCollector().toResult().reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "parameters[0].name",
                details = "Found header parameter with same name as header api-key security scheme \"X-API-KEY\"",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        )
    }

    @Test
    fun `APIKeyInQueryParamSecurityScheme should print warning if query param exists`() {
        val scheme = APIKeyInQueryParamSecurityScheme("apiKey", null)
        val context = CollectorContext()
        val parameters = listOf(
            parameterWithContext(parameter = HeaderParameter().apply { name = "X-API-KEY" }, collectorContext = context.at("parameters").at(0)),
            parameterWithContext(parameter = QueryParameter().apply { name = "apiKey" }, collectorContext = context.at("parameters").at(1))
        )

        scheme.collectErrorIfExistsInParameters(parameters)
        assertThat(context.toCollector().getEntries()).hasSize(1)
        assertThat(context.toCollector().toResult().reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "parameters[1].name",
                details = "Found query parameter with same name as query api-key security scheme \"apiKey\"",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        )
    }

    @Test
    fun `BasicAuthSecurityScheme should print warning if Authorization header param exists`() {
        val scheme = BasicAuthSecurityScheme()
        val context = CollectorContext()
        val parameters = listOf(
            parameterWithContext(parameter = HeaderParameter().apply { name = AUTHORIZATION }, collectorContext = context.at("parameters").at(0)),
            parameterWithContext(parameter = HeaderParameter().apply { name = "X-API-KEY" }, collectorContext = context.at("parameters").at(1)),
            parameterWithContext(parameter = QueryParameter().apply { name = "apiKey" }, collectorContext = context.at("parameters").at(2))
        )

        scheme.collectErrorIfExistsInParameters(parameters)
        assertThat(context.toCollector().getEntries()).hasSize(1)
        assertThat(context.toCollector().toResult().reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "parameters[0].name",
                details = "Found header parameter with same name as Basic Auth security scheme",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        )
    }

    @Test
    fun `BearerSecurityScheme should print warning if Authorization header param exists`() {
        val scheme = BearerSecurityScheme()
        val context = CollectorContext()
        val parameters = listOf(
            parameterWithContext(parameter = HeaderParameter().apply { name = AUTHORIZATION }, collectorContext = context.at("parameters").at(0)),
            parameterWithContext(parameter = HeaderParameter().apply { name = "X-API-KEY" }, collectorContext = context.at("parameters").at(1)),
            parameterWithContext(parameter = QueryParameter().apply { name = "apiKey" }, collectorContext = context.at("parameters").at(2))
        )

        scheme.collectErrorIfExistsInParameters(parameters)
        assertThat(context.toCollector().getEntries()).hasSize(1)
        assertThat(context.toCollector().toResult().reportString()).isEqualToIgnoringWhitespace(
            toViolationReportString(
                breadCrumb = "parameters[0].name",
                details = "Found header parameter with same name as Bearer Authorization security scheme",
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
            parameterWithContext(parameter = HeaderParameter().apply { name = AUTHORIZATION }, collectorContext = context.at("parameters").at(0)),
            parameterWithContext(parameter = HeaderParameter().apply { name = "X-API-KEY" }, collectorContext = context.at("parameters").at(1)),
            parameterWithContext(parameter = QueryParameter().apply { name = "apiKey" }, collectorContext = context.at("parameters").at(2))
        )

        composite.collectErrorIfExistsInParameters(parameters)
        assertThat(context.toCollector().getEntries()).hasSize(2)
        assertThat(context.toCollector().toResult().reportString()).isEqualToIgnoringWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "parameters[1].name",
                details = "Found header parameter with same name as header api-key security scheme \"X-API-KEY\"",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "parameters[0].name",
                details = "Found header parameter with same name as Bearer Authorization security scheme",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        }
        """.trimIndent())
    }

    @Test
    fun `no warning if header security scheme and query param have same name`() {
        val scheme = APIKeyInHeaderSecurityScheme("X-API-KEY", null)
        val context = CollectorContext()
        val parameters = listOf(parameterWithContext(parameter = QueryParameter().apply { name = "X-API-KEY" }, collectorContext = context.at("parameters").at(2)))

        scheme.collectErrorIfExistsInParameters(parameters)
        assertThat(context.toCollector().getEntries()).isEmpty()
        assertThat(context.toCollector().toResult()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `no warning if there are no security schemes`() {
        val scheme = NoSecurityScheme()
        val context = CollectorContext()
        val parameters = listOf(
            parameterWithContext(parameter = HeaderParameter().apply { name = AUTHORIZATION }, collectorContext = context.at("parameters").at(0)),
            parameterWithContext(parameter = HeaderParameter().apply { name = "X-API-KEY" }, collectorContext = context.at("parameters").at(1)),
            parameterWithContext(parameter = QueryParameter().apply { name = "apiKey" }, collectorContext = context.at("parameters").at(2))
        )

        scheme.collectErrorIfExistsInParameters(parameters)
        assertThat(context.toCollector().getEntries()).isEmpty()
        assertThat(context.toCollector().toResult()).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `no warning if there are security schemes and no parameters`() {
        val context = CollectorContext()
        val parameters = emptyList<ParameterWithContext<io.swagger.v3.oas.models.parameters.Parameter>>()
        val composite = CompositeSecurityScheme(listOf(
            APIKeyInHeaderSecurityScheme("X-API-KEY", null),
            APIKeyInQueryParamSecurityScheme("api_key", null),
            BearerSecurityScheme(),
            BasicAuthSecurityScheme()
        ))

        composite.collectErrorIfExistsInParameters(parameters)
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
            parameterWithContext(parameter = HeaderParameter().apply { name = "SOME-OTHER-HEADER" }, collectorContext = context.at("parameters").at(1)),
            parameterWithContext(parameter = QueryParameter().apply { name = "some_other_query" }, collectorContext = context.at("parameters").at(2))
        )

        composite.collectErrorIfExistsInParameters(parameters)
        assertThat(context.toCollector().getEntries()).isEmpty()
        assertThat(context.toCollector().toResult()).isInstanceOf(Result.Success::class.java)
    }
}
