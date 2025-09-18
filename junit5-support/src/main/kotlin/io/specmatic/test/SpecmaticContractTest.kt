package io.specmatic.test

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.stream.Stream
import kotlin.jvm.optionals.getOrNull

@Volatile
private var contractTestHarnessInstance: SpecmaticJUnitSupport? = null

@ExtendWith(AfterSpecmaticContractTestExecutionCallback::class)
interface SpecmaticContractTest {
    @TestFactory
    fun contractTest(): Stream<DynamicTest> {
        val instance = SpecmaticJUnitSupport()

        synchronized(SpecmaticJUnitSupport::class.java) {
            contractTestHarnessInstance = instance
        }
        return instance.contractTest()
    }
}

class AfterSpecmaticContractTestExecutionCallback : AfterTestExecutionCallback {
    override fun afterTestExecution(context: ExtensionContext?) {
        val testInstance = context?.testInstance?.getOrNull() as? SpecmaticJUnitSupport

        if(testInstance != null) {
            testInstance.report()
        } else {
            synchronized(SpecmaticJUnitSupport::class.java) {
                contractTestHarnessInstance?.report()
            }
        }
    }
}
