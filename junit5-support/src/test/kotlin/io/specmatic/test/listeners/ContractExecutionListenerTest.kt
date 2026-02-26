package io.specmatic.test.listeners

import org.junit.jupiter.api.Assertions.assertEquals
import io.specmatic.core.Result
import io.specmatic.core.ScenarioDetailsForResult
import io.specmatic.test.SpecmaticJUnitSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.launcher.TestIdentifier
import java.util.UUID

class ContractExecutionListenerTest {
    @BeforeEach
    fun resetState() {
        ContractExecutionListener.reset()
        SpecmaticJUnitSupport.partialSuccesses.clear()
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

        val partialSuccess = Result.Success(partialSuccessMessage = "partial coverage")
        partialSuccess.scenario = FakeScenario()
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
}
