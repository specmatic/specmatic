package io.specmatic.core

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.Configuration.Companion.DEFAULT_PROXY_HOST
import io.specmatic.core.Configuration.Companion.DEFAULT_PROXY_PORT
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.components.Adapter
import java.io.File

data class ProxyConfig(
    val host: String? = null,
    val port: Int? = null,
    val targetUrl: String,
    val baseUrl: String? = null,
    val timeoutInMilliseconds: Long? = null,
    val adapters: Adapter? = null,
    val consumes: List<String>? = null,
    val https: HttpsConfiguration? = null,
    val outputDirectory: String? = null,
) {
    @JsonIgnore
    fun getHostOrDefault(default: String = DEFAULT_PROXY_HOST): String = host ?: default

    @JsonIgnore
    fun getPortOrDefault(default: Int = DEFAULT_PROXY_PORT.toInt()): Int = port.takeUnless { it == 0 || it == -1 } ?: default

    @JsonIgnore
    fun getTimeoutInMillisecondsOrDefault(default: Long = DEFAULT_TIMEOUT_IN_MILLISECONDS): Long = timeoutInMilliseconds ?: default

    @JsonIgnore
    fun getTargetUrl(orElse: () -> String): String = targetUrl.takeUnless(String::isBlank) ?: orElse()

    @Suppress("unused")
    @JsonIgnore
    fun mockSpecifications(): List<File> = consumes?.map(::File)?.map(File::getCanonicalFile).orEmpty()

    @JsonIgnore
    fun getHttpsConfig(): HttpsConfiguration? = https

    @JsonIgnore
    fun getRecordingsDirectory(default: File = DEFAULT_OUT_DIR): File = outputDirectory?.let(::File) ?: default

    @JsonIgnore
    fun getHooks(): Map<String, String> = adapters?.hooks.orEmpty()

    companion object {
        private val DEFAULT_OUT_DIR = File(".").canonicalFile
    }
}
