package io.specmatic.test.listeners

import org.junit.jupiter.api.Assertions.assertEquals
import io.specmatic.core.Result
import io.specmatic.core.ScenarioDetailsForResult
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.test.SkipExcludedCountTracker
import io.specmatic.test.SpecmaticJUnitSupport
import io.specmatic.test.TestRunContext
import io.specmatic.test.TestRunContextHolder
import io.specmatic.test.TestSkipReason
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.launcher.TestIdentifier
import org.fusesource.jansi.Ansi
import java.util.UUID

class ContractExecutionListenerTest {
    @BeforeEach
    fun resetState() {
        ContractExecutionListener.reset()
        SpecmaticJUnitSupport.partialSuccesses.clear()
        TestRunContextHolder.clear()
    }

    @Test
    fun `exit status is 0 when all tests pass`() {
        val listener = ContractExecutionListener()

        listener.executionFinished(testIdentifier(TestDescriptor.Type.TEST), TestExecutionResult.successful())
        listener.testPlanExecutionFinished(null)

        assertEquals(0, ContractExecutionListener.exitCode())
    }

    @Test
    fun `exit status is 0 when tests are aborted but none fail`() {
        val listener = ContractExecutionListener()

        listener.executionFinished(
            testIdentifier(TestDescriptor.Type.TEST),
            TestExecutionResult.aborted(RuntimeException("skipped"))
        )
        listener.testPlanExecutionFinished(null)

        assertEquals(0, ContractExecutionListener.exitCode())
    }

    @Test
    fun `test plan reports partial successes`() {
        val listener = ContractExecutionListener()

        val partialSuccess = Result.Success(partialSuccessMessage = "partial coverage").updateScenario(FakeScenario())
        SpecmaticJUnitSupport.partialSuccesses.add(partialSuccess)

        listener.testPlanExecutionFinished(null)

        assertEquals(0, ContractExecutionListener.exitCode())
    }

    @Test
    fun `exit status is 1 when the test suite fails`() {
        val listener = ContractExecutionListener()

        listener.executionFinished(testIdentifier(TestDescriptor.Type.TEST), TestExecutionResult.successful())
        listener.executionFinished(
            testIdentifier(TestDescriptor.Type.CONTAINER),
            TestExecutionResult.failed(RuntimeException("coverage threshold not met"))
        )
        listener.testPlanExecutionFinished(null)

        assertEquals(1, ContractExecutionListener.exitCode())
    }

    @Test
    fun `exit status is 1 when any test fails`() {
        val listener = ContractExecutionListener()

        listener.executionFinished(
            testIdentifier(TestDescriptor.Type.TEST),
            TestExecutionResult.failed(AssertionError("boom"))
        )
        listener.testPlanExecutionFinished(null)

        assertEquals(1, ContractExecutionListener.exitCode())
    }

    @Test
    fun `listener instances maintain independent state`() {
        val failedRunListener = ContractExecutionListener()
        val successfulRunListener = ContractExecutionListener()

        failedRunListener.executionFinished(testIdentifier(TestDescriptor.Type.TEST), TestExecutionResult.failed(AssertionError("Kaboom ? Yes Rico Kaboom!")))
        failedRunListener.testPlanExecutionFinished(null)

        successfulRunListener.executionFinished(testIdentifier(TestDescriptor.Type.TEST), TestExecutionResult.successful())
        successfulRunListener.testPlanExecutionFinished(null)

        assertEquals(1, failedRunListener.exitCode())
        assertEquals(0, successfulRunListener.exitCode())
    }

    @Test
    fun `static exitCode tracks the latest listener instance`() {
        val failedRunListener = ContractExecutionListener()
        failedRunListener.executionFinished(testIdentifier(TestDescriptor.Type.TEST), TestExecutionResult.failed(AssertionError("boom")))
        failedRunListener.testPlanExecutionFinished(null)
        assertEquals(1, ContractExecutionListener.exitCode())

        val successfulRunListener = ContractExecutionListener()
        successfulRunListener.executionFinished(testIdentifier(TestDescriptor.Type.TEST), TestExecutionResult.successful())
        successfulRunListener.testPlanExecutionFinished(null)

        assertEquals(0, ContractExecutionListener.exitCode())
    }

