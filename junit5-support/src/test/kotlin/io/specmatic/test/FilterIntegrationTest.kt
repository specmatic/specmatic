package io.specmatic.test

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.test.interceptor.ContractTestInterceptorContext
import io.specmatic.test.interceptor.ContractTestInterceptorProvider
import io.specmatic.core.utilities.Flags
import io.specmatic.reporter.model.TestResult
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
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.opentest4j.TestAbortedException
import java.net.ServerSocket
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream

private const val MESSAGE_FRAGMENT_WHEN_NO_TESTS_WERE_FOUND = "No tests found to run"

@Isolated
class FilterIntegrationTest {
    @ParameterizedTest
    @MethodSource("filterProvider")
    fun contractTestWithDifferentFilters(filter: String, expectedSuccessfulTestCount: Int) {
        System.setProperty("filter", filter)

        val contractTestHarness = SpecmaticJUnitSupport()

        contractTestHarness.contractTest().forEach {
            try {
                it.executable.execute()
            } catch(e: Throwable) {
                println(e)
            }
        }

        val count = contractTestHarness.openApiCoverage.generate().testResultRecords.count { it.result == TestResult.Success }
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
            } catch (_: TestAbortedException) {
                // A matching example can still belong to a @WIP scenario; this test only asserts
                // that matching scenarios are found and executed rather than tripping the
                // "No tests found to run" path.
            }
        }
    }

    @ParameterizedTest
    @MethodSource("factoryExecutionModes")
    fun `provider receives the filtered scenarios and the effective JUnit execution mode`(factory: Class<*>, enabled: Boolean, parallel: Boolean) {
        val previousFilter = System.getProperty("filter")
        val captured = AtomicReference<ContractTestInterceptorContext>()
        System.setProperty("filter", "PATH='/findAvailableProducts' && METHOD='GET' && STATUS='200'")
        try {
            mockkObject(ContractTestInterceptorProvider.Companion)
            every { ContractTestInterceptorProvider.interceptorsFor(any<ContractTestInterceptorContext>()) } answers {
                captured.set(firstArg())
                throw ContractException("Resource workflow setup failed")
            }

            val request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(factory))
                .configurationParameter("junit.jupiter.execution.parallel.enabled", enabled.toString())
                .configurationParameter("junit.jupiter.execution.parallel.mode.default", "same_thread")
                .configurationParameter(RUN_FACTORY_FIXTURES, "true")
                .build()

            val listener = SummaryGeneratingListener()
            LauncherFactory.create().execute(request, listener)

            val context = requireNotNull(captured.get())
            assertThat(context.parallelExecutionEnabled).isEqualTo(parallel)
            assertThat(context.feature.scenarios.size).isGreaterThan(context.selectedScenarios.size)
            assertThat(context.selectedScenarios.map { Triple(it.method, it.path, it.status) })
                .containsExactly(Triple("GET", "/findAvailableProducts", 200))
            assertThat(listener.summary.failures.map { it.exception.message })
                .singleElement().asString().contains("Resource workflow setup failed")
        } finally {
            try {
                unmockkObject(ContractTestInterceptorProvider.Companion)
            } finally {
                if (previousFilter == null) System.clearProperty("filter") else System.setProperty("filter", previousFilter)
            }
        }
    }

    @ExtendWith(FactoryFixtureCondition::class)
    class InterfaceContractTests : SpecmaticContractTest

    @Execution(ExecutionMode.CONCURRENT)
    @ExtendWith(FactoryFixtureCondition::class)
    class ConcurrentInterfaceContractTests : SpecmaticContractTest

    @Execution(ExecutionMode.SAME_THREAD)
    @ExtendWith(FactoryFixtureCondition::class)
    class SerialJUnitSupport : SpecmaticJUnitSupport()

    class FactoryFixtureCondition : ExecutionCondition {
        override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
            return if (context.getConfigurationParameter(RUN_FACTORY_FIXTURES).orElse("false").toBoolean()) {
                ConditionEvaluationResult.enabled("Selected by the filter integration test launcher")
            } else {
                ConditionEvaluationResult.disabled("Fixture requires the filter integration test launcher")
            }
        }
    }

    companion object {
        private const val RUN_FACTORY_FIXTURES = "specmatic.test.run-filter-factory-fixtures"

        @JvmStatic
        fun factoryExecutionModes(): List<Arguments> = listOf(
            Arguments.of(SpecmaticJUnitSupport::class.java, true, true),
            Arguments.of(SerialJUnitSupport::class.java, true, false),
            Arguments.of(ConcurrentInterfaceContractTests::class.java, true, true),
            Arguments.of(InterfaceContractTests::class.java, true, false),
            Arguments.of(SpecmaticJUnitSupport::class.java, false, false),
        )

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
            System.clearProperty("filter")
            httpStub.close()
        }

        private fun findRandomFreePort(): Int {
            logger.log("Checking for a free port")

            repeat(100) {
                val port = ThreadLocalRandom.current().nextInt(40_000, 60_000)
                if (runCatching { ServerSocket(port).use { true } }.getOrDefault(false)) {
                    logger.log("Free port found: $port")
                    return port
                }
            }
            throw RuntimeException("Could not find a free port")
        }
    }
}
