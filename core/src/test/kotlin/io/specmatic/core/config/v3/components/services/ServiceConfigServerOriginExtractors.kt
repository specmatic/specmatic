package io.specmatic.core.config.v3.components.services

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.specmatic.core.config.v3.ServerOrigin
import io.specmatic.core.config.v3.components.runOptions.AsyncApiRunOptions
import io.specmatic.core.config.v3.components.runOptions.GraphQLSdlRunOptions
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.ProtobufRunOptions
import io.specmatic.core.config.v3.spi.ProtocolConfig
import io.specmatic.core.config.v3.spi.ProtocolConfigServerExtractor
import java.net.URI

internal fun serviceConfigServerOriginExtractors(): List<ProtocolConfigServerExtractor> = listOf(
    HostAndPortServerOriginExtractor(),
    AsyncApiServerOriginExtractor(),
)

private class HostAndPortServerOriginExtractor : ProtocolConfigServerExtractor {
    override fun extractNetworkOrigin(configuration: ProtocolConfig<*>): ServerOrigin? {
        if (!configuration.runOptions.hasHostAndPortConfiguration()) return null
        val config = when (configuration) {
            is ProtocolConfig.Global<*> -> configuration.runOptions.config
            is ProtocolConfig.Override<*> -> configuration.spec.getConfig()
        }

        return config.networkOrigin()
    }

    private fun IRunOptions.hasHostAndPortConfiguration(): Boolean {
        return this is GraphQLSdlRunOptions || this is ProtobufRunOptions
    }

    private fun Map<String, Any>.networkOrigin(): ServerOrigin? {
        val host = this["host"] as? String ?: return null
        val port = this["port"] as? Int ?: return null
        return ServerOrigin.NetworkAddress(host = host, port = port)
    }
}

private class AsyncApiServerOriginExtractor : ProtocolConfigServerExtractor {
    override fun extractNetworkOrigin(configuration: ProtocolConfig<*>): ServerOrigin? {
        val runOptions = configuration.runOptions as? AsyncApiRunOptions ?: return null
        val config = when (configuration) {
            is ProtocolConfig.Global<*> -> runOptions.config
            is ProtocolConfig.Override<*> -> configuration.spec.getConfig()
        }

        return config.asyncApiNetworkOrigin()
    }

    private fun Map<String, Any>.asyncApiNetworkOrigin(): ServerOrigin? {
        val inMemoryBroker = this["inMemoryBroker"]?.let {
            objectMapper.convertValue(it, InMemoryBrokerConfig::class.java)
        }

        inMemoryBroker?.networkOrigin()?.let { return it }
        val servers = this["servers"]?.let { objectMapper.convertValue(it, serverEntriesType) }.orEmpty()
        return servers.firstNotNullOfOrNull { it.networkOrigin() }
    }

    private fun InMemoryBrokerConfig.networkOrigin(): ServerOrigin? {
        val host = host ?: return null
        val port = port ?: return null
        return ServerOrigin.NetworkAddress(host = host, port = port)
    }

    private fun AsyncApiServerEntry.networkOrigin(): ServerOrigin? {
        val serverConfig = server ?: AsyncApiServerConfig(host = host, type = type)
        if (serverConfig.type != "in-memory") return null

        val address = serverConfig.host ?: return null
        val uri = runCatching { URI("tcp://$address") }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: return null
        return ServerOrigin.NetworkAddress(host = host, port = port)
    }

    private val objectMapper = jacksonObjectMapper()
    private val serverEntriesType = object : TypeReference<List<AsyncApiServerEntry>>() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class InMemoryBrokerConfig(
        val host: String? = null,
        val port: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AsyncApiServerEntry(
        val host: String? = null,
        val protocol: String? = null,
        val type: String? = null,
        val server: AsyncApiServerConfig? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AsyncApiServerConfig(
        val host: String? = null,
        val protocol: String? = null,
        val type: String? = null,
    )
}
