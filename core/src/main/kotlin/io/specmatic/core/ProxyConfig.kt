package io.specmatic.core

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.Configuration.Companion.DEFAULT_PROXY_HOST
import io.specmatic.core.Configuration.Companion.DEFAULT_PROXY_PORT
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.Adapter
import net.minidev.json.annotate.JsonIgnore
import java.io.File

data class ProxyConfig(
    val host: String? = null,
    val port: Int? = null,
    @field:JsonAlias("targetUrl")
    val target: String,
    val baseUrl: String? = null,
    val timeoutInMilliseconds: Long? = null,
    val adapters: RefOrValue<Adapter>? = null,
    @field:JsonAlias("consumes")
    val mock: List<String>? = null,
    @field:JsonAlias("https")
    val cert: RefOrValue<HttpsConfiguration>? = null,
    @field:JsonAlias("outputDirectory")
    val recordingsDirectory: String? = null,
) {
    @JsonIgnore
    fun getHostOrDefault(default: String = DEFAULT_PROXY_HOST): String = host ?: default

    @JsonIgnore
    fun getPortOrDefault(default: Int = DEFAULT_PROXY_PORT.toInt()): Int = port.takeUnless { it == 0 || it == -1 } ?: default

    @JsonIgnore
    fun getTimeoutInMillisecondsOrDefault(default: Long = DEFAULT_TIMEOUT_IN_MILLISECONDS): Long = timeoutInMilliseconds ?: default

    @JsonIgnore
    fun getTargetUrl(orElse: () -> String): String = target.takeUnless(String::isBlank) ?: orElse()

    @Suppress("unused")
    @JsonIgnore
    fun mockSpecifications(): List<File> = mock?.map(::File)?.map(File::getCanonicalFile).orEmpty()

    @JsonIgnore
    fun getHttpsConfig(): HttpsConfiguration? = cert?.getOrNull()

    @JsonIgnore
    fun getRecordingsDirectory(default: File = DEFAULT_OUT_DIR): File = recordingsDirectory?.let(::File) ?: default

    companion object {
        private val DEFAULT_OUT_DIR = File(".").canonicalFile
    }
}
