package io.specmatic.conversions.links

import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.mapFold
import io.specmatic.core.pattern.unwrapOrReturn
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.servers.ServerVariable

data class OpenApiServerObject(
    private val url: String,
    private val description: String? = null,
    private val variables: Map<String, OpenApiServerVariable> = emptyMap(),
) {
    companion object {
        fun from(server: Server): ReturnValue<OpenApiServerObject> {
            val serverVariables = server.variables?.let {
                it.mapValues { (key, value) ->
                    OpenApiServerVariable.from(value).addDetails("Invalid Server variable '$key'", "variables.$key")
                }.mapFold()
            }?.unwrapOrReturn {
                return it.breadCrumb("server").cast()
            }

            return HasValue(
                OpenApiServerObject(
                    url = server.url ?: return HasFailure("Mandatory field 'url' is missing from server object", "server.url"),
                    description = server.description?.takeUnless(String::isBlank),
                    variables = serverVariables.orEmpty(),
                ),
            )
        }
    }
}

data class OpenApiServerVariable(
    private val default: String,
    private val enum: Set<String>,
    private val description: String? = null
) {
    companion object {
        fun from(variable: ServerVariable): ReturnValue<OpenApiServerVariable> {
            if (variable.enum.isNullOrEmpty()) return HasFailure(
                message = "Property 'enum' in Server variable object can't be null or empty",
                breadCrumb = "enum",
            )

            if (variable.default == null || variable.default !in variable.enum) return HasFailure(
                message = "Property `default` in Server variable object cannot be null, must be one of ${variable.enum.joinToString(separator = ", ")}",
                breadCrumb = "default",
            )

            return HasValue(
                OpenApiServerVariable(
                    default = variable.default,
                    description = variable.description,
                    enum = variable.enum.toSet(),
                ),
            )
        }
    }
}
