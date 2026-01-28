package application

import io.mockk.mockk
import io.specmatic.core.KeyData
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.proxy.Proxy
import io.specmatic.stub.SpecmaticConfigSource
import org.assertj.core.api.AssertionsForClassTypes.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore

class ProxyCommandTest {
    @Test
    fun `uses config values when CLI is absent`(@TempDir tempDir: File) {
        val proxyDir = tempDir.resolve("proxy-data").apply { mkdirs() }
        val configFile = writeSpecmaticYaml(tempDir, """
        version: 2
        proxy:
          host: config-host
          port: 1234
          targetUrl: http://config-target
          outputDirectory: ${proxyDir.canonicalPath}
          timeoutInMilliseconds: 9999
        """.trimIndent())

        val cmd = Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) { TestProxyCommand() }
        assertThrows<ProxyStartedTestException> { cmd.call() }

        assertThat(cmd.capturedHost).isEqualTo("config-host")
        assertThat(cmd.capturedPort).isEqualTo(1234)
        assertThat(cmd.capturedTarget).isEqualTo("http://config-target")
        assertThat(cmd.capturedOutDir?.canonicalPath).isEqualTo(proxyDir.canonicalPath)
        assertThat(cmd.capturedTimeout).isEqualTo(9999L)
    }

    @Test
    fun `CLI overrides config values`(@TempDir tempDir: File) {
        val proxyDir = tempDir.resolve("proxy-data").apply { mkdirs() }
        val configFile = writeSpecmaticYaml(tempDir, """
            version: 2
            proxy:
              host: config-host
              port: 1234
              targetUrl: http://config-target
              outputDirectory: ${proxyDir.canonicalPath}
        """.trimIndent())

        val cmd = Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            TestProxyCommand().apply {
                host = "cli-host"
                port = 5678
                targetBaseURL = "http://cli-target"
            }
        }
        assertThrows<ProxyStartedTestException> { cmd.call() }

        assertThat(cmd.capturedHost).isEqualTo("cli-host")
        assertThat(cmd.capturedPort).isEqualTo(5678)
        assertThat(cmd.capturedTarget).isEqualTo("http://cli-target")
        assertThat(cmd.capturedOutDir?.canonicalPath).isEqualTo(proxyDir.canonicalPath)
    }

    @Test
    fun `falls back to defaults when neither CLI nor config is present`() {
        val cmd = TestProxyCommand().apply { targetBaseURL = "http://www.example.com" }
        assertThrows<ProxyStartedTestException> { cmd.call() }

        assertThat(cmd.capturedHost).isEqualTo("0.0.0.0")
        assertThat(cmd.capturedPort).isEqualTo(9000)
        assertThat(cmd.capturedTarget).isEqualTo("http://www.example.com")
        assertThat(cmd.capturedOutDir?.canonicalPath).isEqualTo(File(".").canonicalPath)
    }

    @Test
    fun `should throw when target url is not provided via CLI args and specmatic config`() {
        val cmd = TestProxyCommand()
        val exception = assertThrows<ContractException> { cmd.call() }
        assertThat(exception.report()).containsIgnoringWhitespaces("Proxy targetURL must be provided through CLI or Specmatic Config")
    }

    @Test
    fun `uses HTTPS key store from CLI if provided`(@TempDir tempDir: File) {
        val keyStoreFile = createEmptyKeyStore(tempDir.resolve("cli.jks"), "cli-test")
        val cmd = TestProxyCommand().apply {
            this.targetBaseURL = "http://www.example.com"
            this.keyStoreFile = keyStoreFile.canonicalPath
            keyStorePassword = "cli-test"
            keyPassword = "keyPass"
        }

        assertThrows<ProxyStartedTestException> { cmd.call() }
        assertThat(cmd.capturedKeyData).isNotNull
        assertThat(cmd.capturedKeyData!!.keyPassword).isEqualTo("keyPass")
        assertThat(cmd.capturedKeyData!!.keyStorePassword).isEqualTo("cli-test")
    }

    @Test
    fun `CLI overrides some config values while config fills missing ones`(@TempDir tempDir: File) {
        val proxyDir = tempDir.resolve("proxy-data").apply { mkdirs() }
        val configFile = writeSpecmaticYaml(tempDir, """
        version: 2
        proxy:
          host: config-host
          port: 1234
          targetUrl: http://config-target
          outputDirectory: ${proxyDir.canonicalPath}
        """.trimIndent())

        val cmd = Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) { TestProxyCommand().apply { port = 5678 } }
        assertThrows<ProxyStartedTestException> { cmd.call() }

        assertThat(cmd.capturedHost).isEqualTo("config-host")
        assertThat(cmd.capturedPort).isEqualTo(5678)
        assertThat(cmd.capturedTarget).isEqualTo("http://config-target")
        assertThat(cmd.capturedOutDir?.canonicalPath).isEqualTo(proxyDir.canonicalPath)
    }

    private fun writeSpecmaticYaml(dir: File, content: String): File = dir.resolve("specmatic.yaml").also { it.writeText(content) }

    private fun createEmptyKeyStore(file: File, password: String): File {
        val keyStore = KeyStore.getInstance("JKS")
        keyStore.load(null, password.toCharArray())
        FileOutputStream(file).use { keyStore.store(it, password.toCharArray()) }
        return file
    }

    private class ProxyStartedTestException: Throwable("Proxy has started")

    class TestProxyCommand : ProxyCommand() {
        var capturedHost: String? = null
        var capturedPort: Int? = null
        var capturedTarget: String? = null
        var capturedOutDir: File? = null
        var capturedKeyData: KeyData? = null
        var capturedTimeout: Long? = null

        override fun createProxy(
            filter: String,
            host: String,
            port: Int,
            outDir: File,
            timeout: Long,
            target: String,
            keyData: KeyData?,
            specmaticConfigSource: SpecmaticConfigSource
        ): Proxy {
            capturedHost = host
            capturedPort = port
            capturedOutDir = outDir
            capturedKeyData = keyData
            capturedTarget = target
            capturedTimeout = timeout
            return mockk(relaxed = true)
        }

        override fun addShutdownHook() {
            throw ProxyStartedTestException()
        }
    }
}
