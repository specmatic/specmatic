package io.specmatic.core.config.v3.components.sources

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.ObjectNode
import io.specmatic.core.Auth

@JsonDeserialize(using = GitAuthentication.Companion.GitAuthenticationDeserializer::class)
sealed interface GitAuthentication {
    data class BearerFile(val bearerFile: String) : GitAuthentication
    data class BearerEnv(val bearerEnvironmentVariable: String) : GitAuthentication
    data class PersonalAccessToken(val personalAccessToken: String) : GitAuthentication

    fun toCommonAuth(): Auth {
        return Auth(
            bearerFile = (this as? BearerFile)?.bearerFile ?: "bearer.txt",
            bearerEnvironmentVariable = (this as? BearerEnv)?.bearerEnvironmentVariable,
            personalAccessToken = (this as? PersonalAccessToken)?.personalAccessToken
        )
    }

    companion object {
        class GitAuthenticationDeserializer : JsonDeserializer<GitAuthentication>() {
            override fun deserialize(p: JsonParser, ctxt: DeserializationContext): GitAuthentication {
                val node = p.codec.readTree<ObjectNode>(p)
                val fields = listOf(
                    "bearerFile" to node.get("bearerFile"),
                    "bearerEnvironmentVariable" to node.get("bearerEnvironmentVariable"),
                    "personalAccessToken" to node.get("personalAccessToken")
                ).filter { it.second != null }

                if (fields.isEmpty()) {
                    val message = "Exactly one authentication method must be specified: " + "bearerFile | bearerEnvironmentVariable | personalAccessToken"
                    throw JsonMappingException.from(p, message)
                }

                if (fields.size > 1) {
                    val message = "Authentication methods are mutually exclusive. Found: " + fields.joinToString { it.first }
                    throw JsonMappingException.from(p, message)
                }

                return when (fields.single().first) {
                    "bearerFile" -> BearerFile(fields.single().second.asText())
                    "bearerEnvironmentVariable" -> BearerEnv(fields.single().second.asText())
                    "personalAccessToken" -> PersonalAccessToken(fields.single().second.asText())
                    else -> error("unreachable")
                }
            }
        }
    }
}
