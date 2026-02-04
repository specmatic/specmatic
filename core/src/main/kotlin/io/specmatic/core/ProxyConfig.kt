package io.specmatic.core

import io.specmatic.core.Configuration.Companion.DEFAULT_PROXY_HOST
import io.specmatic.core.Configuration.Companion.DEFAULT_PROXY_PORT
import io.specmatic.core.config.HttpsConfiguration
import net.minidev.json.annotate.JsonIgnore
import java.io.File

data class ProxyConfig(
    private val host: String? = null,
    private val port: Int? = null,
    private val targetUrl: String,
    private val timeoutInMilliseconds: Long? = null,
    private val consumes: List<String> = emptyList(),
    private val https: HttpsConfiguration? = null,
    private val outputDirectory: File? = null,
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
    fun mockSpecifications(): List<File> = consumes.map(::File).map(File::getCanonicalFile)

    @JsonIgnore
    fun getHttpsConfig(): HttpsConfiguration? = https

    @JsonIgnore
    fun getOutputDirectoryOrDefault(default: File = DEFAULT_OUT_DIR): File = outputDirectory ?: default

    companion object {
        private val DEFAULT_OUT_DIR = File(".").canonicalFile
    }
}
