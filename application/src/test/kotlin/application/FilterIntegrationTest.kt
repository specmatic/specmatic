package application

import io.specmatic.core.TestResult
import io.specmatic.core.utilities.Flags
import io.specmatic.stub.ContractStub
import io.specmatic.stub.createStub
import io.specmatic.test.SpecmaticJUnitSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class FilterIntegrationTest {

    @ParameterizedTest
    @MethodSource("filterProvider")
    fun contractTestWithDifferentFilters(filter: String, expectedSuccessfulTestCount: Int) {
        System.setProperty("filter", filter)

        SpecmaticJUnitSupport().contractTest().forEach { it.executable.execute() }

        val count = SpecmaticJUnitSupport.openApiCoverageReportInput.generate().testResultRecords.count {
            it.result == TestResult.Success
        }
        assertEquals(expectedSuccessfulTestCount, count)
    }

    @Test
    fun shouldThrowExceptionWhenNoTestsFoundDueToFiltering() {
        // Use a filter that will match no tests
        System.setProperty("filter", "METHOD='NONEXISTENT'")

        var exceptionThrown = false
        try {
            val tests = SpecmaticJUnitSupport().contractTest()
            
            // If we get here, an exception should have been thrown in contractTest()
            // because no tests were found, resulting in a failing dynamic test
            tests.forEach { 
                try {
                    it.executable.execute()
                } catch (e: AssertionError) {
                    exceptionThrown = true
                    assert(e.message?.contains("No tests found to run") == true) {
                        "Expected 'No tests found to run' error but got: ${e.message}"
                    }
                }
            }
            
        } catch (e: AssertionError) {
            exceptionThrown = true
            assert(e.message?.contains("No tests found to run") == true) {
                "Expected 'No tests found to run' error but got: ${e.message}"
            }
        }
        
        assert(exceptionThrown) { "Expected exception when no tests are found, but none was thrown" }
    }

    @Test 
    fun shouldNotThrowExceptionWhenTestsRunButNoneSucceed() {
        // Use a filter that should match some tests
        // Let's use a filter that finds tests with SUCCESS examples
        System.setProperty("filter", "EXAMPLE-NAME='SUCCESS'")

        // This should not throw a "no tests found" exception
        // because tests are found and run
        val tests = SpecmaticJUnitSupport().contractTest()
        val testList = tests.toList()
        
        // Should have found some tests  
        assert(testList.isNotEmpty()) { "Expected to find tests with SUCCESS examples, but found ${testList.size} tests" }
        
        // Execute the tests - they should run (not throw "no tests found")
        // We don't care if they pass or fail, just that they don't throw "no tests found"
        testList.forEach { test ->
            try {
                test.executable.execute()
            } catch (e: AssertionError) {
                // Test failures are allowed, but NOT "no tests found" errors
                if (e.message?.contains("No tests found to run") == true) {
                    throw AssertionError("Got unexpected 'No tests found to run' error when tests should have been found and run: ${e.message}")
                }
                // Other assertion errors are fine - tests can fail
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


            // Start Specmatic Http Stub
            httpStub = createStub(port = port)
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            // Shutdown Specmatic Http Stub
            httpStub.close()
        }
    }
}
