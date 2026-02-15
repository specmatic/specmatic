package io.specmatic.core

import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.stub.normalizeHost
import java.net.URI

private sealed interface HostPortIdentifier {
    data class Matching(val host: String, val port: Int): HostPortIdentifier
    data object WildCard: HostPortIdentifier
}

class CertRegistry private constructor(private val certificates: List<Pair<HostPortIdentifier, HttpsConfiguration>>) {
    fun toKeyDataRegistry(transform: (HttpsConfiguration) -> KeyData?): KeyDataRegistry {
        val groupedCerts = certificates.groupBy({ it.first }, { it.second })
        val conflicts = groupedCerts.filterValues { it.distinct().size > 1 }.map { (identifier, certs) -> "$identifier -> ${certs.size} certificates" }
        if (conflicts.isNotEmpty()) {
            throw IllegalArgumentException(
                buildString {
                    appendLine("Multiple certificates found for the same host/port:")
                    conflicts.forEach { appendLine(" - $it") }
                }
            )
        }

        val keyData = groupedCerts.mapValues { (_, certs) -> transform(certs.single()) }
        return keyData.entries.fold(KeyDataRegistry.empty()) { acc, (identifier, keyData) ->
            if (keyData == null) return@fold acc
            when (identifier) {
                is HostPortIdentifier.Matching -> acc.plus(identifier.host, identifier.port, keyData)
                else -> acc.plusWildCard(keyData)
            }
        }
    }

    fun plus(baseUrl: String, cert: HttpsConfiguration): CertRegistry {
        val uri = try {
            if ("://" in baseUrl) URI(baseUrl)
            else URI("scheme://$baseUrl")
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid host/port: $baseUrl", e)
        }
        return plus(uri.host, uri.port, cert)
    }

    fun plus(host: String, port: Int, cert: HttpsConfiguration): CertRegistry {
        val entry = Pair(HostPortIdentifier.Matching(normalizeHost(host), port), cert)
        return CertRegistry(certificates.plus(entry))
    }

    fun plusWildCard(cert: HttpsConfiguration): CertRegistry {
        val entry = Pair(HostPortIdentifier.WildCard, cert)
        return CertRegistry(certificates.plus(entry))
    }

    companion object { fun empty(): CertRegistry = CertRegistry(emptyList()) }
}

class KeyDataRegistry private constructor(private val keyData: Map<HostPortIdentifier, KeyData>) {
    fun hasAny(): Boolean = keyData.isNotEmpty()

    fun plus(host: String, port: Int, entry: KeyData): KeyDataRegistry {
        val identifier = HostPortIdentifier.Matching(normalizeHost(host), port)
        return KeyDataRegistry(keyData + (identifier to entry))
    }

    fun plusWildCard(keyData: KeyData): KeyDataRegistry = KeyDataRegistry(this.keyData + (HostPortIdentifier.WildCard to keyData))

    fun plusWildCardIfEmpty(block: () -> KeyData?): KeyDataRegistry {
        if (this.keyData.isNotEmpty()) return this
        val keyData = block() ?: return this
        return empty().plusWildCard(keyData)
    }

    fun get(host: String, port: Int): KeyData? {
        val identifier = HostPortIdentifier.Matching(normalizeHost(host), port)
        return keyData[identifier] ?: keyData[HostPortIdentifier.WildCard]
    }

    companion object { fun empty(): KeyDataRegistry = KeyDataRegistry(emptyMap()) }
}
