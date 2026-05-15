package io.specmatic.core

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.Configuration.Companion.DEFAULT_PROXY_HOST
import io.specmatic.core.Configuration.Companion.DEFAULT_PROXY_PORT
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.resolveFullyOrEmpty
import io.specmatic.core.config.v3.resolveOrDefault
import io.specmatic.core.config.v3.resolveOrNull
import io.specmatic.core.config.v3.wrapFullyOrNull
import io.specmatic.core.config.v3.wrapOrNull
import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.stub.extractHost
import io.specmatic.stub.extractPort
import java.io.File

data class ProxyConfig(
    val host: TemplateOrValue<String>? = null,
    val port: TemplateOrValue<Int>? = null,
    val targetUrl: TemplateOrValue<String>,
    val baseUrl: TemplateOrValue<String>? = null,
    val timeoutInMilliseconds: TemplateOrValue<Long>? = null,
    val adapters: Adapter? = null,
    val consumes: TemplateOrValue<List<TemplateOrValue<String>>>? = null,
    val https: HttpsConfiguration? = null,
    val outputDirectory: TemplateOrValue<String>? = null,
) {
    @get:JsonIgnore
    val resolvedHost: String?
        get() = host.resolveOrNull()

    @get:JsonIgnore
    val resolvedPort: Int?
        get() = port.resolveOrNull()

    @get:JsonIgnore
    val resolvedTargetUrl: String
        get() = targetUrl.resolveOrDefault("")

    @get:JsonIgnore
    val resolvedBaseUrl: String?
        get() = baseUrl.resolveOrNull()

    @get:JsonIgnore
    val resolvedTimeoutInMilliseconds: Long?
        get() = timeoutInMilliseconds.resolveOrNull()

    @get:JsonIgnore
    val resolvedConsumes: List<String>
        get() = consumes.resolveFullyOrEmpty()

    @get:JsonIgnore
    val resolvedOutputDirectory: String?
        get() = outputDirectory.resolveOrNull()

    @JsonIgnore
    fun getHostOrDefault(default: String = DEFAULT_PROXY_HOST): String {
        val hostFromBaseUrl = resolvedBaseUrl?.let(::extractHost)
        return hostFromBaseUrl ?: resolvedHost ?: default
    }

    @JsonIgnore
    fun getPortOrDefault(default: Int = DEFAULT_PROXY_PORT.toInt()): Int {
        val portFromBaseUrl = resolvedBaseUrl?.let(::extractPort)
        return portFromBaseUrl ?: resolvedPort.takeUnless { it == 0 || it == -1 } ?: default
    }

    @JsonIgnore
    fun getTimeoutInMillisecondsOrDefault(default: Long = DEFAULT_TIMEOUT_IN_MILLISECONDS): Long = resolvedTimeoutInMilliseconds ?: default

    @JsonIgnore
    fun getTargetUrl(orElse: () -> String): String = resolvedTargetUrl.takeUnless(String::isBlank) ?: orElse()

    @Suppress("unused")
    @JsonIgnore
    fun mockSpecifications(): List<File> = resolvedConsumes.map(::File).map(File::getCanonicalFile)

    @JsonIgnore
    fun getHttpsConfig(): HttpsConfiguration? = https

    @JsonIgnore
    fun getRecordingsDirectory(default: File = DEFAULT_OUT_DIR): File = resolvedOutputDirectory?.let(::File) ?: default

    @JsonIgnore
    fun getHooks(): Map<String, String> = adapters?.hooks.orEmpty()

    companion object {
        private val DEFAULT_OUT_DIR = File(".").canonicalFile
    }
}
