package io.specmatic.test

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.extension.ExtendWith
import java.util.stream.Stream

interface SpecmaticContractTest : SpecmaticContractTestRunner {
    companion object {
        val instance = SpecmaticJUnitSupport()

        @JvmStatic
        @AfterAll
        fun tearDown() {
            instance.report()
        }
    }

    @TestFactory
    @ExtendWith(ContractTestExecutionContextResolver::class)
    override fun testStream(): Stream<DynamicTest> {
        return instance.contractTest(ContractTestExecutionContextResolver.current.get())
    }

    override fun generateReports() {
        instance.report()
    }
}

