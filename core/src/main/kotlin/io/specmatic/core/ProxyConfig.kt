package io.specmatic.core

import net.minidev.json.annotate.JsonIgnore
import java.net.URL
import javax.activation.MimeType

data class ProxyMockedOperation(val path: String, val method: String, val requestContentType: String? = null) {
    @JsonIgnore
    fun identity() = Triple(
        path,
        method.lowercase(),
        requestContentType?.let(::MimeType)
    )
}

data class ProxyMockedSpecification(val spec: String, val operations: List<ProxyMockedOperation>) {
    @JsonIgnore
    fun merge(other: ProxyMockedSpecification): ProxyMockedSpecification {
        require(spec == other.spec)
        val mergedOperations = (operations + other.operations).distinctBy(ProxyMockedOperation::identity)
        return copy(operations = mergedOperations)
    }
}

data class ProxyConfig(val baseUrl: String, val targetBaseUrl: String, val mocked: List<ProxyMockedSpecification> = emptyList()) {
    @JsonIgnore
    fun baseUrl(): URL = URL(baseUrl)

    @JsonIgnore
    fun targetBaseUrl(): URL = URL(targetBaseUrl)

    @JsonIgnore
    fun merge(other: ProxyConfig): ProxyConfig {
        require(baseUrl == other.baseUrl)
        require(targetBaseUrl == other.targetBaseUrl)
        return copy(
            mocked = (mocked + other.mocked).groupBy(ProxyMockedSpecification::spec).map { (_, specs) ->
                specs.reduce(ProxyMockedSpecification::merge)
            }
        )
    }

    companion object {
        fun List<ProxyConfig>.merge(other: List<ProxyConfig>): List<ProxyConfig> {
            return this.plus(other).groupBy { it.baseUrl() to it.targetBaseUrl() }.map { (_, configs) ->
                configs.reduce(ProxyConfig::merge)
            }
        }
    }
}
