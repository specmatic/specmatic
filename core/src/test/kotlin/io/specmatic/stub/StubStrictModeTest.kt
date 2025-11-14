package io.specmatic.stub

import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.utilities.Flags
import io.specmatic.osAgnosticPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class StubStrictModeTest {
    @Nested
    inner class StubConfigurationTests {
        @Test
        fun `getStrictMode should return strictMode from config when set to true`() {
            val configFile = File(javaClass.getResource("/stub_strict_mode/specmatic_strict_true.yaml")!!.toURI())
            val config = loadSpecmaticConfig(configFile.path)

            assertThat(config.getStubStrictMode()).isTrue()
        }

        @Test
        fun `getStrictMode should return strictMode from config when set to false`() {
            val configFile = File(javaClass.getResource("/stub_strict_mode/specmatic_strict_false.yaml")!!.toURI())
            val config = loadSpecmaticConfig(configFile.path)

            assertThat(config.getStubStrictMode()).isFalse()
        }

        @Test
        fun `getStrictMode should return null when not set in config and no system property`() {
            val configFile = File(javaClass.getResource("/stub_strict_mode/specmatic_no_strict.yaml")!!.toURI())
            val config = loadSpecmaticConfig(configFile.path)

            assertThat(config.getStubStrictMode()).isFalse()
        }

        @Test
        fun `getStrictMode should fall back to system property when not set in config`() {
            val configFile = File(javaClass.getResource("/stub_strict_mode/specmatic_no_strict.yaml")!!.toURI())

            Flags.using(Flags.STUB_STRICT_MODE to "true") {
                val config = loadSpecmaticConfig(configFile.path)
                assertThat(config.getStubStrictMode()).isTrue()
            }
        }

        @Test
        fun `getStrictMode should prefer config over system property`() {
            val configFile = File(javaClass.getResource("/stub_strict_mode/specmatic_strict_false.yaml")!!.toURI())

            Flags.using(Flags.STUB_STRICT_MODE to "true") {
                val config = loadSpecmaticConfig(configFile.path)
                // Config has false, system property has true - config should win
                assertThat(config.getStubStrictMode()).isFalse()
            }
        }
    }

    @Nested
    inner class CreateStubIntegrationTests {
        @Test
        fun `createStubFromContracts with config strictMode true should fail with invalid stub`() {
            val resourcesDir = File(javaClass.getResource("/stub_strict_mode")!!.toURI())
            val apiFile = File(resourcesDir, "api.yaml")
            val invalidStubDir = File(resourcesDir, "api_data")
            val configFile = File(resourcesDir, "specmatic_strict_true.yaml")

            val exception = assertThrows(Exception::class.java) {
                createStubFromContracts(
                    contractPaths = listOf(apiFile.path),
                    dataDirPaths = listOf(invalidStubDir.path),
                    host = "localhost",
                    port = 9001,
                    timeoutMillis = HTTP_STUB_SHUTDOWN_TIMEOUT,
                    specmaticConfigPath = configFile.path
                ).use { }
            }

            // In strict mode, invalid stubs should cause an error
            assertThat(exception.message).contains("didn't match")
        }

        @Test
        fun `createStubFromContracts with config strictMode false should succeed with invalid stub`() {
            val resourcesDir = File(javaClass.getResource("/stub_strict_mode")!!.toURI())
            val apiFile = File(resourcesDir, "api.yaml")
            val invalidStubDir = File(resourcesDir, "api_data")
            val configFile = File(resourcesDir, "specmatic_strict_false.yaml")

            // Using strict=false in config should allow invalid stubs
            val stub = createStubFromContracts(
                contractPaths = listOf(apiFile.path),
                dataDirPaths = listOf(invalidStubDir.path),
                host = "localhost",
                port = 9002,
                timeoutMillis = HTTP_STUB_SHUTDOWN_TIMEOUT,
                specmaticConfigPath = configFile.path
            )

            try {
                // In non-strict mode, invalid stubs are logged but don't cause failure
                assertThat(stub).isNotNull()
            } finally {
                stub.close()
            }
        }

        @Test
        fun `createStubFromContracts should succeed with valid stub in strict mode`(@TempDir tempDir: File) {
            val configFile = File(osAgnosticPath("src/test/resources/stub_strict_mode/specmatic_strict_true_with_spec.yaml"))

            assertDoesNotThrow {
                val stub = createStub(timeoutMillis = HTTP_STUB_SHUTDOWN_TIMEOUT, givenConfigFileName = configFile.canonicalPath)
                stub.close()
            }
        }
    }
}
