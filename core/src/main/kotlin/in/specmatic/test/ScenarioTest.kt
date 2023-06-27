package `in`.specmatic.test

import `in`.specmatic.core.Result
import `in`.specmatic.core.Scenario
import `in`.specmatic.core.executeTest

class ScenarioTest(val scenario: Scenario, private val generativeTestingEnabled: Boolean = false) : ContractTest {
    override fun testResultRecord(result: Result): TestResultRecord {
        return TestResultRecord(scenario.path.replace(Regex("""\((.*):.*\)"""), "{$1}"), scenario.method, scenario.status, result.testResult())
    }

    override fun generateTestScenarios(
        testVariables: Map<String, String>,
        testBaseURLs: Map<String, String>
    ): List<ContractTest> {
        return scenario.generateContractTests(testVariables, testBaseURLs, generativeTestingEnabled)
    }

    override fun testDescription(): String {
        return scenario.testDescription()
    }

    override fun runTest(host: String?, port: String?, timeout: Int): Result {
        return runHttpTest(timeout, host!!, port!!, scenario)
    }

    override fun runTest(testBaseURL: String?, timeOut: Int): Result {
        val httpClient = HttpClient(testBaseURL!!, timeout = timeOut)
        return executeTest(scenario, httpClient).updateScenario(scenario)
    }

    private fun runHttpTest(timeout: Int, host: String, port: String, testScenario: Scenario): Result {
        val protocol = System.getProperty("protocol") ?: "http"

        return executeTest(protocol, host, port, timeout, testScenario).updateScenario(scenario)
    }

    private fun executeTest(protocol: String, host: String?, port: String?, timeout: Int, testScenario: Scenario): Result {
        val httpClient = HttpClient("$protocol://$host:$port", timeout = timeout)
        return executeTest(testScenario, httpClient)
    }
}
