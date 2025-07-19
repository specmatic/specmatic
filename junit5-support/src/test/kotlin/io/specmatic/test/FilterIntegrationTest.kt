package io.specmatic.test

import io.specmatic.core.TestResult
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.Flags
import io.specmatic.stub.ContractStub
import io.specmatic.stub.createStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.ServerSocket
import java.util.stream.Stream

private const val MESSAGE_FRAGMENT_WHEN_NO_TESTS_WERE_FOUND = "No tests found to run"

class FilterIntegrationTest {

    @ParameterizedTest
    @MethodSource("filterProvider")
    fun contractTestWithDifferentFilters(filter: String, expectedSuccessfulTestCount: Int) {
        System.setProperty("filter", filter)

        SpecmaticJUnitSupport().contractTest().forEach {
            try {
                it.executable.execute()
            } catch(e: Throwable) {
                println(e)
            }
        }

        val count = SpecmaticJUnitSupport.currentInstance?.openApiCoverageReportInput?.generate()?.testResultRecords?.count {
            it.result == TestResult.Success
        } ?: 0
        assertEquals(expectedSuccessfulTestCount, count)
    }

    @Test
    fun shouldThrowExceptionWhenNoTestsFoundDueToFiltering() {
        System.setProperty("filter", "METHOD='NONEXISTENT'")

        val tests = SpecmaticJUnitSupport().contractTest().toList()

        assertThat(tests.count()).isOne()

        try {
            tests.single().executable.execute()
            fail("Expected exception when no tests are found, but none was thrown")
        } catch (e: AssertionError) {
            assert(e.message?.contains(MESSAGE_FRAGMENT_WHEN_NO_TESTS_WERE_FOUND) == true) {
                "Expected '$MESSAGE_FRAGMENT_WHEN_NO_TESTS_WERE_FOUND' error but got: ${e.message}"
            }
        }
    }

    @Test
    fun shouldNotThrowExceptionWhenTestsRunButNoneSucceed() {
        System.setProperty("filter", "EXAMPLE-NAME='SUCCESS'")

        val tests = SpecmaticJUnitSupport().contractTest().toList()

        assert(tests.isNotEmpty()) { "Expected to find tests with SUCCESS examples, but found ${tests.size} tests" }

        tests.forEach { test ->
            try {
                test.executable.execute()
            } catch (e: AssertionError) {
                if (e.message?.contains(MESSAGE_FRAGMENT_WHEN_NO_TESTS_WERE_FOUND) == true) {
                    throw AssertionError("Got unexpected '$MESSAGE_FRAGMENT_WHEN_NO_TESTS_WERE_FOUND' error when tests should have been found and run: ${e.message}")
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun filterProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("EXAMPLE-NAME='SUCCESS'", 4),
                Arguments.of("EXAMPLE-NAME!='SUCCESS'", 1),
                Arguments.of("EXAMPLE-NAME='SUCCESS,TIMEOUT'", 5),
                Arguments.of("EXAMPLE-NAME!='SUCCESS,TIMEOUT'", 0),
                Arguments.of("PARAMETERS.QUERY='type'", 2),
                Arguments.of("PARAMETERS.QUERY!='type'", 3),
                Arguments.of("PARAMETERS.QUERY='type,sortBy'", 2),
                Arguments.of("PARAMETERS.QUERY!='type,sortBy'", 3),
                Arguments.of("PARAMETERS.HEADER='request-id'", 2),
                Arguments.of("PARAMETERS.HEADER!='request-id'", 3),
                Arguments.of("PARAMETERS.HEADER='request-id,pageSize'", 2),
                Arguments.of("PARAMETERS.HEADER!='request-id,pageSize'", 3),
                Arguments.of("PATH='/findAvailableProducts' && METHOD='GET' && STATUS<'400'", 1),
                Arguments.of("PATH='/orders' && STATUS<'400'", 2),
                Arguments.of("PATH='/findAvailableProducts' && STATUS>='200' && STATUS <='299'", 1),
                Arguments.of("(PATH='/findAvailableProducts' || PATH='/orders') && STATUS>='200' && STATUS <='299'", 3),
                Arguments.of("PATH='/findAvailableProducts,/orders' && STATUS>='200' && STATUS <='299'", 3),
                Arguments.of("PATH='/*' && STATUS>='200' && STATUS <='299' && METHOD='POST'", 2),
                Arguments.of("STATUS>='200' && STATUS <='299' && METHOD='DELETE'", 0),
                Arguments.of("STATUS>='200' && STATUS <='299' && METHOD='GET'", 2),
                Arguments.of("STATUS>'199' && STATUS<'300' && METHOD='GET'", 2),
                Arguments.of("STATUS>='200' && STATUS<='300' && METHOD='GET'", 2),
                Arguments.of("PATH='/findAvailableProducts' && PARAMETERS.QUERY='type' && STATUS='200'", 1),
                Arguments.of("PATH='/findAvailableProducts' && PARAMETERS.QUERY!='type' && STATUS='200'", 0),
                Arguments.of("PATH='/findAvailableProducts' && EXAMPLE-NAME!='TIMEOUT' && STATUS='200'", 1),
                Arguments.of("PATH='/findAvailableProducts' && EXAMPLE-NAME='SUCCESS' && STATUS>='200' && STATUS<'300'", 1),
                Arguments.of("PATH='/orders,/products' && STATUS<'400'", 3),
                Arguments.of("PATH='/orders' && STATUS<'400'", 2),
                Arguments.of("PATH='/findAvailableProducts' && METHOD='GET' && STATUS<'400'", 1),
                Arguments.of("PATH='/orders,/products' && STATUS<'400' && PARAMETERS.QUERY='orderId'", 1),
                Arguments.of("PATH='/orders' && STATUS<'400' && PARAMETERS.QUERY!='orderId'", 1),
                Arguments.of("PATH='/findAvailableProducts' && PARAMETERS.HEADER='request-id' && STATUS<'400'", 1),
                Arguments.of("PATH='/findAvailableProducts' && PARAMETERS.HEADER!='request-id' && STATUS<'400'", 0),
            )
        }

        private lateinit var httpStub: ContractStub

        @JvmStatic
        @BeforeAll
        fun setUp() {
            val port = findRandomFreePort()
            System.setProperty("testBaseURL", "http://localhost:$port")
            System.setProperty(Flags.CONFIG_FILE_PATH, "src/test/resources/filter_test/specmatic_filter.yaml")

            httpStub = createStub(port = port)
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            System.clearProperty("testBaseURL")
            System.clearProperty(Flags.CONFIG_FILE_PATH)
            httpStub.close()
        }

        private fun findRandomFreePort(): Int {
            logger.log("Checking for a free port")

            val port = ServerSocket(0).use { it.localPort }

            if (port > 0) {
                logger.log("Free port found: $port")
                return port
            }
            throw RuntimeException("Could not find a free port")
        }
    }
}
