package io.specmatic.test.listeners

import java.io.PrintStream
import org.fusesource.jansi.Ansi
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestIdentifier

class ColorPrinter(private val out: PrintStream = System.out): ContractExecutionPrinter {
    override fun printFinalSummary(testSummary: TestSummary) {
        val (_, partialSuccesses, aborted) = testSummary

        val color = when {
            aborted > 0 -> Ansi.ansi().fgBrightRed()
            partialSuccesses > 0 -> Ansi.ansi().fgYellow()
            else -> Ansi.ansi().fgGreen()
        }

        out.println(color.a(testSummary.message).reset())
        out.println()
        out.println("Executed at ${currentDateAndTime()}")
    }

    override fun printTestSummary(testIdentifier: TestIdentifier?, testExecutionResult: TestExecutionResult?) {
        val color: Ansi = when(testExecutionResult?.status) {
            TestExecutionResult.Status.SUCCESSFUL -> Ansi.ansi().fgGreen()
            TestExecutionResult.Status.ABORTED -> Ansi.ansi().fgYellow()
            TestExecutionResult.Status.FAILED -> Ansi.ansi().fgBrightRed()
            else -> Ansi.ansi()
        }

        out.println(color.a(testStatusMessage(testIdentifier, testExecutionResult)).reset())
    }

    override fun printFailureTitle(failures: String) {
        out.println(failures)
    }
}
