package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.osAgnosticPath
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.testBackwardCompatibility
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.model.TestResult
import io.specmatic.stub.HttpStub
import io.specmatic.stub.listener.MockEvent
import io.specmatic.stub.listener.MockEventListener
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.ServerSocket

class InterpolatedPathsE2ETest {
    private val specFile = File("src/test/resources/openapi/interpolated_paths_e2e.yaml")
    private val externalExamplesDir = File("src/test/resources/openapi/interpolated_paths_e2e_examples")

    private fun loadFeature(version: String, loadExternalExamples: Boolean = false, mutate: (String) -> String = { it }): Feature {
        val specText = specFile.readText().replaceFirst(Regex("""openapi:\s*[0-9.]+"""), "openapi: $version").let(mutate)
        val feature = OpenApiSpecification.fromYAML(specText, specFile.path).toFeature()
        return if (loadExternalExamples) feature.loadExternalisedExamples() else feature
    }

    private fun externalScenarioStubs(): List<ScenarioStub> {
        return externalExamplesDir.listFiles()?.sortedBy { it.name }?.map { ScenarioStub.readFromFile(it) } ?: emptyList()
    }

    private fun Feature.onlyPath(internalPath: String): Feature {
        return copy(scenarios = scenarios.filter { it.path == internalPath })
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun captureMockEvents(testBlock: (MockEventListener) -> Unit): List<MockEvent> {
        val events = mutableListOf<MockEvent>()
        val listener = object : MockEventListener {
            override fun onRespond(data: MockEvent) {
                events.add(data)
            }
        }

        testBlock(listener)
        return events
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.3", "3.1.0"])
    fun `contract tests should run full generation for interpolated paths with dictionary`(openApiVersion: String) {
        val generatePath = "/generate/(orderId:string)-(itemId:string)/status"
        val feature = loadFeature(openApiVersion).onlyPath(generatePath).enableGenerativeTesting()

        val expectedStatusesSeen = mutableListOf<Int>()
        var positiveDictionaryPathSeen = false
        val results = feature.executeTests(object : TestExecutor {
            private var expectedStatus = 0

            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                expectedStatus = scenario.status
                expectedStatusesSeen.add(expectedStatus)
            }

            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.path ?: "").matches("^/generate/.+-.+/status$")
                if (expectedStatus == 200) {
                    positiveDictionaryPathSeen = request.path == "/generate/DICT-ORDER-DICT-ITEM/status"
                }

                return if (expectedStatus == 200) {
                    HttpResponse.ok(parsedJSONObject("""{"message":"ok","source":"executor"}"""))
                } else {
                    HttpResponse(400, parsedJSONObject("""{"error":"bad-request"}"""))
                }
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(results.testCount).isEqualTo(13)
        assertThat(expectedStatusesSeen).contains(200, 400)
        assertThat(positiveDictionaryPathSeen).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.3", "3.1.0"])
    fun `contract tests should validate inline and external examples for interpolated paths`(openApiVersion: String) {
        val examplePath = "/example/(id1:string),(id2:string)/status"
        val feature = loadFeature(openApiVersion, loadExternalExamples = true).onlyPath(examplePath)
        val seenPaths = mutableListOf<String>()
        val seenStatuses = mutableListOf<Int>()

        val results = feature.executeTests(object : TestExecutor {
            override fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
                seenStatuses.add(scenario.status)
            }

            override fun execute(request: HttpRequest): HttpResponse {
                val path = request.path.orEmpty()
                seenPaths.add(path)

                return when (path) {
                    "/example/inline-A,inline-B/status" ->
                        HttpResponse.ok(parsedJSONObject("""{"message":"inline-ok","source":"inline"}"""))

                    "/example/missing-A,missing-B/status" ->
                        HttpResponse(404, parsedJSONObject("""{"error":"inline-not-found"}"""))

                    "/example/external-A,external-B/status" ->
                        HttpResponse(202, parsedJSONObject("""{"message":"external-ok","source":"external"}"""))

                    "/example/external-missing-A,external-missing-B/status" ->
                        HttpResponse(404, parsedJSONObject("""{"error":"external-not-found"}"""))

                    "/example/token-A,DICT-ID2/status" ->
                        HttpResponse(202, parsedJSONObject("""{"message":"DICT_EXAMPLE","source":"token-external"}"""))

                    else -> HttpResponse(500, parsedJSONObject("""{"error":"unexpected-path"}"""))
                }
            }
        })

        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(results.testCount).isEqualTo(5)
        assertThat(seenStatuses).containsExactlyInAnyOrder(200, 202, 202, 404, 404)
        assertThat(seenPaths).containsExactlyInAnyOrder(
            "/example/inline-A,inline-B/status",
            "/example/missing-A,missing-B/status",
            "/example/external-A,external-B/status",
            "/example/external-missing-A,external-missing-B/status",
            "/example/token-A,DICT-ID2/status"
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.3", "3.1.0"])
    fun `stub should serve inline and external examples and emit expected mock events`(openApiVersion: String) {
        val feature = loadFeature(openApiVersion)
        val events = captureMockEvents { listener ->
            HttpStub(
                feature,
                scenarioStubs = externalScenarioStubs(),
                port = freePort(),
                listeners = listOf(listener)
            ).use { stub ->
                val inlineResponse = stub.client.execute(HttpRequest("GET", "/example/inline-A,inline-B/status"))
                val externalResponse = stub.client.execute(HttpRequest("GET", "/example/external-A,external-B/status"))
                val externalTokenResponse = stub.client.execute(HttpRequest("GET", "/example/token-A,DICT-ID2/status"))
                val generatedResponse = stub.client.execute(
                    HttpRequest(
                        method = "POST",
                        path = "/generate/DICT-ORDER-DICT-ITEM/status",
                        body = parsedJSONObject("""{"state":"READY","quantity":1}""")
                    )
                )

                assertThat(inlineResponse.status).isEqualTo(200)
                assertThat(externalResponse.status).isEqualTo(202)
                assertThat(externalTokenResponse.status).isEqualTo(202)
                assertThat(generatedResponse.status).isEqualTo(200)

                val externalTokenBody = externalTokenResponse.body as JSONObjectValue
                assertThat(externalTokenBody.jsonObject["message"]).isEqualTo(StringValue("DICT_EXAMPLE"))
                assertThat(externalTokenBody.jsonObject["source"]).isEqualTo(StringValue("token-external"))

                val generatedBody = generatedResponse.body as JSONObjectValue
                assertThat(generatedBody.jsonObject["message"]).isEqualTo(StringValue("DICT_RESPONSE"))
                assertThat(generatedBody.jsonObject["source"]).isEqualTo(StringValue("dictionary"))
            }
        }

        assertThat(events).hasSize(4)
        val inlineEvent = events.first { it.request.path == "/example/inline-A,inline-B/status" }
        val externalEvent = events.first { it.request.path == "/example/external-A,external-B/status" }
        val externalTokenEvent = events.first { it.request.path == "/example/token-A,DICT-ID2/status" }
        val generatedEvent = events.first { it.request.path == "/generate/DICT-ORDER-DICT-ITEM/status" }

        assertThat(inlineEvent.details).isEqualToIgnoringWhitespace("Request Matched Inline Example: INLINE_OK")
        assertThat(inlineEvent.stubResult).isEqualTo(TestResult.Success)

        assertThat(externalEvent.details).isEqualToIgnoringWhitespace("Request Matched External Example: ${osAgnosticPath("src/test/resources/openapi/interpolated_paths_e2e_examples/external_success_202.json")}")
        assertThat(externalEvent.stubResult).isEqualTo(TestResult.Success)

        assertThat(externalTokenEvent.details).isEqualToIgnoringWhitespace("Request Matched External Example: ${osAgnosticPath("src/test/resources/openapi/interpolated_paths_e2e_examples/external_partial_tokens_202.json")}")
        assertThat(externalTokenEvent.stubResult).isEqualTo(TestResult.Success)

        assertThat(generatedEvent.details).isEqualToIgnoringWhitespace("Request Matched Contract POST /generate/(orderId:string)-(itemId:string)/status -> 200")
        assertThat(generatedEvent.stubResult).isEqualTo(TestResult.Success)
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.3", "3.1.0"])
    fun `loop test should pass when contract tests run against stub for interpolated paths`(openApiVersion: String) {
        val feature = loadFeature(openApiVersion, loadExternalExamples = true)
        val results = HttpStub(feature, scenarioStubs = externalScenarioStubs(), port = freePort()).use { stub ->
            feature.executeTests(stub.client)
        }

        assertThat(results.testCount).isEqualTo(7)
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.3", "3.1.0"])
    fun `backward compatibility should fail for interpolated path when parameter type becomes incompatible`(openApiVersion: String) {
        val oldFeature = loadFeature(openApiVersion)
        val newFeatureWithIncompatiblePathType = loadFeature(openApiVersion) { original ->
            original.replaceFirst(
                Regex("""(-\sname:\sid2\s+in:\spath\s+required:\strue\s+schema:\s+type:\s*)string"""),
                "$1number"
            )
        }

        assertThat(newFeatureWithIncompatiblePathType.path).isEqualToIgnoringWhitespace(specFile.path)
        val compatibility = testBackwardCompatibility(oldFeature, newFeatureWithIncompatiblePathType)

        assertThat(compatibility.success()).isFalse()
        assertThat(compatibility.report()).isEqualToNormalizingWhitespace(
            """
In scenario "Inline and external examples endpoint. Response: Inline success"
API: GET /example/(id1:string),(id2:number)/status -> 200

  >> REQUEST.PARAMETERS.PATH.id2
  
      R1001: Type mismatch
      Documentation: https://docs.specmatic.io/rules#r1001
      Summary: The value type does not match the expected type defined in the specification
  
      This is type number in the new specification, but type string in the old specification

In scenario "Inline and external examples endpoint. Response: External success"
API: GET /example/(id1:string),(id2:number)/status -> 202

  >> REQUEST.PARAMETERS.PATH.id2
  
      R1001: Type mismatch
      Documentation: https://docs.specmatic.io/rules#r1001
      Summary: The value type does not match the expected type defined in the specification
  
      This is type number in the new specification, but type string in the old specification

In scenario "Inline and external examples endpoint. Response: Inline not found"
API: GET /example/(id1:string),(id2:number)/status -> 404

  >> REQUEST.PARAMETERS.PATH.id2
  
      R1001: Type mismatch
      Documentation: https://docs.specmatic.io/rules#r1001
      Summary: The value type does not match the expected type defined in the specification
  
      This is type number in the new specification, but type string in the old specification
            """.trimIndent()
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.3"])
    fun `backward compatibility should pass across equivalent 30 and 31 interpolated specs`() {
        val openApi30Feature = loadFeature("3.0.3")
        val openApi31Feature = loadFeature("3.1.0")

        val oldToNew = testBackwardCompatibility(openApi30Feature, openApi31Feature)
        val newToOld = testBackwardCompatibility(openApi31Feature, openApi30Feature)

        assertThat(Result.fromResults(oldToNew.results)).isInstanceOf(Result.Success::class.java)
        assertThat(Result.fromResults(newToOld.results)).isInstanceOf(Result.Success::class.java)
    }
}
