package io.specmatic.core

import io.ktor.network.tls.certificates.generateCertificate
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.pattern.ContractException
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
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

fun HttpsConfiguration.prepareListenerKeyStore() {
    val directory = keyStoreDir()?.let(::File)?.toPath() ?: return
    val keyStore = directory.resolve("$APPLICATION_NAME_LOWER_CASE.jks")
    if (!Files.exists(keyStore)) {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".$APPLICATION_NAME_LOWER_CASE-", ".jks")
        try {
            generateCertificate(
                temporary.toFile(),
                jksPassword = keyStorePasswordOrDefault(),
                keyAlias = keyStoreAliasOrDefault("proxy"),
                keyPassword = keyPasswordOrDefault(),
            )
            try {
                Files.move(temporary, keyStore)
            } catch (_: FileAlreadyExistsException) {
                // Another recording already created the directory keystore.
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
