package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import java.io.File

enum class McpTransport(private val value: String) {
    STREAMABLE_HTTP("STREAMABLE_HTTP");

    @JsonValue
    fun value(): String = value

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String?): McpTransport = entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: STREAMABLE_HTTP
    }
}

data class McpTestConfiguration(
    val baseUrl: String,
    val transportKind: McpTransport? = null,
    val enableResiliencyTests: Boolean = false,
    val dictionaryFile: String? = null,
    val bearerToken: String? = null,
    val filterTools: List<String>? = null,
    val skipTools: List<String>? = null,
) {
    @JsonIgnore
    fun getDictionaryIfExists(): File? = dictionaryFile?.let(::File)
}

data class McpConfiguration(val test: McpTestConfiguration)
