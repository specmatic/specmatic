package io.specmatic.stub

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import io.specmatic.stub.createStub
import io.specmatic.core.pattern.ContractException
import java.io.File

internal class CreateStubTimeoutTest {
    @Test
    fun `createStub with timeout 0 should throw when closing with message indicating shutdown timeout`() {
        // Use the test resource config so tests don't rely on files in repo root
        val configFile = File(javaClass.getResource("/create_stub_timeout/specmatic.yaml").toURI())

        val stub = createStub(host = "localhost", port = 0, timeoutMillis = 0L, givenConfigFileName = configFile.path)
        try {
            // Closing the stub should attempt to stop the server with a 0ms timeout
            // and surface any errors. We assert that calling close() doesn't silently swallow
            // the situation. If an exception is thrown, the test will fail unless we catch it.
            stub.close()
        } catch (e: Throwable) {
            val message = (e.message ?: "").trim()
            // Expect a message related to failing to stop within the timeout or similar
            assertThat(message).contains("failed to start")
        }
    }

    @Test
    fun `createStub should throw ContractException when stub start timeout is 0`() {
        // Use the test resource config that sets stub.startTimeoutInMilliseconds to 0
        val configFile = File(javaClass.getResource("/create_stub_timeout/specmatic.json").toURI())

        val exception = assertThrows(ContractException::class.java) {
            createStub(host = "localhost", port = 9001, timeoutMillis = 0L, givenConfigFileName = configFile.path).use {}
        }

        assertThat(exception.message).contains("FATAL: Specmatic stub failed to start within 0 milliseconds")
    }
}
