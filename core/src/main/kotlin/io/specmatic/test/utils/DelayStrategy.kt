package io.specmatic.test.utils

import io.ktor.http.*
import io.specmatic.core.HttpResponse
import java.time.Instant
import kotlin.math.pow

data class DelayStrategyContext<T>(val value: T? = null, val attempt: Int)

sealed interface DelayStrategy<T> {
    fun calculateDelay(context: DelayStrategyContext<T>): Long
    fun getInitialDelay(context: DelayStrategyContext<T>): Long = 0

    data class ExponentialBackOff(private val baseDelay: Long = 1000) : DelayStrategy<Unit> {
        override fun calculateDelay(context: DelayStrategyContext<Unit>): Long = baseDelay * 2.0.pow(context.attempt).toLong()
    }

    data class RespectRetryAfter(private val baseDelay: Long = 1000, private val fallBackStrategy: DelayStrategy<Unit> = ExponentialBackOff(baseDelay)) : DelayStrategy<HttpResponse> {
        override fun calculateDelay(context: DelayStrategyContext<HttpResponse>): Long {
            val retryAfter = extractRetryAfter(context.value)
            return retryAfter ?: fallBackStrategy.calculateDelay(DelayStrategyContext(value = Unit, attempt = context.attempt))
        }

        override fun getInitialDelay(context: DelayStrategyContext<HttpResponse>): Long {
            return extractRetryAfter(context.value) ?: 0
        }

        private fun extractRetryAfter(response: HttpResponse?): Long? {
            val retryAfter = response?.headers?.get(HttpHeaders.RetryAfter) ?: return null
            retryAfter.toLongOrNull()?.let { seconds -> return seconds * 1000 }
            return runCatching {
                val target = Instant.parse(retryAfter)
                val now = Instant.now()
                val delayMillis = target.toEpochMilli() - now.toEpochMilli()
                delayMillis.coerceAtLeast(0)
            }.getOrNull()
        }
    }
}
