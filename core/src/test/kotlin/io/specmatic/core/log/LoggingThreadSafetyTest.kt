package io.specmatic.core.log

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class LoggingThreadSafetyTest {
    @Test
    fun `withIndentation and log should not deadlock across threads`() {
        val loggerEnteredLog = CountDownLatch(1)
        val allowIndentationRead = CountDownLatch(1)
        val threadOneInIndentation = CountDownLatch(1)

        val logStrategy = DeadlockHarnessLogger(loggerEnteredLog, allowIndentationRead)
        val logger = ThreadSafeLog(logStrategy)
        val executor = namedExecutor("logging-deadlock-regression")

        try {
            val indentationTask = executor.submit<Boolean> {
                logger.withIndentation(2) {
                    threadOneInIndentation.countDown()
                    check(loggerEnteredLog.await(5, TimeUnit.SECONDS))
                    allowIndentationRead.countDown()
                    logger.log("log inside indentation")
                }
                true
            }

            val loggingTask = executor.submit<Boolean> {
                check(threadOneInIndentation.await(5, TimeUnit.SECONDS))
                logger.log("parallel log")
                true
            }

            assertThatCode {
                indentationTask.get(5, TimeUnit.SECONDS)
                loggingTask.get(5, TimeUnit.SECONDS)
            }.doesNotThrowAnyException()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `boundary state should be isolated per thread`() {
        val boundary = UsesBoundaryImpl()
        val threadOneSetBoundary = CountDownLatch(1)
        val threadTwoCheckedBoundary = CountDownLatch(1)

        val threadOneSawBoundary = AtomicReference<Boolean>()
        val threadTwoSawBoundary = AtomicReference<Boolean>()
        val executor = namedExecutor("boundary-thread")

        try {
            val threadOne = executor.submit<Boolean> {
                boundary.boundary()
                threadOneSetBoundary.countDown()
                check(threadTwoCheckedBoundary.await(5, TimeUnit.SECONDS))
                threadOneSawBoundary.set(boundary.removeBoundary())
                true
            }

            val threadTwo = executor.submit<Boolean> {
                check(threadOneSetBoundary.await(5, TimeUnit.SECONDS))
                threadTwoSawBoundary.set(boundary.removeBoundary())
                threadTwoCheckedBoundary.countDown()
                true
            }

            threadOne.get(5, TimeUnit.SECONDS)
            threadTwo.get(5, TimeUnit.SECONDS)

            assertThat(threadOneSawBoundary.get()).isTrue()
            assertThat(threadTwoSawBoundary.get()).isFalse()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `indentation scopes from different threads should not block each other`() {
        val indentation = UsesIndentationImpl()
        val threadOneInScope = CountDownLatch(1)
        val releaseThreadOne = CountDownLatch(1)
        val threadTwoCompleted = CountDownLatch(1)

        val executor = namedExecutor("indent-thread")
        try {
            val threadOne = executor.submit<Boolean> {
                indentation.withIndentation(2) {
                    threadOneInScope.countDown()
                    check(releaseThreadOne.await(5, TimeUnit.SECONDS))
                }
                true
            }

            val threadTwo = executor.submit<Boolean> {
                check(threadOneInScope.await(5, TimeUnit.SECONDS))
                indentation.withIndentation(4) {
                    threadTwoCompleted.countDown()
                }
                true
            }

            assertThat(threadTwoCompleted.await(3, TimeUnit.SECONDS)).isTrue()
            releaseThreadOne.countDown()
            threadTwo.get(5, TimeUnit.SECONDS)
            threadOne.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private class DeadlockHarnessLogger(private val loggerEnteredLog: CountDownLatch, private val allowIndentationRead: CountDownLatch) : LogStrategy, UsesIndentationWithHelpers by UsesIndentationImpl(), UsesBoundaryWithHelpers by UsesBoundaryImpl() {
        override val printer: CompositePrinter = CompositePrinter()
        override var infoLoggingEnabled: Boolean = true

        override fun keepReady(msg: LogMessage) {}

        override fun exceptionString(e: Throwable, msg: String?): String = e.message ?: e.toString()

        override fun ofTheException(e: Throwable, msg: String?): LogMessage = StringLog(exceptionString(e, msg))

        override fun log(e: Throwable, msg: String?) {
            log(ofTheException(e, msg))
        }

        override fun log(msg: String) {
            log(StringLog(msg))
        }

        override fun log(msg: LogMessage) {
            loggerEnteredLog.countDown()
            check(allowIndentationRead.await(5, TimeUnit.SECONDS))
            currentIndentation()
        }

        override fun logError(e: Throwable) {
            log(e, "ERROR")
        }

        override fun newLine() {}

        override fun debug(msg: String): String = msg

        override fun debug(msg: LogMessage) {}

        override fun debug(e: Throwable, msg: String?) {}
    }

    private fun namedExecutor(prefix: String) = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "$prefix-${THREAD_ID.getAndIncrement()}")
    }

    companion object {
        private val THREAD_ID = AtomicInteger(1)
    }
}
