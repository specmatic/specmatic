package application

import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.KeyStoreConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore

class CertInfoTest {
    private fun createEmptyKeyStore(file: File, password: String) {
        val keyStore = KeyStore.getInstance("JKS")
        keyStore.load(null, password.toCharArray())
        FileOutputStream(file).use { keyStore.store(it, password.toCharArray()) }
    }

    @Test
    fun `returns null when neither cli nor config provides keystore`() {
        val certInfo = CertInfo(fromCli = HttpsConfiguration.Companion.HttpsFromOpts(), fromConfig = null)
        val result = certInfo.getHttpsCert("-test")
        assertThat(result).isNull()
    }

    @Test
    fun `uses keystore file from cli when config is absent`(@TempDir tempDir: File) {
        val keystoreFile = tempDir.resolve("cli.jks")
        createEmptyKeyStore(keystoreFile, "cli-pass")

        val certInfo = CertInfo(
            fromConfig = null,
            fromCli = HttpsConfiguration.Companion.HttpsFromOpts(
                keyStoreFile = keystoreFile.canonicalPath,
                keyStorePassword = "cli-pass",
                keyStoreAlias = "cli-alias",
                keyPassword = "key-pass"
            ),
        )
        val keyData = certInfo.getHttpsCert("-suffix")!!

        assertThat(keyData.keyStore).isNotNull
        assertThat(keyData.keyStorePassword).isEqualTo("cli-pass")
        assertThat(keyData.keyAlias).isEqualTo("cli-alias")
        assertThat(keyData.keyPassword).isEqualTo("key-pass")
    }

    @Test
    fun `uses directory based keystore from config`(@TempDir tempDir: File) {
        val keystoreDir = tempDir.resolve("keystores").apply { mkdirs() }

        val certInfo = CertInfo(
            fromCli = HttpsConfiguration.Companion.HttpsFromOpts(), fromConfig = HttpsConfiguration(
                keyStore = KeyStoreConfiguration.DirectoryBasedConfig(
                    directory = keystoreDir.canonicalPath,
                    password = "dir-pass",
                    alias = "dir-alias"
                )
            )
        )
        val keyData = certInfo.getHttpsCert("-dir")!!

        assertThat(keyData.keyStore).isNotNull
        assertThat(keyData.keyPassword).isEqualTo("dir-pass")
        assertThat(keyData.keyAlias).isEqualTo("dir-alias")
    }

    @Test
    fun `when both file and directory keystores are present file is preferred`(@TempDir tempDir: File) {
        val keystoreFile = tempDir.resolve("preferred.jks")
        createEmptyKeyStore(keystoreFile, "file-pass")

        val keystoreDir = tempDir.resolve("keystores").apply { mkdirs() }
        val config = HttpsConfiguration(
            keyStore = KeyStoreConfiguration.DirectoryBasedConfig(
                directory = keystoreDir.canonicalPath,
                password = "dir-pass",
                alias = "dir-alias"
            )
        )

        val cliOpts = HttpsConfiguration.Companion.HttpsFromOpts(
            keyStoreFile = keystoreFile.canonicalPath,
            keyStorePassword = "file-pass",
            keyStoreAlias = "file-alias"
        )

        val certInfo = CertInfo(cliOpts, config)
        val keyData = certInfo.getHttpsCert("-pref")!!

        assertThat(keyData.keyStorePassword).isEqualTo("file-pass")
        assertThat(keyData.keyAlias).isEqualTo("file-alias")
    }

    @Test
    fun `cli overrides config properties but keeps absent properties`(@TempDir tempDir: File) {
        val keystoreFile = tempDir.resolve("base.jks")
        createEmptyKeyStore(keystoreFile, "config-pass")

        val config = HttpsConfiguration(keyStore = KeyStoreConfiguration.FileBasedConfig(file = keystoreFile.canonicalPath, password = "pass", alias = null))
        val cliOpts = HttpsConfiguration.Companion.HttpsFromOpts(keyStoreAlias = "cli-alias", keyStorePassword = "config-pass")

        val certInfo = CertInfo(cliOpts, config)
        val keyData = certInfo.getHttpsCert("-x")!!

        assertThat(keyData.keyStorePassword).isEqualTo("config-pass")
        assertThat(keyData.keyAlias).isEqualTo("cli-alias")
    }

    @Test
    fun `defaults alias when none provided`(@TempDir tempDir: File) {
        val keystoreFile = tempDir.resolve("default.jks")
        createEmptyKeyStore(keystoreFile, "forgotten")

        val certInfo = CertInfo(
            fromCli = HttpsConfiguration.Companion.HttpsFromOpts(keyStoreFile = keystoreFile.canonicalPath),
            fromConfig = null
        )
        val keyData = certInfo.getHttpsCert("-abc")!!

        assertThat(keyData.keyAlias).endsWith("-abc")
    }

    @Test
    fun `cli keyPassword is passed separately from keystore password`(@TempDir tempDir: File) {
        val keystoreFile = tempDir.resolve("test.jks")
        createEmptyKeyStore(keystoreFile, "store-pass")

        val certInfo = CertInfo(
            fromCli = HttpsConfiguration.Companion.HttpsFromOpts(
                keyStoreFile = keystoreFile.canonicalPath,
                keyStorePassword = "store-pass",
                keyPassword = "key-pass"
            ),
            fromConfig = null
        )

        val keyData = certInfo.getHttpsCert("-kp")!!
        assertThat(keyData.keyStorePassword).isEqualTo("store-pass")
        assertThat(keyData.keyPassword).isEqualTo("key-pass")
    }
}
