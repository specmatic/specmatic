package application.test

import `in`.specmatic.core.log.logger
import `in`.specmatic.test.SpecmaticJUnitSupport
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.util.*
import kotlin.system.exitProcess

fun getContractExecutionPrinter(): ContractExecutionPrinter {
    return if(stdOutIsRedirected())
        MonochromePrinter()
    else if(colorIsRequested())
        ColorPrinter()
    else MonochromePrinter()
}

private fun colorIsRequested() = System.getenv("SPECMATIC_COLOR") == "1"

private fun stdOutIsRedirected() = System.console() == null

class ContractExecutionListener : TestExecutionListener {
    private var totalRun: Int = 0

    private var success: Int = 0
    private var failure: Int = 0
    private var aborted: Int = 0

    private val failedLog: MutableList<String> = mutableListOf()

    private var couldNotStart = false

    private val printer: ContractExecutionPrinter = getContractExecutionPrinter()

    override fun executionSkipped(testIdentifier: TestIdentifier?, reason: String?) {
        super.executionSkipped(testIdentifier, reason)
    }

    override fun executionFinished(testIdentifier: TestIdentifier?, testExecutionResult: TestExecutionResult?) {
        if (listOf("SpecmaticJUnitSupport", "backwardCompatibilityTest()", "contractTest()", "JUnit Jupiter", "JUnitBackwardCompatibilityTestRunner").any {
                    testIdentifier!!.displayName.contains(it)
                }) {
                    if(testExecutionResult?.status != TestExecutionResult.Status.SUCCESSFUL)
                        couldNotStart = true

                    return
        }

        totalRun += 1
        logger.newLine()
        logger.log(progressUpdate(totalRun, SpecmaticJUnitSupport.totalTestCount))
        logger.newLine()

        printer.printTestSummary(testIdentifier, testExecutionResult)

        when(testExecutionResult?.status) {
            TestExecutionResult.Status.SUCCESSFUL ->  {
                success++
                println()
            }
            TestExecutionResult.Status.ABORTED -> {
                aborted++
                printAndLogFailure(testExecutionResult, testIdentifier)
            }
            TestExecutionResult.Status.FAILED -> {
                failure++
                printAndLogFailure(testExecutionResult, testIdentifier)
            }
            else -> {
                logger.debug("A test called \"${testIdentifier?.displayName}\" ran but the test execution result was null. Please inform the Specmatic developer.")
            }
        }
    }

    private fun printAndLogFailure(
        testExecutionResult: TestExecutionResult,
        testIdentifier: TestIdentifier?
    ) {
        val message = testExecutionResult.throwable?.get()?.message?.replace("\n", "\n\t")?.trimIndent()
            ?: ""

        val reason = "Reason: $message"
        println("$reason\n\n")

        val log = """"${testIdentifier?.displayName} ${testExecutionResult.status}"
    ${reason.prependIndent("  ")}"""

        failedLog.add(log)
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan?) {
        org.fusesource.jansi.AnsiConsole.systemInstall()

        if(SpecmaticJUnitSupport.partialSuccesses.isNotEmpty()) {
            println()
            printer.printFailureTitle("Partial Successes:")
            println()

            SpecmaticJUnitSupport.partialSuccesses.filter { it.partialSuccessMessage != null} .forEach { result ->
                println("  " + (result.scenario?.testDescription() ?: "Unknown Scenario"))
                println("    " + result.partialSuccessMessage!!)
                println()
            }

            println()
        }

        if (failedLog.isNotEmpty()) {
            println()
            printer.printFailureTitle("Unsuccessful Scenarios:")
            println(failedLog.joinToString(System.lineSeparator()) { it.prependIndent("  ") })
            println()
        }

        printer.printFinalSummary(TestSummary(success, SpecmaticJUnitSupport.partialSuccesses.size, aborted, failure))
    }

    fun exitProcess() {
        val exitStatus = when (failure != 0 || couldNotStart) {
            true -> 1
            false -> 0
        }

        exitProcess(exitStatus)
    }
}

private fun progressUpdate(totalTestsRun: Int, countOfTests: Int): String {
    return "Tests run: $totalTestsRun/$countOfTests (${percentage(totalTestsRun, countOfTests)}%)"
}

private fun percentage(totalTestsRun: Int, countOfTests: Int): String {
    return if(countOfTests == 0)
        "0"
    else
        ((totalTestsRun.toDouble() / countOfTests.toDouble()) * 100).toInt().toString()
}
