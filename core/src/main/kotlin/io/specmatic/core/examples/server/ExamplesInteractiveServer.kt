package io.specmatic.core.examples.server

import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.specmatic.conversions.ExampleFromFile
import io.specmatic.core.*
import io.specmatic.core.examples.server.ExamplesView.Companion.groupEndpoints
import io.specmatic.core.examples.server.ExamplesView.Companion.toTableRows
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.route.modules.HealthCheckModule.Companion.configureHealthCheckModule
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.utilities.uniqueNameForApiOperation
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.stub.HttpStubData
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.util.*

class ExamplesInteractiveServer(
    private val serverHost: String,
    private val serverPort: Int,
    private val inputContractFile: File? = null,
    private val filterName: String,
    private val filterNotName: String
) : Closeable {
    private var contractFileFromRequest: File? = null

    private fun getContractFile(): File {
        if(inputContractFile != null && inputContractFile.exists()) return inputContractFile
        if(contractFileFromRequest != null && contractFileFromRequest!!.exists()) return contractFileFromRequest!!
        throw ContractException("Invalid contract file provided to the examples interactive server")
    }

    private val environment = applicationEngineEnvironment {
        module {
            install(CORS) {
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
                allowHeader(HttpHeaders.AccessControlAllowOrigin)
                allowHeader(HttpHeaders.ContentType)
                anyHost()
            }

            install(ContentNegotiation) {
                jackson {}
            }

            configureHealthCheckModule()
            routing {
                staticResources("/_specmatic/assets", "/templates/examples/assets")

                post("/_specmatic/examples") {
                    val request = call.receive<ExamplePageRequest>()
                    contractFileFromRequest = File(request.contractFile)
                    val contractFile = getContractFileOrBadRequest(call) ?: return@post
                    try {
                        respondWithExamplePageHtmlContent(contractFile, call)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, "An unexpected error occurred: ${e.message}")
                    }
                }

                get("/_specmatic/examples") {
                    respondWithExamplePageHtmlContent(getContractFile(), call)
                }

                post("/_specmatic/examples/generate") {
                    val contractFile = getContractFile()
                    try {
                        val request = call.receive<List<GenerateExampleRequest>>()
                        val generatedExamples = request.map {
                            generate(
                                contractFile,
                                it.method,
                                it.path,
                                it.responseStatusCode,
                                it.contentType
                            )
                        }
                        call.respond(HttpStatusCode.OK, GenerateExampleResponse(generatedExamples))
                    } catch(e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, "An unexpected error occurred: ${e.message}")
                    }
                }

                post("/_specmatic/examples/validate") {
                    val request = call.receive<ValidateExampleRequest>()
                    try {
                        val contractFile = getContractFile()
                        val validationResults = request.exampleFiles.map {
                            try {
                                validate(contractFile, File(it))
                                ValidateExampleResponse(it)
                            } catch(e: FileNotFoundException){
                                ValidateExampleResponse(it, e.message ?: "File not found")
                            } catch(e: NoMatchingScenario) {
                                ValidateExampleResponse(it, e.msg ?: "Something went wrong")
                            } catch(e: Exception) {
                                ValidateExampleResponse(it, e.message ?: "An unexpected error occurred")
                            }
                        }
                        call.respond(HttpStatusCode.OK, validationResults)
                    } catch(e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An unexpected error occurred: ${e.message}"))
                    }
                }

                post("/_specmatic/v2/examples/validate") {
                    val request = call.receive<ValidateExampleRequest>()
                    try {
                        val contractFile = getContractFile()
                        val validationResults = request.exampleFiles.map {
                            try {
                                validate(contractFile, File(it))
                                ValidateExampleResponseV2(
                                    ValidateExampleVerdict.SUCCESS,
                                    "The provided example is valid",
                                    it
                                )
                            } catch(e: NoMatchingScenario) {
                                ValidateExampleResponseV2(
                                    ValidateExampleVerdict.FAILURE,
                                    e.msg ?: "Something went wrong",
                                    it
                                )
                            } catch(e: Exception) {
                                ValidateExampleResponseV2(
                                    ValidateExampleVerdict.FAILURE,
                                    e.message ?: "An unexpected error occurred",
                                    it
                                )
                            }
                        }
                        call.respond(HttpStatusCode.OK, validationResults)
                    } catch(e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "An unexpected error occurred: ${e.message}"))
                    }
                }

                get("/_specmatic/examples/content") {
                    val fileName = call.request.queryParameters["fileName"]
                    if(fileName == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request. Missing required query param named 'fileName'"))
                        return@get
                    }
                    val file = File(fileName)
                    if(file.exists().not() || file.extension != "json") {
                        val message = if(file.extension == "json") "The provided example file ${file.name} does not exist"
                        else "The provided example file ${file.name} is not a valid example file"
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to message))
                        return@get
                    }
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("content" to File(fileName).readText())
                    )
                }
            }
        }
        connector {
            this.host = serverHost
            this.port = serverPort
        }
    }

    private val server: ApplicationEngine = embeddedServer(Netty, environment, configure = {
        this.requestQueueLimit = 1000
        this.callGroupSize = 5
        this.connectionGroupSize = 20
        this.workerGroupSize = 20
    })

    init {
        server.start()
    }

    override fun close() {
        server.stop(0, 0)
    }

    private suspend fun getContractFileOrBadRequest(call: ApplicationCall): File? {
        return try {
            getContractFile()
        } catch(e: ContractException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            return null
        }
    }

    private suspend fun respondWithExamplePageHtmlContent(contractFile: File, call: ApplicationCall) {
        try {
            val html = getExamplePageHtmlContent(contractFile)
            call.respondText(html, contentType = ContentType.Text.Html)
        } catch (e: Exception) {
            println(e)
            call.respond(HttpStatusCode.InternalServerError, "An unexpected error occurred: ${e.message}")
        }
    }

    private fun getExamplePageHtmlContent(contractFile: File): String {
        val feature = ScenarioFilter(filterName, filterNotName).filter(parseContractFileToFeature(contractFile))

        val endpoints = ExamplesView.getEndpoints(feature, getExamplesDirPath(contractFile))
        val tableRows = endpoints.groupEndpoints().toTableRows()

        return HtmlTemplateConfiguration.process(
            templateName = "examples/index.html",
            variables = mapOf(
                "tableRows" to tableRows,
                "contractFile" to contractFile.name,
                "contractFilePath" to contractFile.absolutePath,
                "hasExamples" to tableRows.any {it.example != null}
            )
        )
    }

    class ScenarioFilter(private val filterName: String, private val filterNotName: String) {
        private val filterNameTokens = if(filterName.isNotBlank()) {
            filterName.trim().split(",").map { it.trim() }
        } else emptyList()

        private val filterNotNameTokens = if(filterNotName.isNotBlank()) {
            filterNotName.trim().split(",").map { it.trim() }
        } else emptyList()

        fun filter(feature: Feature): Feature {
            val scenarios = feature.scenarios.filter { scenario ->
                if(filterNameTokens.isNotEmpty()) {
                    filterNameTokens.any { name -> scenario.testDescription().contains(name) }
                } else true
            }.filter { scenario ->
                if(filterNotNameTokens.isNotEmpty()) {
                    filterNotNameTokens.none { name -> scenario.testDescription().contains(name) }
                } else true
            }

            return feature.copy(scenarios = scenarios)
        }
    }

    companion object {
        fun generate(contractFile: File, scenarioFilter: ScenarioFilter, extensive: Boolean, overwriteByDefault: Boolean): List<String> {
            try {
                val feature: Feature = parseContractFileToFeature(contractFile).let { feature ->
                    val filteredScenarios = if (extensive == false) {
                        feature.scenarios.filter {
                            it.status.toString().startsWith("2")
                        }
                    } else {
                        feature.scenarios
                    }

                    scenarioFilter.filter(feature.copy(scenarios = filteredScenarios.map {
                        it.copy(examples = emptyList())
                    })).copy(stubsFromExamples = emptyMap())
                }

                val examplesDir =
                    getExamplesDirPath(contractFile)

                if(examplesDir.exists() && overwriteByDefault == false) {
                    val response: String = Scanner(System.`in`).use { scanner ->
                        print("Do you want to continue? (y/n): ")
                        scanner.nextLine().trim().lowercase()
                    }

                    if(response != "y") {
                        println()
                        println("You chose $response, terminating example generation.")
                        println()
                        return emptyList()
                    }

                    examplesDir.deleteRecursively()
                }

                examplesDir.mkdirs()

                if (feature.scenarios.isEmpty()) {
                    logger.log("All examples were filtered out by the filter expression")
                    return emptyList()
                }

                return feature.scenarios.map { scenario ->
                    println("Generating for ${scenario.testDescription()}")
                    val generatedScenario = scenario.generateTestScenarios(DefaultStrategies).first().value

                    val request = generatedScenario.httpRequestPattern.generate(generatedScenario.resolver)
                    val response = generatedScenario.httpResponsePattern.generateResponse(generatedScenario.resolver).cleanup()

                    val scenarioStub = ScenarioStub(request, response)

                    val stubJSON = scenarioStub.toJSON()
                    val uniqueNameForApiOperation =
                        uniqueNameForApiOperation(scenarioStub.request, "", scenarioStub.response.status)

                    val file = examplesDir.resolve("${uniqueNameForApiOperation}.json")
                    println("Writing to file: ${file.relativeTo(contractFile.canonicalFile.parentFile).path}")
                    file.writeText(stubJSON.toStringLiteral())

                    file.path
                }
            } catch (e: StackOverflowError) {
                logger.log("Got a stack overflow error. You probably have a recursive data structure definition in the contract.")
                throw e
            }
        }

        fun generate(
            contractFile: File,
            method: String,
            path: String,
            responseStatusCode: Int,
            contentType: String? = null
        ): String? {
            val feature = parseContractFileToFeature(contractFile)
            val scenario = feature.scenarios.firstOrNull {
                it.method == method && it.status == responseStatusCode && it.path == path
                        && (contentType == null || it.httpRequestPattern.headersPattern.contentType == contentType)
            }
            if(scenario == null) return null

            val examplesDir = getExamplesDirPath(contractFile)
            val existingExampleFile = getExistingExampleFile(scenario, examplesDir)
            if(existingExampleFile != null) return existingExampleFile.absolutePath
            else examplesDir.mkdirs()

            val request = scenario.generateHttpRequest()
            val response = feature.lookupResponse(scenario).cleanup()
            val scenarioStub = ScenarioStub(request, response)

            val stubJSON = scenarioStub.toJSON()
            val uniqueNameForApiOperation =
                uniqueNameForApiOperation(scenarioStub.request, "", scenarioStub.response.status)

            val file = examplesDir.resolve("${uniqueNameForApiOperation}.json")
            println("Writing to file: ${file.relativeTo(contractFile.canonicalFile.parentFile).path}")
            file.writeText(stubJSON.toStringLiteral())
            return file.absolutePath
        }

        fun validate(contractFile: File): Result {
            val feature = parseContractFileToFeature(contractFile).also {
                validateInlineExamples(it)
            }

            val examplesDir = contractFile.absoluteFile.parentFile.resolve(contractFile.nameWithoutExtension + "_examples")

            if(!examplesDir.isDirectory)
                return Result.Failure("$examplesDir does not exist, did not find any files to validate")


            logger.log("Validating examples in ${examplesDir.path}")

            val results = examplesDir.walkTopDown().map { file: File ->
                if(file.isDirectory)
                    return@map null

                logger.log("Validating ${file.path}")

                val scenarioStub = ScenarioStub.readFromFile(file)

                val result: Pair<Pair<Result.Success, List<HttpStubData>>?, NoMatchingScenario?> =
                    HttpStub.setExpectation(scenarioStub, feature, InteractiveExamplesMismatchMessages)
                val validationResult = result.first
                val noMatchingScenario = result.second

                if (validationResult != null) {
                    Result.Success()
                } else {
                    val failures = noMatchingScenario?.results?.withoutFluff()?.results ?: emptyList()

                    val failureResults = Results(failures).withoutFluff()

                    failureResults.toResultIfAny()
                }
            }.filterNotNull().toList()

            return Result.fromResults(results)
        }

        fun validate(contractFile: File, exampleFile: File): List<HttpStubData> {
            val feature = parseContractFileToFeature(contractFile).also {
                validateInlineExamples(it)
            }

            val scenarioStub = ScenarioStub.readFromFile(exampleFile)

            val result: Pair<Pair<Result.Success, List<HttpStubData>>?, NoMatchingScenario?> =
                HttpStub.setExpectation(scenarioStub, feature, InteractiveExamplesMismatchMessages)
            val validationResult = result.first
            val noMatchingScenario = result.second

            if(validationResult == null) {
                val failures =  noMatchingScenario?.results?.withoutFluff()?.results ?: emptyList()

                val failureResults = Results(failures).withoutFluff()
                throw NoMatchingScenario(
                    failureResults,
                    cachedMessage = failureResults.report(scenarioStub.request),
                    msg = failureResults.report(scenarioStub.request)
                )
            }

            return validationResult.second
        }

        private fun validateInlineExamples(it: Feature) {
            if (Flags.getBooleanValue("VALIDATE_INLINE_EXAMPLES"))
                try {
                    it.validateExamplesOrException()
                } catch (e: Exception) {
                    logger.log(e)
                }
        }

        private fun HttpResponse.cleanup(): HttpResponse {
            return this.copy(headers = this.headers.minus(SPECMATIC_RESULT_HEADER))
        }

        private fun File.hasExistingMatchingExample(scenario: Scenario): Boolean {
            if (this.exists().not() || this.isDirectory.not()) throw IllegalArgumentException("Not a directory: $this")

            val examples = this.listFiles()?.mapNotNull { file ->
                ExampleFromFile(file)
            } ?: emptyList()

            return examples.any {
                val response = it.response ?: return@any false
                scenario.matchesMock(it.request, response).isSuccess()
            }
        }

        fun getExistingExampleFile(scenario: Scenario, examplesDir: File): File? {
            val exampleFile = examplesDir.listFiles()?.firstOrNull {
                val example = ExampleFromFile(it)
                val response = example.response ?: return@firstOrNull false
                scenario.matchesMock(example.request, response).isSuccess()
            }
            return exampleFile
        }

        private fun getExamplesDirPath(contractFile: File): File {
            return contractFile.canonicalFile
                .parentFile
                .resolve("""${contractFile.nameWithoutExtension}$EXAMPLES_DIR_SUFFIX""")
        }
    }
}

object InteractiveExamplesMismatchMessages : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Specification expected $expected but example contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} $keyName in the example is not in the specification"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} $keyName in the specification is missing from the example"
    }
}

data class ExamplePageRequest(
    val contractFile: String
)

data class ValidateExampleRequest(
    val exampleFiles: List<String>
)

data class ValidateExampleResponse(
    val absPath: String,
    val error: String? = null
)

enum class ValidateExampleVerdict {
    SUCCESS,
    FAILURE
}
data class ValidateExampleResponseV2(
    val verdict: ValidateExampleVerdict,
    val message: String,
    val exampleFilePath: String
)

data class GenerateExampleRequest(
    val method: String,
    val path: String,
    val responseStatusCode: Int,
    val contentType: String? = null
)

data class GenerateExampleResponse(
    val generatedExamples: List<String?>
)