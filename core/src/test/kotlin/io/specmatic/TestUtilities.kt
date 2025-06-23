package io.specmatic

import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.ContractSourceEntry
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpExpectations
import io.specmatic.stub.HttpStubData
import io.specmatic.stub.HttpStubResponse
import io.specmatic.stub.ThreadSafeListOfStubs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertFalse
import java.io.File

fun toReport(result: Result, scenarioMessage: String? = null): String {
    return when (result) {
        is Result.Failure -> {
            result.toFailureReport(scenarioMessage)
        }
        else -> SuccessReport
    }.toString()
}

fun List<String>.toContractSourceEntries(): List<ContractSourceEntry> {
    return this.map { ContractSourceEntry(it) }
}

object Utils {
    fun readTextResource(path: String) =
        File(
            javaClass.classLoader.getResource(path)?.file
                ?: throw ContractException("Could not find resource file $path")
        ).readText()
}

fun optionalPattern(pattern: Pattern): AnyPattern {
    val patterns = listOf(DeferredPattern("(empty)"), pattern)
    return AnyPattern(patterns, extensions = patterns.extractCombinedExtensions())
}

infix fun Value.shouldMatch(pattern: Pattern) {
    val result = pattern.matches(this, Resolver())
    if(!result.isSuccess()) println(toReport(result))
    assertThat(result).isInstanceOf(Result.Success::class.java)
}

infix fun Value.shouldNotMatch(pattern: Pattern) {
    assertFalse(pattern.matches(this, Resolver()).isSuccess())
}

fun emptyPattern() = DeferredPattern("(empty)")

infix fun String.backwardCompatibleWith(oldContractGherkin: String) {
    val results = testBackwardCompatibility(oldContractGherkin)
    assertThat(results.success()).isTrue()
    assertThat(results.failureCount).isZero()

    stubsFrom(oldContractGherkin).workWith(this)
}

infix fun String.notBackwardCompatibleWith(oldContractGherkin: String) {
    val results = testBackwardCompatibility(oldContractGherkin)
    assertThat(results.success()).isFalse()
    assertThat(results.failureCount).isPositive()

    stubsFrom(oldContractGherkin).breakOn(this)
}

fun String.testBackwardCompatibility(oldContractGherkin: String): Results {
    val oldFeature = parseGherkinStringToFeature(oldContractGherkin)
    val newFeature = parseGherkinStringToFeature(this)
    return testBackwardCompatibility(oldFeature, newFeature)
}

fun stubResponse(httpRequest: HttpRequest, features: List<Feature>, threadSafeStubs: List<HttpStubData>, strictMode: Boolean): HttpStubResponse {
    return io.specmatic.stub.getHttpResponse(
        httpRequest,
        features,
        HttpExpectations(threadSafeStubs.toMutableList()),
        strictMode
    ).response
}

fun testStub(contractGherkin: String, stubRequest: HttpRequest, stubResponse: HttpResponse): HttpResponse {
    val feature = parseGherkinStringToFeature(contractGherkin)
    val stub = ScenarioStub(stubRequest, stubResponse)
    val matchingStub = feature.matchingStub(stub)

    return stubResponse(stubRequest, listOf(feature), listOf(matchingStub), true).let {
        it.response.copy(headers = it.response.headers - SPECMATIC_RESULT_HEADER)
    }
}

fun stub(stubRequest: HttpRequest, stubResponse: HttpResponse): TestHttpStub =
        TestHttpStub(stubRequest, stubResponse)

private fun stubsFrom(oldContract: String): TestHttpStubData {
    val oldFeature = parseGherkinStringToFeature(oldContract)

    val testScenarios = oldFeature.generateBackwardCompatibilityTestScenarios()

    return TestHttpStubData(oldContract, testScenarios.map { scenario ->
        val request = scenario.generateHttpRequest()
        val response = scenario.generateHttpResponse(emptyMap())

        TestHttpStub(stubRequest = request, stubResponse = response.copy(headers = response.headers.minus(SPECMATIC_RESULT_HEADER)))
    })

}

private class TestHttpStubData(val oldContract: String, val stubs: List<TestHttpStub>) {
    fun breakOn(newContract: String) {
        for(stub in stubs) {
            stub.shouldWorkWith(oldContract)
            stub.shouldBreakWith(newContract)
        }
    }

    fun workWith(newContract: String) {
        for(stub in stubs) {
            stub.shouldWorkWith(oldContract)
            stub.shouldWorkWith(newContract)
        }
    }
}

val HttpRequest.jsonBody: JSONObjectValue
    get() {
        return this.body as JSONObjectValue
    }

const val GENERATION = "generation"

infix fun <E> List<E>.shouldContainInAnyOrder(elementList: List<E>) {
    assertThat(this).containsExactlyInAnyOrderElementsOf(elementList)
}

val DefaultStrategies = FlagsBased (
    DoNotUseDefaultExample,
    NonGenerativeTests,
    null,
    "",
    "",
    false
)


fun osAgnosticPath(path: String): String {
    return path.replace("/", File.separator)
}

fun String.trimmedLinesList(): List<String> {
    return this.lines().map { it.trim() }
}

fun String.trimmedLinesString(): String {
    return this.lines().joinToString(System.lineSeparator()) { it.trim() }
}

fun runningOnWindows(): Boolean {
    val osName = System.getProperty("os.name")

    println("OS name: $osName")

    return "windows" in osName.lowercase()
}

class Waiter(private val delayInMilliSeconds: Long, private val maximumWaitTime: Long) {
    private var totalWaitTime = 0L

    fun waitForMoreTime() {
        Thread.sleep(delayInMilliSeconds)
        totalWaitTime += delayInMilliSeconds
    }

    fun canWaitForMoreTime(): Boolean {
        return totalWaitTime < maximumWaitTime
    }

    fun hasTimeRunOut(): Boolean {
        return !canWaitForMoreTime()
    }
}