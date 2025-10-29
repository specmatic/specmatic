package io.specmatic.test.utils

import io.specmatic.core.log.logger

interface Sleeper {
    fun sleep(milliSeconds: Long)
}

val DefaultSleeper = object : Sleeper {
    override fun sleep(milliSeconds: Long) = Thread.sleep(milliSeconds)
}

sealed interface RetryResult<Output, DelayContext> {
    data class Continue<Output, DelayContext>(val value: DelayContext? = null) : RetryResult<Output, DelayContext>
    data class Stop<Output, DelayContext>(val value: Output) : RetryResult<Output, DelayContext>
}

class RetryHandler<Output, DelayContext>(private val delayStrategy: DelayStrategy<DelayContext>, private val maxAttempts: Int = 3, private val sleeper: Sleeper = DefaultSleeper) {
    fun run(onExhausted: (Int) -> Output, initialDelayContext: DelayContext? = null, block: (attempt: Int) -> RetryResult<Output, DelayContext>): Output {
        initialDelayContext?.let(::delayForInitialRun)
        repeat(maxAttempts) { attempt ->
            val result = runCatching { block(attempt.inc()) }.getOrElse { RetryResult.Continue(null) }
            when (result) {
                is RetryResult.Stop -> return result.value
                is RetryResult.Continue -> {
                    if (attempt == maxAttempts - 1) return onExhausted(maxAttempts)
                    val context = DelayStrategyContext(value = result.value, attempt = attempt)
                    val nextDelay = delayStrategy.calculateDelay(context)
                    logger.log("Sleeping for ${nextDelay}ms (attempt ${attempt + 2}/$maxAttempts)")
                    sleeper.sleep(nextDelay)
                }
            }
        }
        error("Unreachable")
    }

    private fun delayForInitialRun(delayContext: DelayContext) {
        val context = DelayStrategyContext(value = delayContext, attempt = 0)
        val initialDelay = delayStrategy.getInitialDelay(context).takeIf { it > 0 } ?: return
        logger.log("Sleeping for initial delay of ${initialDelay}ms")
        sleeper.sleep(initialDelay)
    }
}
