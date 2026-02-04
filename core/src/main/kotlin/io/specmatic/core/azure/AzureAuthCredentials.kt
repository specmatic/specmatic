package io.specmatic.core.azure

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.git.getBearerToken
import io.specmatic.core.git.getPersonalAccessToken

class AzureAuthCredentials(private val specmaticConfig: SpecmaticConfig) : AuthCredentials {
    override fun gitCommandAuthHeaders(): List<String> {
        val azurePAT: String? = getPersonalAccessToken(specmaticConfig)

        if(azurePAT != null) {
            return listOf("-c", "http.extraHeader=Authorization: Basic ${PersonalAccessToken(azurePAT).basic()}")
        }

        val bearer: String? = getBearerToken()
        if (bearer != null) {
            return listOf("-c", "http.extraHeader=Authorization: Bearer $bearer")
        }

        return emptyList()
    }
}
