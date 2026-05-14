package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
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
    val baseUrl: TemplateOrValue<String>,
    val transportKind: TemplateOrValue<McpTransport>? = null,
    val enableResiliencyTests: TemplateOrValue<Boolean> = TemplateOrValue.Value(false),
    val dictionaryFile: TemplateOrValue<String>? = null,
    val bearerToken: TemplateOrValue<String>? = null,
    val filterTools: TemplateOrValue<List<TemplateOrValue<String>>>? = null,
    val skipTools: TemplateOrValue<List<TemplateOrValue<String>>>? = null,
) {
    @JsonIgnore
    fun getBaseUrl(): String = baseUrl.resolve()

    @JsonIgnore
    fun getTransportKindOrNull(): McpTransport? = transportKind?.resolve()

    @JsonIgnore
    fun isResiliencyTestsEnabled(): Boolean = enableResiliencyTests.resolve()

    @JsonIgnore
    fun getDictionaryFileOrNull(): String? = dictionaryFile?.resolve()

    @JsonIgnore
    fun getBearerTokenOrNull(): String? = bearerToken?.resolve()

    @JsonIgnore
    fun getFilterToolsOrNull(): List<String>? = filterTools?.resolveFully()

    @JsonIgnore
    fun getSkipToolsOrNull(): List<String>? = skipTools?.resolveFully()

    @JsonIgnore
    fun getDictionaryIfExists(): File? = getDictionaryFileOrNull()?.let(::File)
}

data class McpConfiguration(val test: TemplateOrValue<McpTestConfiguration>) {
    @JsonIgnore
    fun getTestConfiguration(): McpTestConfiguration = test.resolve()
}
