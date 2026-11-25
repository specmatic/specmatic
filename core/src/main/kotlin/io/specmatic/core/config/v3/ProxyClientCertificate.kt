package io.specmatic.core.config.v3

import io.specmatic.core.config.ConfigPathMapper
import java.io.File

data class ProxyClientCertificate(
    val keyStore: ClientKeyStore,
    val keyStorePassword: String? = null,
) {
    data class ClientKeyStore(val file: String, val password: String? = null, val alias: String? = null)

    internal fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): ProxyClientCertificate =
        copy(keyStore = keyStore.copy(file = mapper.child("keyStore").child("file").map(keyStore.file, configDirectory)))

    internal fun resolveFile(configDirectory: File): ProxyClientCertificate =
        copy(keyStore = keyStore.copy(file = configDirectory.resolve(keyStore.file).path))
}
