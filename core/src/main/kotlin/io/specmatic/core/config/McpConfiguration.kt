package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import java.io.File

enum class McpTransport(private val value: String) {
    STREAMABLE_HTTP("STREAMABLE_HTTP"),
    STDIO("STDIO");

    @JsonValue
    fun value(): String = value

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String?): McpTransport = entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: STREAMABLE_HTTP
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class McpTestConfiguration(
    val baseUrl: String? = null,
    val transportKind: McpTransport = McpTransport.STREAMABLE_HTTP,
    val enableResiliencyTests: Boolean = false,
    val dictionaryFile: String? = null,
    val bearerToken: String? = null,
    val filterTools: List<String>? = null,
    val skipTools: List<String>? = null,
    val command: String? = null,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val args: List<String> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val env: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): McpTestConfiguration {
        return copy(
            dictionaryFile = dictionaryFile?.let { mapper.child("dictionaryFile").map(it, configDirectory) },
            workingDirectory = workingDirectory?.let { mapper.child("workingDirectory").map(it, configDirectory) }
        )
    }

    @JsonIgnore
    fun getDictionaryIfExists(): File? = dictionaryFile?.let(::File)
}

data class McpConfiguration(val test: McpTestConfiguration) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): McpConfiguration {
        return copy(test = test.mapPaths(mapper.child("test"), configDirectory))
    }
}
