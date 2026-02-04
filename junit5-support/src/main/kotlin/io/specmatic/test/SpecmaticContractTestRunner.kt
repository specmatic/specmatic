package io.specmatic.test

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.stream.Stream

interface SpecmaticContractTestRunner {
    @TestFactory
    fun testStream(): Stream<DynamicTest>

    fun generateReports()
}