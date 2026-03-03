package io.specmatic.core

import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.pattern.ContractException
import java.io.File
import java.security.KeyStore

fun HttpsConfiguration.toKeyData(aliasSuffix: String): KeyData? {
    val keyStoreFilePath = keyStoreFile()
    if (keyStoreFilePath != null) {
        return KeyData(
            keyStore = loadKeyStoreFromFile(keyStoreFilePath, keyStorePasswordOrDefault()),
            keyStorePassword = keyStorePasswordOrDefault(),
            keyAlias = keyStoreAliasOrDefault(aliasSuffix),
            keyPassword = keyPasswordOrDefault()
        )
    }

    val keyStoreDirPath = keyStoreDir()
    if (keyStoreDirPath != null) {
        val keyStoreFile = File(keyStoreDirPath).resolve("$APPLICATION_NAME_LOWER_CASE.jks")
        if (!keyStoreFile.exists()) {
            throw ContractException("No keystore file found at ${keyStoreFile.canonicalPath}. Please provide a valid client certificate keystore.")
        }

        return KeyData(
            keyStore = loadKeyStoreFromFile(keyStoreFile.canonicalPath, keyStorePasswordOrDefault()),
            keyStorePassword = keyStorePasswordOrDefault(),
            keyAlias = keyStoreAliasOrDefault(aliasSuffix),
            keyPassword = keyPasswordOrDefault()
        )
    }

    return null
}

fun CertRegistry.toTestKeyDataRegistry(): KeyDataRegistry {
    return toKeyDataRegistry { httpsConfiguration ->
        httpsConfiguration.toKeyData(aliasSuffix = "test")
    }
}

fun CertRegistry.toIncomingMtlsRegistryForStub(): IncomingMtlsRegistry = toIncomingMtlsRegistry()

private fun loadKeyStoreFromFile(keyStoreFile: String, keyStorePassword: String): KeyStore {
    val keyStorePath = File(keyStoreFile)
    val keyStoreType = when (keyStorePath.extension.lowercase()) {
        "jks" -> "JKS"
        "p12", "pfx" -> "PKCS12"
        else -> throw ContractException("Unsupported keystore format in $keyStoreFile. Supported formats are .jks, .p12 and .pfx")
    }

    return KeyStore.getInstance(keyStoreType).apply {
        keyStorePath.inputStream().use { inputStream ->
            load(inputStream, keyStorePassword.toCharArray())
        }
    }
}
