package io.specmatic.core

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FeatureResponseScenarioTest {
    @Test
    fun `successful response scenarios use the same exact path method and 2xx predicate`() {
        val target = scenario(method = "POST", path = "/orders", status = 202)
        val matchingSuccess = scenario(method = "POST", path = "/orders", status = 200)
        val samePathDifferentMethod = scenario(method = "GET", path = "/orders", status = 200)
        val sameMethodDifferentPath = scenario(method = "POST", path = "/orders/(id:number)", status = 200)
        val matchingFailure = scenario(method = "POST", path = "/orders", status = 400)
        val feature = feature(target, matchingSuccess, samePathDifferentMethod, sameMethodDifferentPath, matchingFailure)

        assertThat(feature.successfulResponseScenariosFor(target)).containsExactly(target, matchingSuccess)
    }

    @Test
    fun `scenario matching path and method uses the first matching path pattern`() {
        val scenarioWithoutPath = scenario(method = "GET", path = null, status = 200)
        val wrongMethod = scenario(method = "POST", path = "/monitor/(id:number)", status = 200)
        val matchingScenario = scenario(method = "GET", path = "/monitor/(id:number)", status = 200)
        val laterMatchingScenario = scenario(method = "GET", path = "/monitor/(id:number)", status = 201)
        val feature = feature(scenarioWithoutPath, wrongMethod, matchingScenario, laterMatchingScenario)

        assertThat(feature.scenarioMatchingPathAndMethod(method = "GET", path = "/monitor/10")).isSameAs(matchingScenario)
    }

    @Test
    fun `scenario matching path and method returns null when the path does not match`() {
        val sameMethodDifferentPath = scenario(method = "GET", path = "/operations/(id:number)", status = 200)
        val feature = feature(sameMethodDifferentPath)

        assertThat(feature.scenarioMatchingPathAndMethod(method = "GET", path = "/monitor/10")).isNull()
    }

    private fun feature(vararg scenarios: Scenario): Feature {
        return Feature(name = "response scenarios", scenarios = scenarios.toList(), protocol = SpecmaticProtocol.HTTP)
    }

    private fun scenario(method: String, path: String?, status: Int): Scenario {
        return Scenario(
            ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(
                    method = method,
                    httpPathPattern = path?.let(::buildHttpPathPattern),
                ),
                httpResponsePattern = HttpResponsePattern(status = status),
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI,
            )
        )
    }
}
