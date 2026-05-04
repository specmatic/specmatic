package io.specmatic.test.listeners

import org.fusesource.jansi.Ansi
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestIdentifier

class ColorPrinter: ContractExecutionPrinter {
    override fun printFinalSummary(testSummary: TestSummary) {
        val color = summaryColor(testSummary)
        println(color.a(testSummary.message).reset())
        println()
        println("Executed at ${currentDateAndTime()}")
    }

    internal fun summaryColor(testSummary: TestSummary): Ansi {
        return when {
            testSummary.aborted > 0 -> Ansi.ansi().fgBrightRed()
            testSummary.partialSuccess > 0 -> Ansi.ansi().fgYellow()
            else -> Ansi.ansi().fgGreen()
        }
    }

    override fun printTestSummary(testIdentifier: TestIdentifier?, testExecutionResult: TestExecutionResult?) {
        val color: Ansi = when(testExecutionResult?.status) {
            TestExecutionResult.Status.SUCCESSFUL -> Ansi.ansi().fgGreen()
            TestExecutionResult.Status.ABORTED -> Ansi.ansi().fgYellow()
            TestExecutionResult.Status.FAILED -> Ansi.ansi().fgBrightRed()
            else -> Ansi.ansi()
        }

        println(color.a(testStatusMessage(testIdentifier, testExecutionResult)).reset())
    }

    override fun printFailureTitle(failures: String) {
        println(failures)
    }
}
