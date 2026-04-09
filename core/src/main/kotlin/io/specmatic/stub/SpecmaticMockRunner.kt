package io.specmatic.stub

import io.specmatic.stub.SpecmaticMockRunner.Companion.LOOPBACK_CANDIDATES
import io.specmatic.stub.SpecmaticMockRunner.Companion.LOOPBACK_LIKE
import io.specmatic.stub.SpecmaticMockRunner.Companion.WILDCARDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

interface SpecmaticMockRunner: Callable<Int>, Closeable {
    suspend fun checkReadiness()

    companion object {
        val WILDCARDS = setOf("0.0.0.0", "::", "[::]")
        val LOOPBACK_LIKE = setOf("0.0.0.0", "::", "[::]", "127.0.0.1", "::1", "localhost")
        val LOOPBACK_CANDIDATES = listOf("localhost", "127.0.0.1", "::1", "host.docker.internal")
    }
}

suspend fun canConnect(host: String, port: Int, timeout: Duration): Boolean = withContext(Dispatchers.IO) {
    val candidateHosts = when (host.lowercase()) {
        !in LOOPBACK_LIKE -> listOf(host)
        in WILDCARDS -> LOOPBACK_CANDIDATES
        else -> (listOf(host) + LOOPBACK_CANDIDATES).distinct()
    }

    val timeoutMs = timeout.inWholeMilliseconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return@withContext candidateHosts.any { candidate ->
        runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(candidate, port), timeoutMs) }
        }.isSuccess
    }
}

suspend fun waitUntilConnectable(host: String, port: Int, timeout: Duration, pollInterval: Duration = 250.milliseconds, connectAttemptTimeout: Duration = 500.milliseconds): Boolean {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!deadline.hasPassedNow()) {
        if (canConnect(host, port, connectAttemptTimeout)) return true
        delay(pollInterval)
    }
    return false
}

suspend fun <T> waitForNotNull(timeout: Duration, pollInterval: Duration = 250.milliseconds, description: String = "value", supplier: () -> T?): T {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!deadline.hasPassedNow()) {
        supplier()?.let { return it }
        delay(pollInterval)
    }
    throw TimeoutException("$description was not available within $timeout")
}
