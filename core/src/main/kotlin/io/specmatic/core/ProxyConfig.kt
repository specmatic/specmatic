package io.specmatic.core

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.Configuration.Companion.DEFAULT_PROXY_HOST
import io.specmatic.core.Configuration.Companion.DEFAULT_PROXY_PORT
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.resolveFully
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
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
    val adapters: TemplateOrValue<Adapter>? = null,
    val consumes: TemplateOrValue<List<TemplateOrValue<String>>>? = null,
    val https: TemplateOrValue<HttpsConfiguration>? = null,
    val outputDirectory: TemplateOrValue<String>? = null,
) {
    @JsonIgnore
    fun getHostOrDefault(default: String = DEFAULT_PROXY_HOST): String {
        val hostFromBaseUrl = baseUrl?.resolve()?.let(::extractHost)
        return hostFromBaseUrl ?: host?.resolve() ?: default
    }

    @JsonIgnore
    fun getPortOrDefault(default: Int = DEFAULT_PROXY_PORT.toInt()): Int {
        val portFromBaseUrl = baseUrl?.resolve()?.let(::extractPort)
        return portFromBaseUrl ?: port?.resolve()?.takeUnless { it == 0 || it == -1 } ?: default
    }

    @JsonIgnore
    fun getTimeoutInMillisecondsOrDefault(default: Long = DEFAULT_TIMEOUT_IN_MILLISECONDS): Long = timeoutInMilliseconds?.resolve() ?: default

    @JsonIgnore
    fun getTargetUrl(orElse: () -> String): String = targetUrl.resolve().takeUnless(String::isBlank) ?: orElse()

    @Suppress("unused")
    @JsonIgnore
    fun mockSpecifications(): List<File> = consumes?.resolveFully().orEmpty().map(::File).map(File::getCanonicalFile)

    @JsonIgnore
    fun getHttpsConfig(): HttpsConfiguration? = https?.resolve()

    @JsonIgnore
    fun getRecordingsDirectory(default: File = DEFAULT_OUT_DIR): File = outputDirectory?.resolve()?.let(::File) ?: default

    @JsonIgnore
    fun getHooks(): Map<String, String> = adapters?.resolve()?.hooks?.resolveFully().orEmpty()

    companion object {
        private val DEFAULT_OUT_DIR = File(".").canonicalFile
    }
}
