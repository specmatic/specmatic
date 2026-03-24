package io.specmatic.stub

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds

class SpecmaticMockRunnerTest {
    @Test
    fun `canConnect should return true when port is accepting connections`(): Unit = runBlocking {
        ServerSocket(0).use { serverSocket ->
            val connected = canConnect("127.0.0.1", serverSocket.localPort, 200.milliseconds)
            assertThat(connected).isTrue()
        }
    }

    @Test
    fun `canConnect should return false when host or port is invalid`(): Unit = runBlocking {
        val invalidHost = canConnect("256.256.256.256", 8080, 100.milliseconds)
        assertThat(invalidHost).isFalse()

        val invalidPort = canConnect("127.0.0.1", 65536, 100.milliseconds)
        assertThat(invalidPort).isFalse()
    }

    @Test
    fun `waitUntilConnectable should return true when port is accepting connections`(): Unit = runBlocking {
        ServerSocket(0).use { serverSocket ->
            val connected = waitUntilConnectable(host = "127.0.0.1", port = serverSocket.localPort, timeout = 300.milliseconds, pollInterval = 20.milliseconds, connectAttemptTimeout = 100.milliseconds)
            assertThat(connected).isTrue()
        }
    }

    @Test
    fun `waitUntilConnectable should return false when endpoint cannot be reached`(): Unit = runBlocking {
        val connected = waitUntilConnectable(host = "127.0.0.1", port = 8080, timeout = 120.milliseconds, pollInterval = 20.milliseconds, connectAttemptTimeout = 40.milliseconds)
        assertThat(connected).isFalse()
    }

    @Test
    fun `waitForNotNull should return supplied value once available`(): Unit = runBlocking {
        var calls = 0
        val value = waitForNotNull(timeout = 250.milliseconds, pollInterval = 1.milliseconds, description = "test value") {
            calls++
            if (calls >= 3) "ready" else null
        }

        assertThat(value).isEqualTo("ready")
    }

    @Test
    fun `waitForNotNull should throw timeout exception when value is unavailable`() {
        assertThatThrownBy {
            runBlocking {
                waitForNotNull(timeout = 80.milliseconds, pollInterval = 10.milliseconds, description = "missing value") { null }
            }
        }.isInstanceOf(TimeoutException::class.java).hasMessageContaining("missing value was not available within")
    }
}
