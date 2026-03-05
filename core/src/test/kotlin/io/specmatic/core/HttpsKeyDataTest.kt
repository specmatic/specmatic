package io.specmatic.core

import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.KeyStoreConfiguration
import io.specmatic.core.pattern.ContractException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.KeyStore

class HttpsKeyDataTest {
    @Test
    fun `should load JKS keystore from file`(@TempDir tempDir: Path) {
        val keyStoreFile = tempDir.resolve("client.jks").toFile()
        createEmptyKeyStore(keyStoreFile, "store-pass", "JKS")

        val keyData = httpsConfig(keyStoreFile, "store-pass").toKeyData(aliasSuffix = "test")

        assertThat(keyData).isNotNull()
    }

    @Test
    fun `should load PKCS12 keystore from file`(@TempDir tempDir: Path) {
        val keyStoreFile = tempDir.resolve("client.p12").toFile()
        createEmptyKeyStore(keyStoreFile, "store-pass", "PKCS12")

        val keyData = httpsConfig(keyStoreFile, "store-pass").toKeyData(aliasSuffix = "test")

        assertThat(keyData).isNotNull()
    }

    @Test
    fun `should fail for unsupported keystore extension`(@TempDir tempDir: Path) {
        val keyStoreFile = tempDir.resolve("client.txt").toFile().apply {
            writeText("not-a-keystore")
        }

        assertThatThrownBy {
            httpsConfig(keyStoreFile, "store-pass").toKeyData(aliasSuffix = "test")
        }.isInstanceOf(ContractException::class.java)
            .hasMessageContaining("Unsupported keystore format")
    }

    @Test
    fun `should fail when keystore password is incorrect`(@TempDir tempDir: Path) {
        val keyStoreFile = tempDir.resolve("client.jks").toFile()
        createEmptyKeyStore(keyStoreFile, "correct-pass", "JKS")

        assertThatThrownBy {
            httpsConfig(keyStoreFile, "wrong-pass").toKeyData(aliasSuffix = "test")
        }.isInstanceOf(Exception::class.java)
    }

    private fun httpsConfig(file: File, keyStorePassword: String): HttpsConfiguration {
        return HttpsConfiguration(
            keyStore = KeyStoreConfiguration.FileBasedConfig(file = file.canonicalPath),
            keyStorePassword = keyStorePassword
        )
    }

    private fun createEmptyKeyStore(file: File, password: String, type: String) {
        val keyStore = KeyStore.getInstance(type).apply { load(null, password.toCharArray()) }
        file.outputStream().use { outputStream ->
            keyStore.store(outputStream, password.toCharArray())
        }
    }
}
