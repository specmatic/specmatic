package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.resolveFullyOrEmpty
import io.specmatic.core.config.v3.resolveOrNull
import io.specmatic.core.config.v3.wrapFullyOrNull
import io.specmatic.core.config.v3.wrapOrNull
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
    val enableResiliencyTests: TemplateOrValue<Boolean>? = null,
    val dictionaryFile: TemplateOrValue<String>? = null,
    val bearerToken: TemplateOrValue<String>? = null,
    val filterTools: TemplateOrValue<List<TemplateOrValue<String>>>? = null,
    val skipTools: TemplateOrValue<List<TemplateOrValue<String>>>? = null,
) {
    @get:JsonIgnore
    val resolvedBaseUrl: String
        get() = baseUrl.resolveOrNull().orEmpty()

    @get:JsonIgnore
    val resolvedTransportKind: McpTransport?
        get() = transportKind.resolveOrNull()

    @get:JsonIgnore
    val resolvedEnableResiliencyTests: Boolean
        get() = enableResiliencyTests.resolveOrNull() ?: false

    @get:JsonIgnore
    val resolvedDictionaryFile: String?
        get() = dictionaryFile.resolveOrNull()

    @get:JsonIgnore
    val resolvedBearerToken: String?
        get() = bearerToken.resolveOrNull()

    @get:JsonIgnore
    val resolvedFilterTools: List<String>
        get() = filterTools.resolveFullyOrEmpty()

    @get:JsonIgnore
    val resolvedSkipTools: List<String>
        get() = skipTools.resolveFullyOrEmpty()

    @JsonIgnore
    fun getDictionaryIfExists(): File? = resolvedDictionaryFile?.let(::File)
}

data class McpConfiguration(
    val test: TemplateOrValue<McpTestConfiguration>
) {
    @get:JsonIgnore
    val resolvedTest: McpTestConfiguration?
        get() = test.resolveOrNull()
}