    @Test
    fun `testPlanExecutionStarted resets listener state and prevents accumulation across runs`() {
        val listener = ContractExecutionListener()

        listener.executionFinished(testIdentifier(TestDescriptor.Type.TEST), TestExecutionResult.failed(AssertionError("first run failure")))
        listener.testPlanExecutionFinished(null)
        assertEquals(1, listener.exitCode())

        listener.testPlanExecutionStarted(null)
        listener.executionFinished(testIdentifier(TestDescriptor.Type.TEST), TestExecutionResult.successful())
        listener.testPlanExecutionFinished(null)

        assertEquals(0, listener.exitCode())
        assertEquals(0, ContractExecutionListener.exitCode())
    }

    @Test
    fun `summary provider counts excluded and skipped decisions`() {
        val tracker = SkipExcludedCountTracker()
        tracker.record(Decision.Skip(context = FakeScenario(), reasoning = Reasoning(TestSkipReason.EXCLUDED)))
        tracker.record(Decision.Skip(context = FakeScenario(), reasoning = Reasoning(TestSkipReason.MAX_TEST_COUNT_EXCEEDED)))

        val counts = tracker.snapshot()
        assertEquals(1, counts.excluded)
        assertEquals(1, counts.skipped)
    }

    @Test
    fun `listener falls back to zero summary when provider missing`() {
        val listener = ContractExecutionListener()
        listener.testPlanExecutionStarted(null)

        val counts = listener.getNotTestedCountsAndClear()
        assertEquals(0, counts.skipped)
        assertEquals(0, counts.excluded)
    }

    @Test
    fun `listener reads summary provider from thread local and clears it when finished`() {
        val listener = ContractExecutionListener()
        val provider = SkipExcludedCountTracker()
        TestRunContextHolder.set(TestRunContext(provider))
        provider.record(Decision.Skip(context = FakeScenario(), reasoning = Reasoning(TestSkipReason.EXCLUDED)))

        listener.testPlanExecutionStarted(null)
        val firstRun = listener.getNotTestedCountsAndClear()
        assertEquals(0, firstRun.skipped)
        assertEquals(1, firstRun.excluded)

        listener.testPlanExecutionFinished(null)
        val secondRun = listener.getNotTestedCountsAndClear()
        assertEquals(null, TestRunContextHolder.get())
        assertEquals(0, secondRun.skipped)
        assertEquals(0, secondRun.excluded)
    }

    @Test
    fun `summary color reflects failure before skipped`() {
        val printer = ColorPrinter()
        val red = printer.summaryColor(TestSummary(success = 1, failure = 0, aborted = 1, skipped = 1, excluded = 0, partialSuccess = 0)).a("x").toString()
        val yellow = printer.summaryColor(TestSummary(success = 1, failure = 0, aborted = 0, skipped = 0, excluded = 0, partialSuccess = 1)).a("x").toString()
        val green = printer.summaryColor(TestSummary(success = 1, failure = 0, aborted = 0, skipped = 1, excluded = 0, partialSuccess = 0)).a("x").toString()

        assertEquals(Ansi.ansi().fgBrightRed().a("x").toString(), red)
        assertEquals(Ansi.ansi().fgYellow().a("x").toString(), yellow)
        assertEquals(Ansi.ansi().fgGreen().a("x").toString(), green)
    }

    @Test
    fun `summary counts failures and total without double counting partial successes`() {
        val summary = TestSummary(
            success = 7,
            failure = 2,
            aborted = 3,
            skipped = 4,
            excluded = 5,
            partialSuccess = 6,
        )

        assertEquals(5, summary.failed)
        assertEquals(21, summary.total)
        assertEquals(6, summary.partialSuccess)
        assertEquals("Success: 7, Failure: 5, Skipped: 4, Excluded: 5, Total: 21", summary.message)
    }
}

private fun testIdentifier(type: TestDescriptor.Type): TestIdentifier {
    val uniqueId = UniqueId.forEngine("specmatic-tests").append("id", UUID.randomUUID().toString())
    val descriptor = object : AbstractTestDescriptor(uniqueId, "sample") {
        override fun getType(): TestDescriptor.Type = type
    }
    return TestIdentifier.from(descriptor)
}

private class FakeScenario : ScenarioDetailsForResult {
    override val status: Int = 200
    override val ignoreFailure: Boolean = false
    override val name: String = "Partial success scenario"
    override val method: String = "GET"
    override val path: String = "/partial-success"

    override fun testDescription(): String = name

    override fun operationDescription() = "operation 1"
    override fun failureReportSubHeading() = "API: ${operationDescription()}"
}
