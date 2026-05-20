package io.specmatic.test.listeners

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestIdentifier

class MonochromePrinter: ContractExecutionPrinter {
    override fun printFinalSummary(testSummary: TestSummary) {
        consolePrintln(testSummary.message)
        consolePrintln()
        consolePrintln("Executed at ${currentDateAndTime()}")
    }

    override fun printTestSummary(testIdentifier: TestIdentifier?, testExecutionResult: TestExecutionResult?) {
        consolePrintln(testStatusMessage(testIdentifier, testExecutionResult))
    }

    override fun printFailureTitle(failures: String) {
        consolePrintln(failures)
    }
}

fun currentDateAndTime(): String {
    return java.time.LocalDateTime.now().toString()
}
