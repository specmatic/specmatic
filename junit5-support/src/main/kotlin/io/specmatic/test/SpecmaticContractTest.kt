package io.specmatic.test

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.stream.Stream
import kotlin.jvm.optionals.getOrNull

@ExtendWith(AfterSpecmaticContractTestExecutionCallback::class)
interface SpecmaticContractTest {
    @TestFactory
    fun contractTest(): Stream<DynamicTest> {
        return SpecmaticJUnitSupport().contractTest()
    }
}


class AfterSpecmaticContractTestExecutionCallback : AfterTestExecutionCallback {
    override fun afterTestExecution(context: ExtensionContext?) {
        val testInstance = context?.testInstance?.getOrNull() as? SpecmaticJUnitSupport ?: return
        testInstance.report()
    }
}
