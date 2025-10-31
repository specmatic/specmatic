package io.specmatic.conversions.links

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
                    OpenApiServerVariable.from(value).addDetails("Invalid Server variables", key)
                }.mapFold()
            }?.unwrapOrReturn {
                return it.cast()
            }

            return HasValue(
                OpenApiServerObject(
                    url = server.url,
                    description = server.description,
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
            return HasValue(
                OpenApiServerVariable(
                    default = variable.default,
                    description = variable.description,
                    enum = variable.enum.orEmpty().toSet(),
                ),
            )
        }
    }
}
