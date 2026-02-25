package io.specmatic.test.listeners

import io.specmatic.core.log.logger
import io.specmatic.test.SpecmaticJUnitSupport
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.TestExecutionResult.Status
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    private val success = AtomicInteger(0)
    private val failure = AtomicInteger(0)
    private val aborted = AtomicInteger(0)
    private val couldNotStart = AtomicBoolean(false)
    private val testSuiteFailed = AtomicBoolean(false)
    private val printer: ContractExecutionPrinter = getContractExecutionPrinter()
    private val failedLog: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val exceptionsThrown: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())

    init {
        latestListener.set(this)
    }

    companion object {
        private val latestListener = AtomicReference<ContractExecutionListener?>(null)
        internal fun reset() { latestListener.get()?.reset() }
        fun exitCode(): Int = latestListener.get()?.exitCode() ?: 1
    }

    internal fun exitCode(): Int = if (testSuiteFailed.get() || couldNotStart.get() || failure.get() > 0) 1 else 0
    internal fun reset() {
        success.set(0)
        failure.set(0)
        aborted.set(0)
        couldNotStart.set(false)
        testSuiteFailed.set(false)
        failedLog.clear()
        exceptionsThrown.clear()
    }

    override fun testPlanExecutionStarted(testPlan: TestPlan?) {
        reset()
    }

    override fun executionFinished(testIdentifier: TestIdentifier?, testExecutionResult: TestExecutionResult?) {
        if (testIdentifier != null &&
            testIdentifier.type == TestDescriptor.Type.CONTAINER
        ) {
            testExecutionResult?.let {
                it.throwable?.ifPresent { throwable -> exceptionsThrown.add(throwable) }
                if (it.status != Status.SUCCESSFUL) {
                    testSuiteFailed.set(true)
                    if (success.get() + failure.get() + aborted.get() == 0) {
                        couldNotStart.set(true)
                    }
                }
            }

            return
        }

        printer.printTestSummary(testIdentifier, testExecutionResult)

        when (testExecutionResult?.status) {
            Status.SUCCESSFUL -> {
                success.incrementAndGet()
                println()
            }
            Status.ABORTED -> {
                aborted.incrementAndGet()
                printAndLogFailure(testExecutionResult, testIdentifier)
            }
            Status.FAILED -> {
                failure.incrementAndGet()
                printAndLogFailure(testExecutionResult, testIdentifier)
            }
            else -> {
                logger.debug("A test called \"${testIdentifier?.displayName}\" ran but the test execution result was null. Please inform the Specmatic developer.")
            }
        }
    }

    private fun printAndLogFailure(testExecutionResult: TestExecutionResult, testIdentifier: TestIdentifier?) {
        val message = testExecutionResult.throwable?.get()?.message?.trimIndent().orEmpty()
        val reason = "Reason:\n$message"
        println("$reason\n\n")

        val log = """"${testIdentifier?.displayName} ${testExecutionResult.status}"
    ${reason.prependIndent("  ")}"""

        failedLog.add(log)
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan?) {
        org.fusesource.jansi.AnsiConsole.systemInstall()

        println()

        val exceptionSnapshot = synchronized(exceptionsThrown) { exceptionsThrown.toList() }
        exceptionSnapshot.forEach { exceptionThrown ->
            logger.log(exceptionThrown)
        }

        println()

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

        val failedLogSnapshot = synchronized(failedLog) { failedLog.toList() }
        if (failedLogSnapshot.isNotEmpty()) {
            println()
            printer.printFailureTitle("Unsuccessful Scenarios:")
            println(failedLogSnapshot.joinToString(System.lineSeparator() + System.lineSeparator()) { it.prependIndent("  ") })
            println()
        }

        printer.printFinalSummary(
            TestSummary(
                success = success.get(),
                partialSuccesses = SpecmaticJUnitSupport.partialSuccesses.size,
                aborted = aborted.get(),
                failure = failure.get()
            )
        )
    }
}
