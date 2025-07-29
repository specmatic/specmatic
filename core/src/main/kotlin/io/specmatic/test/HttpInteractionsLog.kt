package io.specmatic.test

import io.specmatic.core.log.HttpLogMessage
import java.util.concurrent.ConcurrentLinkedDeque

class HttpInteractionsLog {
    val testHttpLogMessages = ConcurrentLinkedDeque<HttpLogMessage>()

    fun addHttpLog(httpLogMessage: HttpLogMessage) {
        if (httpLogMessage.isTestLog()) {
            testHttpLogMessages.add(httpLogMessage)
        }
    }

    fun totalDuration(): Long {
        return testHttpLogMessages.sumOf { it.duration() }
    }
}
