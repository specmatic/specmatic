package application

import io.ktor.network.tls.certificates.*
import io.specmatic.core.APPLICATION_NAME_LOWER_CASE
import io.specmatic.core.KeyData
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.nonNullElse
import io.specmatic.core.utilities.exitWithMessage
import java.io.File
import java.security.KeyStore

data class CertInfo(val fromCli: HttpsConfiguration.Companion.HttpsFromOpts, val fromConfig: HttpsConfiguration?) {
    fun getHttpsCert(aliasSuffix: String): KeyData? {
        val fromOpts = HttpsConfiguration.from(fromCli)
        val effectiveConfig = fromConfig.nonNullElse(fromOpts, HttpsConfiguration::overrideWith) ?: return null
        return when {
            effectiveConfig.keyStoreFile() != null -> KeyData(
                keyStore = loadKeyStoreFromFile(effectiveConfig.keyStoreFile().orEmpty(), effectiveConfig.keyStorePasswordOrDefault()),
                keyStorePassword = effectiveConfig.keyStorePasswordOrDefault(),
                keyAlias = effectiveConfig.keyStoreAliasOrDefault(aliasSuffix),
                keyPassword = effectiveConfig.keyPasswordOrDefault()
            )

            effectiveConfig.keyStoreDir() != null -> createKeyStore(
                effectiveConfig.keyStoreDir().orEmpty(),
                effectiveConfig.keyStorePasswordOrDefault(),
                effectiveConfig.keyStoreAliasOrDefault(aliasSuffix),
                effectiveConfig.keyPasswordOrDefault()
            )

            else -> null
        }
    }
}

private fun createKeyStore(keyStoreDirPath: String, keyStorePassword: String, keyAlias: String, keyPassword: String): KeyData {
    val keyStoreDir = File(keyStoreDirPath)
    if (!keyStoreDir.exists())
        keyStoreDir.mkdirs()

    val filename = "$APPLICATION_NAME_LOWER_CASE.jks"
    val keyStoreFile = keyStoreDir.resolve(filename)
    if (keyStoreFile.exists()) {
        val deleteStatus = keyStoreFile.delete()
        if (!deleteStatus) {
            exitWithMessage("Unable to delete existing keystore file at $keyStoreFile")
        }
    }

    val keyStore = generateCertificate(keyStoreFile, jksPassword = keyStorePassword, keyAlias = keyAlias, keyPassword = keyPassword)
    return KeyData(keyStore = keyStore, keyStorePassword = keyStorePassword, keyAlias = keyAlias, keyPassword = keyPassword)
}

private fun loadKeyStoreFromFile(keyStoreFile: String, keyStorePassword: String): KeyStore {
    val certFilePath = File(keyStoreFile)
    val keyStoreType = when (certFilePath.extension.lowercase()) {
        "jks" -> "JKS"
        "pfx" -> "PKCS12"
        else -> exitWithMessage("The certificate file must be either in Java Key Store or PKCS12 format")
    }

    return KeyStore.getInstance(keyStoreType).apply {
        this.load(certFilePath.inputStream(), keyStorePassword.toCharArray())
    }
}
