package io.specmatic.test

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
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
    override fun testStream(): Stream<DynamicTest> {
        return instance.contractTest()
    }

    override fun generateReports() {
        instance.report()
    }
}

