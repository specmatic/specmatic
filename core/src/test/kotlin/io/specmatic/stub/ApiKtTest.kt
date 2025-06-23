package io.specmatic.stub

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.*
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.core.value.*
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.mock.ScenarioStub
import io.specmatic.trimmedLinesList
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile

internal class ApiKtTest {
    @Test
    fun `given an expectation json with a key and one without, the request with the key should always match`() {
        val behaviour = parseGherkinStringToFeature("""
Feature: Math API

    Scenario: Square of a number
        When POST /square
        And request-body
            | number       | (number) |
            | description? | (number in string) |
        Then status 200
        And response-body (number)
""".trim())

        val request1 = HttpRequest("POST", "/square", emptyMap(), parsedValue("""{"number": 10}"""))
        val request2 = HttpRequest("POST", "/square", emptyMap(), parsedValue("""{"number": 10, "description": "10"}"""))

        val mock1 = ScenarioStub(request1, HttpResponse.ok(1))
        val mock2 = ScenarioStub(request2, HttpResponse.ok(2))
        val contractInfo = listOf(Pair(behaviour, listOf(mock1, mock2)))
        val response = stubResponse(request2, contractInfo)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isEqualTo(NumberValue(2))
    }

    @Test
    fun `given an expectation with optional header, a request without the header should match`() {
        val behaviour = parseGherkinStringToFeature("""
Feature: Math API

    Scenario: Square of a number
        When POST /square
        And request-body (number)
        And request-header X-Optional-Header? (string)
        Then status 200
        And response-body (number)
""".trim())

        val request = HttpRequest("POST", "/square", emptyMap(), NumberValue(10))
        val response = fakeResponse(request, behaviour)

        println(response.toLogString())
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `given an expectation with optional header, a request with the header should match`() {
        val behaviour = parseGherkinStringToFeature("""
Feature: Math API

    Scenario: Square of a number
        When POST /square
        And request-body (number)
        And request-header X-Optional-Header? (string)
        Then status 200
        And response-body (number)
""".trim())

        val request = HttpRequest("POST", "/square", mapOf("X-Optional-Header" to "some value"), NumberValue(10))
        val response = fakeResponse(request, behaviour)

        println(response.toLogString())
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `should be able to setup a mock with an optional header`() {
        val behaviour = parseGherkinStringToFeature("""
Feature: Math API

    Scenario: Square of a number
        When POST /square
        And request-body (number)
        And request-header X-Optional-Header? (string)
        Then status 200
        And response-body (number)
""".trim())

        val request = HttpRequest("POST", "/square", mapOf("X-Optional-Header" to "some value"), NumberValue(10))
        val mock = ScenarioStub(request, HttpResponse.ok(10))

        val response = stubResponse(request, listOf(Pair(behaviour, listOf(mock))))

        println(response.toLogString())
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `should be able to setup a mock without an optional header`() {
        val behaviour = parseGherkinStringToFeature("""
Feature: Math API

    Scenario: Square of a number
        When POST /square
        And request-body (number)
        And request-header X-Optional-Header? (string)
        Then status 200
        And response-body (number)
""".trim())

        val request = HttpRequest("POST", "/square", emptyMap(), NumberValue(10))
        val mock = ScenarioStub(request, HttpResponse.ok(10))

        val response = stubResponse(request, listOf(Pair(behaviour, listOf(mock))))

        println(response.toLogString())
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `given a mock with fewer headers before a mock with more params, the mock with fewer should not eat up all requests with its subset of headers`() {
        val behaviour = parseGherkinStringToFeature("""
Feature: Math API

    Scenario: Square of a number
        When POST /square
        And request-body (number)
        And request-header X-Required (string)
        And request-header X-Optional? (string)
        Then status 200
        And response-body (number)
""".trim())

        val request1 = HttpRequest("POST", "/square", mapOf("X-Required" to "some value"), parsedValue("""10"""))
        val request2 = HttpRequest("POST", "/square", mapOf("X-Required" to "some value", "X-Optional" to "some other value"), parsedValue("""10"""))

        val mock1 = ScenarioStub(request1, HttpResponse.ok(1))
        val mock2 = ScenarioStub(request2, HttpResponse.ok(2))
        val contractInfo = listOf(Pair(behaviour, listOf(mock1, mock2)))
        val response = stubResponse(request2, contractInfo)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isEqualTo(NumberValue(2))
    }

    @Test
    fun `given a mock with fewer query params before a mock with more params, the mock with fewer should not eat up all requests with its subset of headers`() {
        val behaviour = parseGherkinStringToFeature("""
Feature: Math API

    Scenario: Some number
        When GET /number?param1=(string)&param2=(string)
        Then status 200
        And response-body (number)
""".trim())

        val request1 = HttpRequest("GET", "/number", queryParametersMap = mapOf("param1" to "some value"))
        val request2 = HttpRequest("GET", "/number", queryParametersMap = mapOf("param1" to "some value", "param2" to "some other value"))

        val mock1 = ScenarioStub(request1, HttpResponse.ok(1))
        val mock2 = ScenarioStub(request2, HttpResponse.ok(2))
        val contractInfo = listOf(Pair(behaviour, listOf(mock1, mock2)))
        val response = stubResponse(request2, contractInfo)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isEqualTo(NumberValue(2))
    }

    @Test
    fun `stubbing out a contract pattern in json by specifying a sub pattern in stub data`() {
        val behaviour = parseGherkinStringToFeature("""
Feature: Math API

    Scenario: Square of a number
        When POST /square
        And request-body
          | number      | (number?)  |
        Then status 200
        And response-body (number)
""".trim())

        val mockRequest1 = HttpRequest("POST", "/square", body = JSONObjectValue(mapOf("number" to StringValue("(number)"))))
        val mockRequest2 = HttpRequest("POST", "/square", body = JSONObjectValue(mapOf("number" to StringValue("(null)"))))

        val mock1 = ScenarioStub(mockRequest1, HttpResponse.ok(NumberValue(1)))
        val mock2 = ScenarioStub(mockRequest2, HttpResponse.ok(NumberValue(2)))
        val contractInfo = listOf(Pair(behaviour, listOf(mock1, mock2)))

        val actualRequest1 = HttpRequest("POST", "/square", body = JSONObjectValue(mapOf("number" to NumberValue(10))))
        stubResponse(actualRequest1, contractInfo).let { response ->
            println(response)
            assertThat(response.status).isEqualTo(200)
            assertThat(response.body).isEqualTo(NumberValue(1))
        }

        val actualRequest2 = HttpRequest("POST", "/square", body = JSONObjectValue(mapOf("number" to NullValue)))
        stubResponse(actualRequest2, contractInfo).let { response ->
            println(response)
            assertThat(response.status).isEqualTo(200)
            assertThat(response.body).isEqualTo(NumberValue(2))
        }
    }

    @Test
    fun `stubbing out a contract pattern in request body by specifying a sub pattern in stub data`() {
        val behaviour = parseGherkinStringToFeature("""
Feature: Math API

    Scenario: Square of a number
        When POST /square
        And request-body (number?)
        Then status 200
        And response-body (number)
""".trim())

        val mockRequest1 = HttpRequest("POST", "/square", body = StringValue("(number)"))
        val mockRequest2 = HttpRequest("POST", "/square", body = StringValue("(empty)"))

        val mock1 = ScenarioStub(mockRequest1, HttpResponse.ok(1))
        val mock2 = ScenarioStub(mockRequest2, HttpResponse.ok(2))
        val contractInfo = listOf(Pair(behaviour, listOf(mock1, mock2)))

        val actualRequest1 = HttpRequest("POST", "/square", body = NumberValue(10))
        stubResponse(actualRequest1, contractInfo).let { response ->
            println(response)
            assertThat(response.status).isEqualTo(200)
            assertThat(response.body).isEqualTo(NumberValue(1))
        }

        val actualRequest2 = HttpRequest("POST", "/square", body = EmptyString)
        stubResponse(actualRequest2, contractInfo).let { response ->
            println(response)
            assertThat(response.status).isEqualTo(200)
            assertThat(response.body).isEqualTo(NumberValue(2))
        }
    }

    @Test
    fun `should load a stub with a form content part and match such a request`() {
        val behaviour = parseGherkinStringToFeature("""
Feature: Math API

    Scenario: Square of a number
        When POST /square
        And request-part number (number)
        Then status 200
        And response-body (number)
""".trim())

        val request = HttpRequest("POST", "/square", multiPartFormData = listOf(MultiPartContentValue("number", NumberValue(10))))
        val mock = ScenarioStub(request, HttpResponse.ok(10))

        val response = stubResponse(request, listOf(Pair(behaviour, listOf(mock))))

        println(response.toLogString())
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `should match multipart content part`() {
        val behaviour = parseGherkinStringToFeature("""
Feature: Math API

    Scenario: Square of a number
        When POST /square
        And request-part number (number)
        Then status 200
        And response-body (number)
""".trim())

        val request = HttpRequest("POST", "/square", multiPartFormData = listOf(MultiPartContentValue("number", NumberValue(10))))

        val response = stubResponse(request, listOf(Pair(behaviour, emptyList())))

        println(response.toLogString())
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `should load a stub with a file part and match such a request`() {
        val behaviour = parseGherkinStringToFeature("""
            Feature: Math API
            
                Scenario: Square of a number
                    When POST /square
                    And request-part number @number.txt text/plain
                    Then status 200
                    And response-body (number)
        """.trim())

        val request = HttpRequest("POST", "/square", multiPartFormData = listOf(MultiPartFileValue("number", "number.txt", "text/plain", null)))
        val mock = ScenarioStub(request, HttpResponse.ok(10))

        val response = stubResponse(request, listOf(Pair(behaviour, listOf(mock))))

        println(response.toLogString())
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `should match multipart file part`() {
        val feature = parseGherkinStringToFeature("""
            Feature: Math API
            
                Scenario: Square of a number
                    When POST /square
                    And request-part number @number.txt text/plain
                    Then status 200
                    And response-body (number)
        """.trim())

        val request = HttpRequest("POST", "/square", multiPartFormData = listOf(MultiPartFileValue("number", "number.txt", "text/plain", null)))

        val response = stubResponse(request, listOf(Pair(feature, emptyList())))

        println(response.toLogString())
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isInstanceOf(NumberValue::class.java)
    }

    @Test
    fun `should get a stub out of a spec file and a matching stub file`() {
        val feature = parseGherkinStringToFeature("""
Feature: Math API
    Scenario: Square of a number
        When POST /square
        And request-body (number)
        Then status 200
        And response-body (number)
""".trim())

        val stubInfo = loadContractStubs(listOf(Pair("math.$CONTRACT_EXTENSION", feature)), listOf(Pair("sample.json", ScenarioStub(
            HttpRequest(method = "POST", path = "/square", body = StringValue("10")),
            HttpResponse(status = 200, body = "20")
        ))))
        assertThat(stubInfo.single().first).isEqualTo(feature)

        val stub = stubInfo.single().second.single()
        val expectedRequest = HttpRequest("POST", path = "/square", body = StringValue("10"))
        val expectedResponse = HttpResponse(status = 200, body = StringValue("20"))

        assertThat(stub.request).isEqualTo(expectedRequest)
        assertThat(stub.response).isEqualTo(expectedResponse)
    }

    @Test
    fun `spec file should reject a stub file that does not match the spec file`() {
        val feature = parseGherkinStringToFeature("""
Feature: Math API
    Scenario: Square of a number
        When POST /square
        And request-body (number)
        Then status 200
        And response-body (number)
""".trim())

        val (stdout, stubInfo) =  captureStandardOutput {
            loadContractStubs(
                listOf(Pair("math.$CONTRACT_EXTENSION", feature)),
                listOf(
                    Pair(
                        "sample.json", ScenarioStub(
                            HttpRequest(method = "POST", path = "/square", body = StringValue("10")),
                            HttpResponse(status = 200, body = "not a number")
                        )
                    )
                ),
                logIgnoredFiles = true
            )
        }

        assertThat(stubInfo.single().first).isEqualTo(feature)
        assertThat(stubInfo.single().second).isEmpty()
        assertThat(stdout).contains("sample.json didn't match math.$CONTRACT_EXTENSION")
        assertThat(stdout).contains("""In scenario "Square of a number"""")
        assertThat(stdout).contains("API: POST /square -> 200")
        assertThat(stdout).contains(">> RESPONSE.BODY")
        assertThat(stdout).contains(ContractAndStubMismatchMessages.mismatchMessage("number", """"not a number""""))
    }

    @Test
    fun `should get a stub out of two spec files and a single stub file that matches only one of them`() {
        val squareFeature = parseGherkinStringToFeature("""
Feature: Square API
    Scenario: Square of a number
        When POST /square
        And request-body (number)
        Then status 200
        And response-body (number)
""".trim())

        val cubeFeature = parseGherkinStringToFeature("""
Feature: Cube API
    Scenario: Cube of a number
        When POST /cube
        And request-body (number)
        Then status 200
        And response-body (number)
""".trim())

        val features = listOf(Pair("square.$CONTRACT_EXTENSION", squareFeature), Pair("cube.$CONTRACT_EXTENSION", cubeFeature))
        val rawStubInfo = listOf(Pair("sample.json", ScenarioStub(
            HttpRequest(method = "POST", path = "/square", body = StringValue("10")),
            HttpResponse(status = 200, body = "20")
        )))
        val stubInfo = loadContractStubs(features, rawStubInfo)
        assertThat(stubInfo.map { it.first }).contains(squareFeature)
        assertThat(stubInfo.map { it.first }).contains(cubeFeature)
        assertThat(stubInfo).hasSize(2)

        val squareStub = stubInfo.first { it.first == squareFeature }

        val expectedRequest = HttpRequest("POST", path = "/square", body = StringValue("10"))
        val expectedResponse = HttpResponse(status = 200, body = StringValue("20"))

        assertThat(squareStub.second.single().request).isEqualTo(expectedRequest)
        assertThat(squareStub.second.single().response).isEqualTo(expectedResponse)
    }

    @Test
    fun `should not be able to load a stub json with unexpected keys in the request`() {
        val feature = parseGherkinStringToFeature("""
Feature: Math API
    Scenario: Square of a number
        When POST /square
        And request-body
        | number | (number) |
        Then status 200
        And response-body (number)
""".trim())

        val (output, stubInfo) = captureStandardOutput { loadContractStubs(
            listOf(Pair("math.$CONTRACT_EXTENSION", feature)),
            listOf(
                Pair(
                    "sample.json", ScenarioStub(
                        HttpRequest(
                            method = "POST",
                            path = "/square",
                            body = StringValue("""{"number": 10, "unexpected": "data"}""")
                        ),
                        HttpResponse(status = 200, body = "20")
                    )
                )
            ),
            logIgnoredFiles = true
        ) }
        assertThat(stubInfo.single().first).isEqualTo(feature)
        assertThat(stubInfo.single().second).isEmpty()
        assertThat(output).contains("sample.json didn't match math.spec")
        assertThat(output).contains("""In scenario "Square of a number"""")
        assertThat(output).contains("API: POST /square -> 200")
        assertThat(output).contains(">> REQUEST.BODY.unexpected")
        assertThat(output).contains(ContractAndStubMismatchMessages.unexpectedKey("key", "unexpected"))
    }

    @Test
    fun `should not be able to load a stub json with unexpected keys in the response`() {
        val feature = parseGherkinStringToFeature("""
Feature: Math API
    Scenario: Square of a number
        When POST /square
        And request-body (number)
        Then status 200
        And response-body
        | number | (number) |
""".trim())

        val (output, stubInfo) = captureStandardOutput {
            loadContractStubs(
                listOf(Pair("math.$CONTRACT_EXTENSION", feature)),
                listOf(
                    Pair(
                        "sample.json", ScenarioStub(
                            HttpRequest(method = "POST", path = "/square", body = StringValue("""10""")),
                            HttpResponse(status = 200, body = """{"number": 10, "unexpected": "data"}""")
                        )
                    )
                ),
                logIgnoredFiles = true
            )
        }
        assertThat(stubInfo.single().first).isEqualTo(feature)
        assertThat(stubInfo.single().second).isEmpty()

        assertThat(output).contains("sample.json didn't match math.spec")
        assertThat(output).contains("""In scenario "Square of a number"""")
        assertThat(output).contains("API: POST /square -> 200")
        assertThat(output).contains(">> RESPONSE.BODY.unexpected")
        assertThat(output).contains(ContractAndStubMismatchMessages.unexpectedKey("key", "unexpected"))
    }

    private fun fakeResponse(request: HttpRequest, behaviour: Feature): HttpResponse {
        return stubResponse(request, listOf(Pair(behaviour, emptyList())))
    }

    private fun stubResponse(request: HttpRequest, contractInfo: List<Pair<Feature, List<ScenarioStub>>>): HttpResponse {
        val expectations = contractInfoToExpectations(contractInfo)
        return stubResponse(request, contractInfo, expectations)
    }

    private fun makeStubMatchResults(results: Results): StubMatchResults {
        val exceptionReport = StubMatchExceptionReport(HttpRequest("POST", "/test"), NoMatchingScenario(results))
        return StubMatchResults(null, StubMatchErrorReport(exceptionReport, "/path/to/contract"))
    }

    @Test
    fun `should eliminate fluffy error messages and display only the deep error`() {
        val results1 = Results(listOf(Result.Failure("deep", null, "")))
        val results2 = Results(listOf(Result.Failure("fluffLevel 2", null, "", FailureReason.SOAPActionMismatch)))
        val results3 = Results(listOf(Result.Failure("fluffLevel 1", null, "", FailureReason.StatusMismatch)))

        val stubMatchResults = listOf(
            makeStubMatchResults(results1),
            makeStubMatchResults(results2),
            makeStubMatchResults(results3)
        )

        val errorMessage = stubMatchErrorMessage(stubMatchResults, "stubfile.json")

        assertThat(errorMessage.trimmedLinesList()).isEqualTo("""
            stubfile.json didn't match /path/to/contract
              No match was found.
              
              deep
        """.trimIndent().trimmedLinesList())

        println(errorMessage)
    }

    @Test
    fun `should show level 1 fluffy errors and eliminate level 2 fluffy errors if no deep errors are found`() {
        val results1 = Results(listOf(Result.Failure("failed", null, "", FailureReason.ContentTypeMismatch)))
        val results2 = Results(listOf(Result.Failure("fluffy", null, "", FailureReason.SOAPActionMismatch)))

        val errorMessage = stubMatchErrorMessage(listOf(makeStubMatchResults(results1), makeStubMatchResults(results2)), "stubfile.json")

        assertThat(errorMessage.trimmedLinesList()).isEqualTo("""
            stubfile.json didn't match /path/to/contract
              No match was found.
              
              failed
        """.trimIndent().trimmedLinesList())

        println(errorMessage)
    }

    @Test
    fun `should eliminate all level 2 fluffy error messages and display a standard error if all errors are level 2 fluffy`() {
        val results = Results(listOf(Result.Failure("failed", null, "", FailureReason.URLPathMisMatch)))
        val exceptionReport = StubMatchExceptionReport(HttpRequest("POST", "/test"), NoMatchingScenario(results))
        val stubMatchResults = StubMatchResults(null, StubMatchErrorReport(exceptionReport, "/path/to/contract"))
        val errorMessage = stubMatchErrorMessage(listOf(stubMatchResults), "stubfile.json", listOf("spec1.yaml"))

        assertThat(errorMessage).isEqualTo("""
            stubfile.json didn't match any of the contracts from spec1.yaml
              No matching REST stub or contract found for method POST and path /test
""".trimIndent())
        println(errorMessage)
    }

    @ParameterizedTest
    @CsvSource(
        "Filename, Should Create, Expected output",
        "missing_file.yaml, false, Skipping the file",
        "invalid_spec.yaml, true, Could not parse contract",
        "not_a_spec.txt, true, Could not parse contract",
        useHeadersInDisplayName = true
    )
    fun `loadIfOpenAPISpecification should handle invalid files gracefully by catching exceptions`(fileName: String, shouldCreate: Boolean, expectedOutput: String, @TempDir tempDir: File) {
        val file = tempDir.resolve("invalid.yaml")

        if(shouldCreate) {
            file.createNewFile()
        }

        val contractPathData = ContractPathData("", file.path)
        val (output, result) = captureStandardOutput {  loadIfOpenAPISpecification(contractPathData, SpecmaticConfig()) }
        assertThat(result).isNull()
        assertThat(output).contains(expectedOutput)
    }
}

fun <ReturnType> captureStandardOutput(trim: Boolean = true, fn: () -> ReturnType): Pair<String, ReturnType> {
    val originalOut = System.out

    val byteArrayOutputStream = ByteArrayOutputStream()
    val newOut = PrintStream(byteArrayOutputStream)
    System.setOut(newOut)

    val result = fn()

    System.out.flush()
    System.setOut(originalOut)
    val string = String(byteArrayOutputStream.toByteArray())
    val trimmed = if(trim) string.trim() else string
    return Pair(trimmed, result)
}

fun contractInfoToExpectations(contractInfo: List<Pair<Feature, List<ScenarioStub>>>): StubDataItems {
    return contractInfo.fold(StubDataItems()) { stubsAcc, (feature, mocks) ->
        val newStubs = mocks.fold(StubDataItems()) { stubs, mock ->
            val stubData = feature.matchingStub(mock)
            StubDataItems(stubs.http.plus(stubData))
        }

        StubDataItems(stubsAcc.http.plus(newStubs.http))
    }
}
