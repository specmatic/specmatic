package io.specmatic.core.config.v3

import io.specmatic.core.OPENAPI_FILE_EXTENSIONS
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.isOpenAPI
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.collections.contains

sealed interface ServerOrigin {
    val port: Int? get() = null
    val host: String? get() = null
    val scheme: String? get() = null
    val baseUrl: String? get() = null

    fun toBaseUrl(defaultScheme: String = "http"): String

    data class BaseUrl(override val baseUrl: String) : ServerOrigin {
        override fun toBaseUrl(defaultScheme: String): String = baseUrl
    }

    data class NetworkAddress(override val scheme: String? = null, override val host: String, override val port: Int) : ServerOrigin {
        override fun toBaseUrl(defaultScheme: String): String {
            return "${scheme ?: defaultScheme}://$host:$port"
        }
    }

    fun withPath(path: String, defaultScheme: String = "http"): ServerOrigin {
        val cleanPath = path.removePrefix("/")
        val cleanBaseUrl = toBaseUrl(defaultScheme).removeSuffix("/")
        return BaseUrl(if (cleanPath.isBlank()) cleanBaseUrl else "$cleanBaseUrl/$cleanPath")
    }

    companion object {
        fun from(url: String): ServerOrigin = BaseUrl(url)
        fun from(scheme: String? = null, host: String, port: Int): ServerOrigin {
            if (scheme == null) return NetworkAddress(host = host, port = port)
            return BaseUrl("$scheme://$host:$port")
        }
    }
}

internal fun determineSpecTypeFor(specFile: File): List<SpecType> {
    return when(specFile.extension.lowercase()) {
        "wsdl" ->
            listOf(SpecType.WSDL)
        "proto" ->
            listOf(SpecType.PROTOBUF)
        in setOf("graphql", "graphqls") ->
            listOf(SpecType.GRAPHQL)
        in OPENAPI_FILE_EXTENSIONS if isOpenAPI(specFile.canonicalPath, logFailure = false) ->
            listOf(SpecType.OPENAPI)
        in OPENAPI_FILE_EXTENSIONS if isAsyncAPI(specFile.canonicalPath) ->
            listOf(SpecType.ASYNCAPI)
        else ->
            listOf(SpecType.OPENAPI, SpecType.ASYNCAPI)
    }
}

private fun isAsyncAPI(path: String): Boolean = try {
    File(path).reader().use { reader ->
        Yaml().load<MutableMap<String, Any?>>(reader).contains("asyncapi")
    }
} catch (_: Throwable) {
    false
}
