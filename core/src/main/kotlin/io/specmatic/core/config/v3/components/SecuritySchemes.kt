package io.specmatic.core.config.v3.components

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

enum class SecuritySchemeType(private val value: String) {
    OAUTH2("oauth2"),
    BASIC_AUTH("basicAuth"),
    BEARER("bearer"),
    API_KEY("apiKey");

    @JsonValue
    fun toValue(): String = value

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): SecuritySchemeType = SecuritySchemeType.entries.find { it.value == value }
            ?: throw IllegalArgumentException("Unknown type: $value")
    }
}

data class SecuritySchemeConfigurationV3(val type: SecuritySchemeType, val token: String)
