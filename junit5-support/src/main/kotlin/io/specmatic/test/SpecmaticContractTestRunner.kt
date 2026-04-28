package io.specmatic.test

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.ServiceLoader
import java.util.stream.Stream

interface SpecmaticContractTestRunner {

    @TestFactory
    fun contractTest(): Stream<DynamicTest> {
        val beforeEachHooks = ServiceLoader.load(BeforeEachTestHook::class.java).mapNotNull { it }
        return testStream().map { dynamicTest ->
            DynamicTest.dynamicTest(dynamicTest.displayName) {
                beforeEachHooks.forEach { it.execute() }
                dynamicTest.executable.execute()
            }
        }
    }

    fun testStream(): Stream<DynamicTest>

    fun generateReports()
}