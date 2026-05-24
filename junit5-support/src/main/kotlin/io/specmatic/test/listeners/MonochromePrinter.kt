package io.specmatic.test.listeners

import java.io.PrintStream
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestIdentifier

class MonochromePrinter(private val out: PrintStream = System.out): ContractExecutionPrinter {
    override fun printFinalSummary(testSummary: TestSummary) {
        out.println(testSummary.message)
        out.println()
        out.println("Executed at ${currentDateAndTime()}")
    }

    override fun printTestSummary(testIdentifier: TestIdentifier?, testExecutionResult: TestExecutionResult?) {
        out.println(testStatusMessage(testIdentifier, testExecutionResult))
    }

    override fun printFailureTitle(failures: String) {
        out.println(failures)
    }
}

fun currentDateAndTime(): String {
    return java.time.LocalDateTime.now().toString()
}
