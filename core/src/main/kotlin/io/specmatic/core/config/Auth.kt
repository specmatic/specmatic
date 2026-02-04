package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty

data class Auth(
    @param:JsonProperty("bearer-file") val bearerFile: String = "bearer.txt",
    @param:JsonProperty("bearer-environment-variable") val bearerEnvironmentVariable: String? = null,
    @param:JsonProperty("personal-access-token") @param:JsonAlias("personalAccessToken") val personalAccessToken: String? = null
)
