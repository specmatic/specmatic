package io.specmatic.core

import io.cucumber.gherkin.GherkinParser
import io.cucumber.messages.types.*
import io.cucumber.messages.types.Examples
import io.cucumber.messages.types.Source
import io.ktor.http.*
import io.specmatic.conversions.ExampleFromFile
import io.specmatic.conversions.IncludedSpecification
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.WSDLFile
import io.specmatic.conversions.WsdlSpecification
import io.specmatic.conversions.testDirectoryEnvironmentVariable
import io.specmatic.conversions.testDirectoryProperty
import io.specmatic.conversions.unwrapBackground
import io.specmatic.conversions.unwrapFeature
import io.specmatic.conversions.wsdlContentToFeature
import io.specmatic.core.discriminator.DiscriminatorBasedItem
import io.specmatic.core.discriminator.DiscriminatorMetadata
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.*
import io.specmatic.core.pattern.Examples.Companion.examplesFrom
import io.specmatic.core.utilities.*
import io.specmatic.core.value.*
import io.specmatic.core.Result.Success
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.NamedExampleMismatchMessages
import io.specmatic.stub.HttpStubData
import io.specmatic.test.*
import io.swagger.v3.oas.models.*
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.*
import io.swagger.v3.oas.models.parameters.*
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import java.io.File
import java.net.URI
import kotlin.jvm.optionals.getOrNull

fun parseContractFileToFeature(
    contractPath: String,
    hook: Hook = PassThroughHook(),
    sourceProvider: String? = null,
    sourceRepository: String? = null,
    sourceRepositoryBranch: String? = null,
    specificationPath: String? = null,
    securityConfiguration: SecurityConfiguration? = null,
    specmaticConfig: SpecmaticConfig = loadSpecmaticConfigOrDefault(getConfigFilePath()),
    overlayContent: String = "",
    strictMode: Boolean = false
): Feature {
    return parseContractFileToFeature(
        File(contractPath),
        hook,
        sourceProvider,
        sourceRepository,
        sourceRepositoryBranch,
        specificationPath,
        securityConfiguration,
        specmaticConfig,
        overlayContent,
        strictMode
    )
}

fun checkExists(file: File) = file.also {
    if (!file.exists())
        throw ContractException("File ${file.path} does not exist (absolute path ${file.canonicalPath})")
}

fun parseContractFileWithNoMissingConfigWarning(contractFile: File): Feature {
    return parseContractFileToFeature(contractFile, specmaticConfig = SpecmaticConfig())
}

fun parseContractFileToFeature(
    file: File,
    hook: Hook = PassThroughHook(),
    sourceProvider: String? = null,
    sourceRepository: String? = null,
    sourceRepositoryBranch: String? = null,
    specificationPath: String? = null,
    securityConfiguration: SecurityConfiguration? = null,
    specmaticConfig: SpecmaticConfig = loadSpecmaticConfigOrDefault(getConfigFilePath()),
    overlayContent: String = "",
    strictMode: Boolean = false
): Feature {
    logger.debug("Parsing spec file ${file.path}, absolute path ${file.canonicalPath}")
    return when (file.extension) {
        in OPENAPI_FILE_EXTENSIONS -> OpenApiSpecification.fromYAML(
            hook.readContract(file.path),
            file.path,
            sourceProvider = sourceProvider,
            sourceRepository = sourceRepository,
            sourceRepositoryBranch = sourceRepositoryBranch,
            specificationPath = specificationPath,
            securityConfiguration = securityConfiguration,
            specmaticConfig = specmaticConfig,
            overlayContent = overlayContent,
            strictMode = strictMode
        ).toFeature()
        WSDL -> wsdlContentToFeature(checkExists(file).readText(), file.canonicalPath)
        in CONTRACT_EXTENSIONS -> parseGherkinStringToFeature(checkExists(file).readText().trim(), file.canonicalPath)
        else -> throw unsupportedFileExtensionContractException(file.path, file.extension)
    }
}

fun unsupportedFileExtensionContractException(
    path: String,
    extension: String
) =
    ContractException(
        "Current file $path has an unsupported extension $extension. Supported extensions are ${
            CONTRACT_EXTENSIONS.joinToString(
                ", "
            )
        }."
    )

fun parseGherkinStringToFeature(gherkinData: String, sourceFilePath: String = "", isWSDL: Boolean = false): Feature {
    val gherkinDocument = parseGherkinString(gherkinData, sourceFilePath)
    val (name, scenarios) = lex(gherkinDocument, sourceFilePath, isWSDL)
    return Feature(scenarios = scenarios, name = name, path = sourceFilePath)
}

data class Feature(
    val scenarios: List<Scenario> = emptyList(),
    private var serverState: Map<String, Value> = emptyMap(),
    val name: String,
    val testVariables: Map<String, String> = emptyMap(),
    val testBaseURLs: Map<String, String> = emptyMap(),
    val path: String = "",
    val sourceProvider:String? = null,
    val sourceRepository:String? = null,
    val sourceRepositoryBranch:String? = null,
    val specification:String? = null,
    val serviceType:String? = null,
    val specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
    val flagsBased: FlagsBased = strategiesFromFlags(specmaticConfig),
    val strictMode: Boolean = false,
    val exampleStore: ExampleStore = ExampleStore.empty()
): IFeature {
    val stubsFromExamples: Map<String, List<Pair<HttpRequest, HttpResponse>>>
        get() {
            return exampleStore.examples.groupBy(
                keySelector = { it.name },
                valueTransform = { it.example.request to it.example.response }
            )
        }

    fun enableGenerativeTesting(onlyPositive: Boolean = false): Feature {
        return this.copy(
            flagsBased = this.flagsBased.copy(
                generation = GenerativeTestsEnabled(onlyPositive),
                positivePrefix = POSITIVE_TEST_DESCRIPTION_PREFIX,
                negativePrefix = NEGATIVE_TEST_DESCRIPTION_PREFIX
            ),
            specmaticConfig = specmaticConfig.copyResiliencyTestsConfig(onlyPositive)
        )
    }

    fun enableSchemaExampleDefault(): Feature {
        return this.copy(flagsBased = this.flagsBased.copy(defaultExampleResolver = UseDefaultExample))
    }

    fun lookupResponse(httpRequest: HttpRequest): HttpResponse {
        try {
            val resultList = lookupScenario(httpRequest, scenarios)
            return matchingScenario(resultList)?.generateHttpResponse(serverState)
                ?: Results(resultList.map { it.second }.toMutableList()).withoutFluff()
                    .generateErrorHttpResponse(httpRequest)
        } finally {
            serverState = emptyMap()
        }
    }

    fun lookupResponse(scenario: Scenario): HttpResponse {
        try {
            return scenario.generateHttpResponse(serverState)
        } finally {
            serverState = emptyMap()
        }
    }

    fun loadInlineExamplesAsStub(): List<ReturnValue<HttpStubData>> {
        return this.stubsFromExamples.entries.flatMap { (exampleName, examples) ->
            examples.mapNotNull { (request, response) ->
                try {
                    val stubData: HttpStubData =
                        this.matchingStub(request, response, NamedExampleMismatchMessages(exampleName))

                    if (stubData.matchFailure) {
                        logger.newLine()
                        logger.log(stubData.response.body.toStringLiteral())
                        null
                    } else {
                        HasValue(stubData)
                    }
                } catch (e: Throwable) {
                    logger.newLine()

                    when (e) {
                        is ContractException -> {
                            logger.log(e)
                            null
                        }

                        is NoMatchingScenario -> {
                            logger.log(e, "[Example $exampleName]")
                            HasFailure("[Example $exampleName] ${e.message}")
                        }

                        else -> {
                            logger.log(e, "[Example $exampleName]")
                            throw e
                        }
                    }
                }
            }
        }
    }

    fun generateDiscriminatorBasedRequestResponseList(
        scenarioValue: HasValue<Scenario>,
        allowOnlyMandatoryKeysInJSONObject: Boolean = false
    ): List<DiscriminatorBasedRequestResponse> {
        val scenario = scenarioValue.value
        try {
            val requests = scenario.generateHttpRequestV2(
                allowOnlyMandatoryKeysInJSONObject = allowOnlyMandatoryKeysInJSONObject
            )
            val responses = scenario.generateHttpResponseV2(
                serverState,
                allowOnlyMandatoryKeysInJSONObject = allowOnlyMandatoryKeysInJSONObject
            )

            val discriminatorBasedRequestResponseList = if (requests.size > responses.size) {
                requests.map { (requestDiscriminator, request) ->
                    val (responseDiscriminator, response) = if (responses.containsDiscriminatorValueAs(
                            requestDiscriminator.discriminatorValue
                        )
                    )
                        responses.getDiscriminatorItemWith(requestDiscriminator.discriminatorValue)
                    else
                        responses.first()
                    DiscriminatorBasedRequestResponse(
                        request = request,
                        response = response,
                        requestDiscriminator = requestDiscriminator,
                        responseDiscriminator = responseDiscriminator,
                        scenarioValue = scenarioValue
                    )
                }
            } else {
                responses.map { (responseDiscriminator, response) ->
                    val (requestDiscriminator, request) = if (requests.containsDiscriminatorValueAs(
                            responseDiscriminator.discriminatorValue
                        )
                    )
                        requests.getDiscriminatorItemWith(responseDiscriminator.discriminatorValue)
                    else requests.first()
                    DiscriminatorBasedRequestResponse(
                        request = request,
                        response = response,
                        requestDiscriminator = requestDiscriminator,
                        responseDiscriminator = responseDiscriminator,
                        scenarioValue = scenarioValue
                    )
                }
            }

            return discriminatorBasedRequestResponseList
        } finally {
            serverState = emptyMap()
        }
    }

    fun stubResponse(
        httpRequest: HttpRequest,
        mismatchMessages: MismatchMessages = DefaultMismatchMessages
    ): Pair<ResponseBuilder?, Results> {
        try {
            val resultList = matchingScenarioToResultList(
                httpRequest = httpRequest,
                serverState = serverState,
                mismatchMessages = mismatchMessages,
                unexpectedKeyCheck = flagsBased.unexpectedKeyCheck ?: ValidateUnexpectedKeys
            ).let { resultList ->
                filterByExpectedResponseStatus(httpRequest.expectedResponseCode(), resultList)
            }

            return matchingScenario(resultList)?.let {
                Pair(ResponseBuilder(it, serverState), Results())
            }
                ?: Pair(
                    null,
                    Results(resultList.map {
                        it.second
                    }.toList())
                        .withoutFluff()
                )
        } finally {
            serverState = emptyMap()
        }
    }

    private fun filterByExpectedResponseStatus(
        expectedResponseCode: Int?,
        resultList: Sequence<Pair<Scenario, Result>>
    ): Sequence<Pair<Scenario, Result>> {
        return expectedResponseCode?.let {
            resultList.filter { result -> result.first.status == it }
        } ?: resultList
    }

    fun stubResponseMap(
        httpRequest: HttpRequest,
        mismatchMessages: MismatchMessages = DefaultMismatchMessages,
        unexpectedKeyCheck: UnexpectedKeyCheck
    ): Map<Int, Pair<ResponseBuilder?, Results>> {
        try {
            val resultList =
                matchingScenarioToResultList(httpRequest, serverState, mismatchMessages, unexpectedKeyCheck)
            val matchingScenarios = matchingScenarios(resultList)

            if (matchingScenarios.toList().isEmpty()) {
                val results = Results(
                    resultList.map { it.second }.toList()
                ).withoutFluff()
                return mapOf(
                    400 to Pair(
                        ResponseBuilder(null, serverState),
                        results
                    )
                )
            }

            return matchingScenarios.map { (status, scenario) ->
                status to Pair(ResponseBuilder(scenario, serverState), Results())
            }.toMap()

        } finally {
            serverState = emptyMap()
        }
    }

    fun calculatePath(httpRequest: HttpRequest, responseStatus: Int): Set<String> {
        val matchingScenario = scenarios.firstOrNull { scenario ->
            val resolver = scenario.resolver
            if (responseStatus == 400) {
                scenario.httpRequestPattern.matchesPathStructureMethodAndContentType(httpRequest, resolver) is Success
            } else {
                scenario.httpRequestPattern.matches(httpRequest, resolver) is Success
            }
        }

        return matchingScenario?.calculatePath(httpRequest) ?: emptySet()
    }

    private fun matchingScenarioToResultList(
        httpRequest: HttpRequest,
        serverState: Map<String, Value>,
        mismatchMessages: MismatchMessages,
        unexpectedKeyCheck: UnexpectedKeyCheck = ValidateUnexpectedKeys
    ): Sequence<Pair<Scenario, Result>> {
        val scenarioSequence = scenarios.asSequence()

        val matchingScenarios = scenarioSequence.zip(scenarioSequence.map {
            it.matchesStub(httpRequest, serverState, mismatchMessages, unexpectedKeyCheck)
        })

        return matchingScenarios
    }

    fun compatibilityLookup(
        httpRequest: HttpRequest,
        mismatchMessages: MismatchMessages = NewAndOldSpecificationRequestMismatches
    ): List<Pair<Scenario, Result>> {
        try {
            val resultList = lookupAllScenarios(httpRequest, scenarios, mismatchMessages, IgnoreUnexpectedKeys)

            val successes = lookupAllSuccessfulScenarios(resultList)
            if (successes.isNotEmpty())
                return successes

            val deepMatchingErrors = allDeeplyMatchingScenarios(resultList)

            return when {
                deepMatchingErrors.isNotEmpty() -> deepMatchingErrors
                scenarios.isEmpty() -> throw EmptyContract()
                else -> emptyList()
            }
        } finally {
            serverState = emptyMap()
        }
    }

    private fun lookupAllSuccessfulScenarios(resultList: List<Pair<Scenario, Result>>): List<Pair<Scenario, Result>> {
        return resultList.filter { (_, result) ->
            result is Success
        }
    }

    private fun allDeeplyMatchingScenarios(resultList: List<Pair<Scenario, Result>>): List<Pair<Scenario, Result>> {
        return resultList.filter {
            when (val result = it.second) {
                is Success -> true
                is Result.Failure -> !result.isFluffy()
            }
        }
    }

    private fun matchingScenario(resultList: Sequence<Pair<Scenario, Result>>): Scenario? {
        return resultList.find {
            it.second is Success
        }?.first
    }

    private fun matchingScenarios(resultList: Sequence<Pair<Scenario, Result>>): Sequence<Pair<Int, Scenario>> {
        return resultList.filter { it.second is Success }.map {
            Pair(it.first.status, it.first)
        }
    }

    private fun lookupScenario(
        httpRequest: HttpRequest,
        scenarios: List<Scenario>
    ): Sequence<Pair<Scenario, Result>> {
        val scenarioSequence = scenarios.asSequence()

        val localCopyOfServerState = serverState
        return scenarioSequence.zip(scenarioSequence.map {
            it.matches(httpRequest, localCopyOfServerState, DefaultMismatchMessages)
        })
    }

    private fun lookupAllScenarios(
        httpRequest: HttpRequest,
        scenarios: List<Scenario>,
        mismatchMessages: MismatchMessages = DefaultMismatchMessages,
        unexpectedKeyCheck: UnexpectedKeyCheck? = null
    ): List<Pair<Scenario, Result>> {
        val localCopyOfServerState = serverState
        return scenarios.zip(scenarios.map {
            it.matches(httpRequest, localCopyOfServerState, mismatchMessages, unexpectedKeyCheck)
        })
    }

    fun executeTests(
        testExecutor: TestExecutor,
        suggestions: List<Scenario> = emptyList(),
        testDescriptionFilter: List<String> = emptyList()
    ): Results {
        return generateContractTests(suggestions)
            .filter { contractTest ->
                testDescriptionFilter.isEmpty() ||
                        testDescriptionFilter.any { scenarioName ->
                            contractTest.testDescription().contains(scenarioName)
                        }
            }
            .fold(Results()) { results, contractTest ->
                val (result, _) = contractTest.runTest(testExecutor)
                Results(results = results.results.plus(result))
            }
    }

    fun setServerState(serverState: Map<String, Value>) {
        this.serverState = this.serverState.plus(serverState)
    }

    fun matches(request: HttpRequest, response: HttpResponse): Boolean {
        return scenarios.firstOrNull {
            it.matches(
                request,
                serverState
            ) is Success && it.matches(response) is Success
        } != null
    }

    fun matchResultSchemaFlagBased(
        discriminatorPatternName: String?,
        patternName: String, value: Value,
        mismatchMessages: MismatchMessages,
        breadCrumbIfDiscriminatorMismatch: String? = null
    ): Result {
        val updatedResolver = flagsBased.update(scenarios.last().resolver).copy(
            mismatchMessages = mismatchMessages,
            mockMode = true
        )

        val pattern = runCatching {
            getSchemaPattern(discriminatorPatternName, patternName, updatedResolver)
        }.getOrElse { e ->
            return if (e is ContractException) e.failure()
            else Result.Failure(e.message ?: "Invalid Pattern \"$discriminatorPatternName.$patternName\"")
        }

        return if (pattern is SubSchemaCompositePattern && !discriminatorPatternName.isNullOrEmpty()) {
            pattern.matchesValue(value, updatedResolver, patternName, breadCrumbIfDiscriminatorMismatch)
        } else pattern.matches(value, updatedResolver)
    }

    fun getAllDiscriminatorValuesIfExists(patternName: String): Set<String> {
        val resolver = flagsBased.update(scenarios.last().resolver)
        val pattern = runCatching {
            getSchemaPattern(patternName, "", resolver)
        }.getOrElse { return emptySet() }

        return when (pattern) {
            is SubSchemaCompositePattern -> pattern.discriminator?.values.orEmpty()
            else -> emptySet()
        }
    }

    fun generateSchemaFlagBased(discriminatorPatternName: String?, patternName: String): Value {
        val updatedResolver = flagsBased.update(scenarios.last().resolver)

        return when (val pattern = getSchemaPattern(discriminatorPatternName, patternName, updatedResolver)) {
            is SubSchemaCompositePattern -> pattern.generateValue(updatedResolver, patternName)
            else -> pattern.generate(updatedResolver)
        }
    }

    fun fixSchemaFlagBased(discriminatorPatternName: String?, patternName: String, value: Value): Value {
        val updatedResolver = flagsBased.update(scenarios.last().resolver).copy(mockMode = true)
        val pattern = getSchemaPattern(discriminatorPatternName, patternName, updatedResolver)

        if (pattern is AnyPattern && !discriminatorPatternName.isNullOrEmpty()) {
            return pattern.fixValue(
                value = value, resolver = updatedResolver, discriminatorValue = patternName,
                onValidDiscValue = { pattern.generateValue(updatedResolver, patternName) },
                onInvalidDiscValue = { f -> throw ContractException(f.toFailureReport()) }
            ) ?: throw ContractException("Couldn't fix pattern with discriminator value ${patternName.quote()}")
        }

        return pattern.fixValue(value, updatedResolver)
    }

    private fun getSchemaPattern(discriminatorPatternName: String?, patternName: String, resolver: Resolver): Pattern {
        if (!discriminatorPatternName.isNullOrEmpty()) {
            return when (val discriminatorPattern =
                resolver.getPattern(withPatternDelimiters(discriminatorPatternName))) {
                is AnyPattern -> discriminatorPattern
                else -> throw ContractException(
                    breadCrumb = discriminatorPatternName,
                    errorMessage = "Pattern ${discriminatorPatternName.quote()} is not an Discriminator Pattern"
                )
            }
        }

        return resolver.getPattern(withPatternDelimiters(patternName))
    }

    fun matchResultFlagBased(scenarioStub: ScenarioStub, mismatchMessages: MismatchMessages): Results {
        return matchResultFlagBased(
            scenarioStub.requestElsePartialRequest(),
            scenarioStub.response(),
            mismatchMessages,
            scenarioStub.isPartial()
        )
    }

    fun negativeScenariosFor(originalScenario: Scenario): Sequence<ReturnValue<Scenario>> {
        return negativeScenarioFor(originalScenario).newBasedOn(
            originalScenario.exampleRow ?: Row(),
            flagsBased
        ).filterNot {
            val scenario = it.value
            originalScenario.httpRequestPattern.matches(
                scenario.generateHttpRequest(flagsBased),
                scenario.resolver
            ).isSuccess()
        }
    }

    fun matchResultFlagBased(
        request: HttpRequest,
        response: HttpResponse,
        mismatchMessages: MismatchMessages,
        isPartial: Boolean
    ): Results {
        val results = scenarios.map {
            it.matches(request, response, mismatchMessages, flagsBased, isPartial)
        }

        if (results.any { it.isSuccess() })
            return Results(results).withoutFluff()

        val deepErrors: List<Result> = results.filterNot {
            it.isFluffy()
        }.ifEmpty {
            results.filter {
                it is Result.Failure && it.hasReason(FailureReason.URLPathParamMismatchButSameStructure)
            }
        }

        if (deepErrors.isNotEmpty())
            return Results(deepErrors).distinct()

        return Results(
            listOf(
                Result.Failure(
                    "No matching specification found for this example",
                    failureReason = FailureReason.IdentifierMismatch
                )
            )
        )
    }

    fun matchResult(request: HttpRequest, response: HttpResponse): Result {
        if (scenarios.isEmpty())
            return Result.Failure("No operations found")

        val matchResults = scenarios.map {
            it.matches(
                request,
                serverState
            ) to it.matches(response)
        }

        if (matchResults.any {
                it.first is Success && it.second is Success
            })
            return Success()

        return Result.fromResults(matchResults.flatMap { it.toList() })
    }

    fun matchingStub(
        request: HttpRequest,
        response: HttpResponse,
        mismatchMessages: MismatchMessages = DefaultMismatchMessages
    ): HttpStubData {
        try {
            val results = stubMatchResult(request, response, mismatchMessages)

            return results.find {
                it.first != null
            }?.let { it.first as HttpStubData }
                ?: throw NoMatchingScenario(
                    failureResults(results).withoutFluff(),
                    msg = null,
                    cachedMessage = failureResults(results).withoutFluff().report(request)
                )
        } finally {
            serverState = emptyMap()
        }
    }

    fun matchingHttpPathPatternFor(path: String): HttpPathPattern? {
        return scenarios.firstOrNull {
            if (it.httpRequestPattern.httpPathPattern == null) return@firstOrNull false
            it.httpRequestPattern.matchesPath(path, it.resolver) is Success
        }?.httpRequestPattern?.httpPathPattern
    }

    private fun stubMatchResult(
        request: HttpRequest,
        response: HttpResponse,
        mismatchMessages: MismatchMessages
    ): List<Pair<HttpStubData?, Result>> {
        val results = scenarios.map { scenario ->
            scenario.newBasedOnAttributeSelectionFields(request.queryParams)
        }.map { scenario ->
            try {
                val keyCheck = if (flagsBased.unexpectedKeyCheck != null)
                    DefaultKeyCheck.copy(unexpectedKeyCheck = flagsBased.unexpectedKeyCheck)
                else DefaultKeyCheck
                when (val matchResult = scenario.matchesMock(
                    request,
                    response,
                    mismatchMessages,
                    keyCheck
                )) {
                    is Success -> Pair(
                        scenario.resolverAndResponseForExpectation(response).let { (resolver, resolvedResponse) ->
                            val newRequestType = scenario.httpRequestPattern.generate(request, resolver)
                            HttpStubData(
                                requestType = newRequestType,
                                response = resolvedResponse.adjustPayloadForContentType(request.headers)
                                    .copy(externalisedResponseCommand = response.externalisedResponseCommand),
                                resolver = resolver,
                                responsePattern = scenario.httpResponsePattern,
                                contractPath = this.path,
                                feature = this,
                                scenario = scenario,
                                originalRequest = request
                            )
                        }, Success()
                    )

                    is Result.Failure -> {
                        Pair(null, matchResult.updateScenario(scenario).updatePath(path))
                    }
                }
            } catch (contractException: ContractException) {
                Pair(null, contractException.failure().updatePath(path))
            }
        }
        return results
    }

    private fun failureResults(results: List<Pair<HttpStubData?, Result>>): Results =
        Results(results.map { it.second }.filterIsInstance<Result.Failure>().toMutableList())

    fun generateContractTests(
        suggestions: List<Scenario>,
        originalScenarios: List<Scenario> = scenarios,
        fn: (Scenario, Row) -> Scenario = { s, _ -> s }
    ): Sequence<ContractTest> {
        val workflow = Workflow(specmaticConfig.getWorkflowDetails() ?: WorkflowDetails.default)

        return generateContractTestScenarios(
            suggestions,
            fn,
            originalScenarios
        ).map { (originalScenario, returnValue) ->
            returnValue.realise(
                hasValue = { concreteTestScenario, comment ->
                    scenarioAsTest(concreteTestScenario, comment, workflow, originalScenario, originalScenarios)
                },
                orFailure = {
                    ScenarioTestGenerationFailure(originalScenario, it.failure, it.message)
                },
                orException = {
                    ScenarioTestGenerationException(originalScenario, it.t, it.message, it.breadCrumb)
                }
            )
        }
    }

    fun createContractTestFromExampleFile(filePath: String): ReturnValue<ContractTest> {
        val example = ExampleFromFile(File(filePath))
        val isBadRequest = (example.responseStatus ?: 0) in invalidRequestStatuses

        val originalScenario = scenarios.firstOrNull { scenario ->
            val matchResult = scenario.matches(
                httpRequest = example.request,
                httpResponse = example.response,
                mismatchMessages = DefaultMismatchMessages,
                flagsBased = flagsBased,
                isPartial = example.isPartial()
            )
            matchResult.isSuccess() || (matchResult as Result.Failure).hasReason(FailureReason.URLPathParamMismatchButSameStructure) && isBadRequest
        } ?: return HasFailure(Result.Failure("Could not find an API matching example $filePath"))

        val exampleRow = example.toRow(specmaticConfig)
        return originalScenario.newBasedOn(exampleRow, flagsBased.withoutGenerativeTests()).map {
            it.ifHasValue { rValue ->
                HasValue(scenarioAsTest(rValue.value, rValue.comments(), Workflow(), originalScenario, scenarios))
            }
        }.firstOrNull() ?: HasFailure(Result.Failure("Could not generate contract test from example file $filePath"))
    }

    private fun scenarioAsTest(
        concreteTestScenario: Scenario,
        comment: String?,
        workflow: Workflow,
        originalScenario: Scenario,
        originalScenarios: List<Scenario> = emptyList()
    ): ContractTest = ScenarioAsTest(
        scenario = adjustTestDescription(concreteTestScenario, originalScenarios),
        feature = this.copy(scenarios = originalScenarios),
        flagsBased,
        concreteTestScenario.sourceProvider,
        concreteTestScenario.sourceRepository,
        concreteTestScenario.sourceRepositoryBranch,
        concreteTestScenario.specification,
        concreteTestScenario.serviceType,
        comment,
        validators = listOf(ExamplePostValidator),
        workflow = workflow,
        originalScenario = originalScenario
    )

    fun adjustTestDescription(scenario: Scenario, scenarios: List<Scenario> = this.scenarios): Scenario {
        if (!isResponseStatusPossible(scenario, HttpStatusCode.Accepted.value, scenarios)) return scenario
        return scenario.copy(
            customAPIDescription = null,
            statusInDescription = "${scenario.statusInDescription}/${HttpStatusCode.Accepted.value}"
        )
    }

    fun isResponseStatusPossible(
        scenario: Scenario,
        responseStatusCode: Int,
        scenarios: List<Scenario> = this.scenarios
    ): Boolean {
        return this.scenarioAssociatedTo(
            scenarios = scenarios,
            path = scenario.path, method = scenario.method,
            responseStatusCode = responseStatusCode, contentType = scenario.requestContentType
        ) != null
    }

    private fun getBadRequestsOrDefault(
        scenario: Scenario,
        scenariosToLookInto: List<Scenario> = scenarios
    ): BadRequestOrDefault? {
        val badRequestResponses = scenariosToLookInto.filter {
            it.httpRequestPattern.httpPathPattern!!.path == scenario.httpRequestPattern.httpPathPattern!!.path
                    && it.httpResponsePattern.status.toString().startsWith("4")
        }.associate { it.httpResponsePattern.status to it.httpResponsePattern }

        val defaultResponse: HttpResponsePattern? = scenariosToLookInto.find {
            it.httpRequestPattern.httpPathPattern!!.path == scenario.httpRequestPattern.httpPathPattern!!.path
                    && it.httpResponsePattern.status == DEFAULT_RESPONSE_CODE
        }?.httpResponsePattern

        if (badRequestResponses.isEmpty() && defaultResponse == null)
            return null

        return BadRequestOrDefault(badRequestResponses, defaultResponse)
    }

    fun generateContractTestScenarios(
        suggestions: List<Scenario>,
        fn: (Scenario, Row) -> Scenario = { s, _ -> s },
        originalScenarios: List<Scenario> = scenarios
    ): Sequence<Pair<Scenario, ReturnValue<Scenario>>> {
        val positiveTestScenarios = positiveTestScenarios(suggestions, fn)

        return if (!specmaticConfig.isResiliencyTestingEnabled() || specmaticConfig.isOnlyPositiveTestingEnabled())
            positiveTestScenarios
        else
            positiveTestScenarios + negativeTestScenarios(originalScenarios)
    }

    private fun positiveTestScenarios(
        suggestions: List<Scenario>,
        fn: (Scenario, Row) -> Scenario = { s, _ -> s }
    ): Sequence<Pair<Scenario, ReturnValue<Scenario>>> =
        scenarios.asSequence().filter {
            it.isA2xxScenario() || it.examples.isNotEmpty() || it.isGherkinScenario
        }.filter {
            !strictMode || it.hasExampleRows()
        }.map {
            it.newBasedOn(suggestions)
        }.flatMap { originalScenario ->
            val resolverStrategies = if (originalScenario.isA2xxScenario())
                flagsBased
            else
                flagsBased.withoutGenerativeTests()

            originalScenario.generateTestScenarios(
                resolverStrategies,
                testVariables,
                testBaseURLs,
                fn
            ).map {
                getScenarioWithDescription(it)
            }.map {
                Pair(originalScenario.copy(generativePrefix = flagsBased.positivePrefix), it)
            }
        }

    fun negativeTestScenarios(originalScenarios: List<Scenario> = scenarios): Sequence<Pair<Scenario, ReturnValue<Scenario>>> {
        return originalScenarios.asSequence().filter {
            it.isA2xxScenario()
        }.filter {
            !strictMode || it.hasExampleRows()
        }.flatMap { originalScenario ->
            val badRequestOrDefault = getBadRequestsOrDefault(originalScenario)
            if (badRequestOrDefaultWasFilteredOut(badRequestOrDefault, originalScenario, originalScenarios)) {
                return@flatMap emptySequence()
            }

            val negativeScenario = originalScenario.negativeBasedOn(badRequestOrDefault)
            val negativeTestScenarios =
                negativeScenario.generateTestScenarios(flagsBased, testVariables, testBaseURLs).map {
                    getScenarioWithDescription(it)
                }

            negativeTestScenarios.filterNot { negativeTestScenarioR ->
                negativeTestScenarioR.withDefault(false) { negativeTestScenario ->
                    val sampleRequest = negativeTestScenario.generateHttpRequest()
                    originalScenario.httpRequestPattern.matches(sampleRequest, originalScenario.resolver).isSuccess()
                }
            }.mapIndexed { index, negativeTestScenarioR ->
                Pair(negativeScenario, negativeTestScenarioR.ifValue { negativeTestScenario ->
                    negativeTestScenario.copy(
                        generativePrefix = flagsBased.negativePrefix,
                        disambiguate = { "[${(index + 1)}] " }
                    )
                })
            }
        }
    }

    private fun badRequestOrDefaultWasFilteredOut(
        badRequestOrDefault: BadRequestOrDefault?,
        originalScenario: Scenario,
        originalScenarios: List<Scenario>
    ): Boolean = badRequestOrDefault == null && getBadRequestsOrDefault(originalScenario, originalScenarios) != null

    fun negativeScenarioFor(scenario: Scenario): Scenario {
        return scenario.negativeBasedOn(getBadRequestsOrDefault(scenario))
    }

    fun generateBackwardCompatibilityTestScenarios(): List<Scenario> =
        scenarios.flatMap { scenario ->
            scenario.copy(examples = emptyList()).generateBackwardCompatibilityScenarios()
        }

    fun matchingStub(
        scenarioStub: ScenarioStub,
        mismatchMessages: MismatchMessages = DefaultMismatchMessages
    ): HttpStubData {
        if (scenarios.isEmpty())
            throw ContractException("No scenarios found in feature $name ($path)")

        if (scenarioStub.partial == null) {
            return matchingStub(
                scenarioStub.request,
                scenarioStub.response,
                mismatchMessages
            ).copy(
                delayInMilliseconds = scenarioStub.delayInMilliseconds,
                requestBodyRegex = scenarioStub.requestBodyRegex?.let { Regex(it) },
                stubToken = scenarioStub.stubToken,
                data = scenarioStub.data,
                examplePath = scenarioStub.filePath,
                name = scenarioStub.nameOrFileName
            )
        }

        val results = scenarios.asSequence().map { scenario ->
            scenario.matchesPartial(scenarioStub.partial, mismatchMessages).updateScenario(scenario)
                .updatePath(path) to scenario
        }

        val matchingScenario = results.filter { it.first is Success }.map { it.second }.firstOrNull()
        if (matchingScenario == null) {
            val failures = Results(results.map { it.first }.filterIsInstance<Result.Failure>().toList()).withoutFluff()
            throw NoMatchingScenario(failures, msg = "Could not load partial example ${scenarioStub.filePath}")
        }

        val requestTypeWithAncestors =
            matchingScenario.httpRequestPattern.copy(
                headersPattern = matchingScenario.httpRequestPattern.headersPattern.copy(
                    ancestorHeaders = matchingScenario.httpRequestPattern.headersPattern.pattern
                )
            )

        val responseTypeWithAncestors =
            matchingScenario.httpResponsePattern.copy(
                headersPattern = matchingScenario.httpResponsePattern.headersPattern.copy(
                    ancestorHeaders = matchingScenario.httpResponsePattern.headersPattern.pattern
                )
            )

        return HttpStubData(
            requestTypeWithAncestors,
            HttpResponse(),
            matchingScenario.resolver,
            responsePattern = responseTypeWithAncestors,
            examplePath = scenarioStub.filePath,
            scenario = matchingScenario,
            data = scenarioStub.data,
            contractPath = this.path,
            partial = scenarioStub.partial.copy(response = scenarioStub.partial.response),
            name = scenarioStub.nameOrFileName
        )
    }

    fun clearServerState() {
        serverState = emptyMap()
    }

    fun overrideInlineExamples(externalExampleNames: Set<String>): Feature {
        return this.copy(
            exampleStore = this.exampleStore.filter { exampleData ->
                exampleData.name !in externalExampleNames
            }
        )
    }

    fun scenarioAssociatedTo(
        method: String,
        path: String,
        responseStatusCode: Int,
        contentType: String? = null,
        scenarios: List<Scenario> = this.scenarios,
    ): Scenario? {
        return scenarios.firstOrNull {
            it.method == method && it.status == responseStatusCode && it.path == path
                    && (contentType == null || it.requestContentType == null || it.requestContentType == contentType)
        }
    }

    fun identifierMatchingScenario(httpRequest: HttpRequest): Scenario? {
        return scenarios.asSequence().filter {
            it.httpRequestPattern.matchesPathStructureMethodAndContentType(httpRequest, it.resolver).isSuccess()
        }.firstOrNull()
    }

    private fun getScenarioWithDescription(scenarioResult: ReturnValue<Scenario>): ReturnValue<Scenario> {
        return scenarioResult.ifHasValue { result: HasValue<Scenario> ->
            val apiDescription = result.value.customAPIDescription ?: result.value.defaultAPIDescription
            val tag = result.valueDetails.singleLineDescription().takeIf { it.isNotBlank() }
            HasValue(result.value.copy(customAPIDescription = apiDescription, requestChangeSummary = tag))
        }
    }

    private fun combine(baseScenario: Scenario, newScenario: Scenario): Scenario {
        return convergeHeaders(baseScenario, newScenario).let { convergedScenario ->
            convergeQueryParameters(convergedScenario, newScenario)
        }.let { convergedScenario ->
            convergeRequestPayload(convergedScenario, newScenario)
        }.let { convergedScenario ->
            convergeResponsePayload(convergedScenario, newScenario)
        }
    }

    private fun convergeResponsePayload(baseScenario: Scenario, newScenario: Scenario): Scenario {
        val baseResponsePayload = baseScenario.httpResponsePattern.body
        val newResponsePayload = newScenario.httpResponsePattern.body

        return convergeDataStructure(baseResponsePayload, newResponsePayload, baseScenario.name) { converged ->
            baseScenario.copy(
                httpResponsePattern = baseScenario.httpResponsePattern.copy(
                    body = converged
                )
            )
        }
    }

    private fun convergeRequestPayload(baseScenario: Scenario, newScenario: Scenario): Scenario {
        if (baseScenario.httpRequestPattern.multiPartFormDataPattern.isNotEmpty())
            TODO("Multipart requests not yet supported")

        return if (baseScenario.httpRequestPattern.formFieldsPattern.size == 1) {
            if (newScenario.httpRequestPattern.formFieldsPattern.size != 1)
                throw ContractException("${baseScenario.httpRequestPattern.method} ${baseScenario.httpRequestPattern.httpPathPattern?.path} exists with different form fields")

            val baseRawPattern = baseScenario.httpRequestPattern.formFieldsPattern.values.first()
            val resolvedBasePattern = resolvedHop(baseRawPattern, baseScenario.resolver)

            val newRawPattern = newScenario.httpRequestPattern.formFieldsPattern.values.first()
            val resolvedNewPattern = resolvedHop(newRawPattern, newScenario.resolver)

            if (isObjectType(resolvedBasePattern) && !isObjectType(resolvedNewPattern))
                throw ContractException("${baseScenario.httpRequestPattern.method} ${baseScenario.httpRequestPattern.httpPathPattern?.path} exists with multiple payload types")

            val converged: Pattern = when {
                resolvedBasePattern.pattern is String && builtInPatterns.contains(resolvedBasePattern.pattern) -> {
                    if (resolvedBasePattern.pattern != resolvedNewPattern.pattern)
                        throw ContractException("Cannot converge ${baseScenario.httpRequestPattern.method} ${baseScenario.httpRequestPattern.httpPathPattern?.path} because there are multiple types of request payloads")

                    resolvedBasePattern
                }

                baseRawPattern is DeferredPattern -> {
                    if (baseRawPattern.pattern == newRawPattern.pattern && isObjectType(resolvedBasePattern))
                        baseRawPattern
                    else
                        throw ContractException("Cannot converge different types ${baseRawPattern.pattern} and ${newRawPattern.pattern} found in ${baseScenario.httpRequestPattern.method} ${baseScenario.httpRequestPattern.httpPathPattern?.path}")
                }

                else ->
                    TODO("Converging of type ${resolvedBasePattern.pattern} and ${resolvedNewPattern.pattern} in ${baseScenario.httpRequestPattern.method} ${baseScenario.httpRequestPattern.httpPathPattern?.path}")
            }

            baseScenario.copy(
                httpRequestPattern = baseScenario.httpRequestPattern.copy(
                    formFieldsPattern = mapOf(baseScenario.httpRequestPattern.formFieldsPattern.keys.first() to converged)
                )
            )
        } else if (baseScenario.httpRequestPattern.formFieldsPattern.isNotEmpty()) {
            TODO(
                "Form fields with non-json-object values (${
                    baseScenario.httpRequestPattern.formFieldsPattern.values.joinToString(
                        ", "
                    ) { it.typeAlias ?: if (it.pattern is String) it.pattern.toString() else it.typeName }
                })"
            )
        } else {
            val baseRequestBody = baseScenario.httpRequestPattern.body
            val newRequestBody = newScenario.httpRequestPattern.body

            convergeDataStructure(baseRequestBody, newRequestBody, baseScenario.name) { converged ->
                baseScenario.copy(
                    httpRequestPattern = baseScenario.httpRequestPattern.copy(
                        body = converged
                    )
                )
            }
        }
    }

    private fun convergeDataStructure(
        basePayload: Pattern,
        newPayload: Pattern,
        scenarioName: String,
        updateConverged: (Pattern) -> Scenario
    ): Scenario {
        return updateConverged(converge(basePayload, newPayload, scenarioName))
    }

    private fun converge(
        basePayload: Pattern,
        newPayload: Pattern,
        scenarioName: String,
    ): Pattern {
        return if (basePayload is TabularPattern && newPayload is TabularPattern) {
            TabularPattern(convergePatternMap(basePayload.pattern, newPayload.pattern))
        } else if (basePayload is ListPattern && newPayload is JSONArrayPattern) {
            val convergedNewPattern: Pattern = newPayload.pattern.fold(basePayload.pattern) { acc, newPattern ->
                converge(acc, newPattern, scenarioName)
            }

            ListPattern(convergedNewPattern)
        } else if (basePayload is JSONArrayPattern && basePayload.pattern.isEmpty() && newPayload is JSONArrayPattern && newPayload.pattern.isEmpty()) {
            val baseTypeAlias = basePayload.typeAlias
            val newTypeAlias = newPayload.typeAlias

            if (baseTypeAlias != null)
                ListPattern(DeferredPattern(baseTypeAlias))
            else if (newTypeAlias != null)
                ListPattern(DeferredPattern(newTypeAlias))
            else
                basePayload
        } else if (basePayload is JSONArrayPattern && newPayload is JSONArrayPattern && (basePayload.pattern.isNotEmpty() || newPayload.pattern.isNotEmpty())) {
            val allPatterns = basePayload.pattern + newPayload.pattern
            val convergedPattern: Pattern =
                allPatterns.reduce { acc, newPattern ->
                    converge(acc, newPattern, scenarioName)
                }

            ListPattern(convergedPattern)
        } else if (basePayload is JSONArrayPattern && newPayload is ListPattern) {
            if (basePayload.pattern.isEmpty()) {
                newPayload
            } else {
                val allPatterns = basePayload.pattern + newPayload.pattern
                val convergedPattern: Pattern =
                    allPatterns.reduce { acc, newPattern ->
                        converge(acc, newPattern, scenarioName)
                    }

                ListPattern(convergedPattern)
            }
        } else if (basePayload is ListPattern && newPayload is ListPattern) {
            val convergedNewPattern: Pattern = converge(basePayload.pattern, newPayload.pattern, scenarioName)

            ListPattern(convergedNewPattern)
        } else if (bothAreIdenticalDeferredPatterns(basePayload, newPayload)) {
            basePayload
        } else if (bothAreTheSamePrimitive(basePayload, newPayload)) {
            basePayload
        } else {
            throw ContractException("Payload definitions could not be converged (seen in Scenario named ${scenarioName}: ${basePayload.typeAlias ?: basePayload.typeName}, ${newPayload.typeAlias ?: newPayload.typeName})")
        }
    }

    private fun bothAreTheSamePrimitive(baseRequestBody: Pattern, newRequestBody: Pattern): Boolean {
        if (baseRequestBody is EmptyStringPattern && newRequestBody is EmptyStringPattern) return true

        val basePatternType = when (baseRequestBody) {
            is LookupRowPattern -> baseRequestBody.pattern.pattern
            else -> baseRequestBody.pattern
        }.takeIf { it is String && builtInPatterns.contains(it) } ?: return false

        val newPatternType = when (newRequestBody) {
            is LookupRowPattern -> newRequestBody.pattern.pattern
            else -> newRequestBody.pattern
        }.takeIf { it is String && builtInPatterns.contains(it) } ?: return false

        return basePatternType == newPatternType
    }

    private fun bothAreIdenticalDeferredPatterns(
        baseRequestBody: Pattern,
        newRequestBody: Pattern
    ) =
        baseRequestBody is DeferredPattern && newRequestBody is DeferredPattern && baseRequestBody.pattern == newRequestBody.pattern

    private fun convergeQueryParameters(baseScenario: Scenario, newScenario: Scenario): Scenario {
        val baseQueryParams = baseScenario.httpRequestPattern.httpQueryParamPattern.queryPatterns
        val newQueryParams = newScenario.httpRequestPattern.httpQueryParamPattern.queryPatterns

        val convergedQueryParams = convergePatternMap(baseQueryParams, newQueryParams)

        return baseScenario.copy(
            httpRequestPattern = baseScenario.httpRequestPattern.copy(
                httpQueryParamPattern = baseScenario.httpRequestPattern.httpQueryParamPattern.copy(queryPatterns = convergedQueryParams)
            )
        )
    }

    private fun convergeHeaders(baseScenario: Scenario, newScenario: Scenario): Scenario {
        val baseRequestHeaders = baseScenario.httpRequestPattern.headersPattern.pattern
        val newRequestHeaders = newScenario.httpRequestPattern.headersPattern.pattern
        val convergedRequestHeaders = convergePatternMap(baseRequestHeaders, newRequestHeaders)

        val baseResponseHeaders = baseScenario.httpResponsePattern.headersPattern.pattern
        val newResponseHeaders = newScenario.httpResponsePattern.headersPattern.pattern
        val convergedResponseHeaders = convergePatternMap(baseResponseHeaders, newResponseHeaders)

        return baseScenario.copy(
            httpRequestPattern = baseScenario.httpRequestPattern.copy(
                headersPattern = HttpHeadersPattern(convergedRequestHeaders)
            ),
            httpResponsePattern = baseScenario.httpResponsePattern.copy(
                headersPattern = HttpHeadersPattern(convergedResponseHeaders)
            )
        )
    }

    private fun toOpenAPIURLPrefixMap(urls: List<String>): Map<String, String> {
        return urls.toSet().associateWith { url ->
            url.removeSuffix("/").removePrefix("http://").removePrefix("https://")
                .split("/").filterNot { it.isEmpty() }
                .joinToString("_") { segment -> segment.capitalizeFirstChar() }
        }
    }

    fun toOpenApi(): OpenAPI {
        val openAPI = OpenAPI()
        openAPI.info = Info().also {
            it.title = this.name
            it.version = "1"
        }

        scenarios.find { it.httpRequestPattern.method == null }?.let {
            throw ContractException("Scenario ${it.name} has no method")
        }

        scenarios.find { it.httpRequestPattern.httpPathPattern == null }?.let {
            throw ContractException("Scenario ${it.name} has no path")
        }

        val urlPrefixMap = toOpenAPIURLPrefixMap(scenarios.mapNotNull {
            it.httpRequestPattern.httpPathPattern?.path
        }.map {
            OpenApiPath.from(it).normalize().toPath()
        })

        val payloadAdjustedScenarios: List<Scenario> = scenarios.map { rawScenario ->
            val prefix = urlPrefixMap.getValue(OpenApiPath.from(rawScenario.httpRequestPattern.httpPathPattern?.path!!).normalize().toPath())
            var scenario = updateScenarioContentTypeFromPattern(rawScenario)

            if (hasBodyJsonPattern(scenario.httpRequestPattern.body, scenario.resolver)) {
                val requestBody = scenario.httpRequestPattern.body
                val oldTypeName = requestBody.typeAlias ?: "(RequestBody)"
                val newTypeName = jsonPatternToUniqueName(
                    prefix = prefix,
                    pattern = requestBody,
                    method = scenario.method,
                    contentType = scenario.httpRequestPattern.headersPattern.contentType,
                ).let(::withPatternDelimiters)

                val newRequestBody = updateJsonPatternName(requestBody, newTypeName)
                val newTypes = scenario.patterns[oldTypeName]?.let {
                    scenario.patterns.minus(oldTypeName).plus(newTypeName to it)
                } ?: scenario.patterns

                scenario = scenario.copy(
                    patterns = newTypes,
                    httpRequestPattern = scenario.httpRequestPattern.copy(body = newRequestBody),
                )
            }

            if (hasBodyJsonPattern(scenario.httpResponsePattern.body, scenario.resolver)) {
                val responseBody = scenario.httpResponsePattern.body
                val oldTypeName = responseBody.typeAlias ?: "(ResponseBody)"
                val newTypeName = jsonPatternToUniqueName(
                    prefix = prefix,
                    pattern = responseBody,
                    status = scenario.status,
                    method = scenario.method,
                    contentType = scenario.httpResponsePattern.headersPattern.contentType,
                ).let(::withPatternDelimiters)

                val newResponseBody = updateJsonPatternName(responseBody, newTypeName)
                val newTypes = scenario.patterns[oldTypeName]?.let {
                    scenario.patterns.minus(oldTypeName).plus(newTypeName to it)
                } ?: scenario.patterns

                scenario = scenario.copy(
                    patterns = newTypes,
                    httpResponsePattern = scenario.httpResponsePattern.copy(body = newResponseBody),
                )
            }

            scenario.copy(
                httpRequestPattern = scenario.httpRequestPattern.copy(
                    httpPathPattern = scenario.httpRequestPattern.httpPathPattern?.let(::toPathPatternWithParameters)
                )
            )
        }

        val rawCombinedScenarios =
            payloadAdjustedScenarios.fold(emptyList<Scenario>()) { acc, payloadAdjustedScenario ->
                val scenarioWithSameIdentifiers = acc.find { alreadyCombinedScenario: Scenario ->
                    similarURLPath(alreadyCombinedScenario, payloadAdjustedScenario)
                            && alreadyCombinedScenario.httpRequestPattern.method == payloadAdjustedScenario.httpRequestPattern.method
                            && alreadyCombinedScenario.httpResponsePattern.status == payloadAdjustedScenario.httpResponsePattern.status
                            && (alreadyCombinedScenario.requestContentType == payloadAdjustedScenario.requestContentType || (alreadyCombinedScenario.requestContentType == null || payloadAdjustedScenario.requestContentType == null))
                            && (alreadyCombinedScenario.responseContentType == payloadAdjustedScenario.responseContentType || (alreadyCombinedScenario.responseContentType == null || payloadAdjustedScenario.responseContentType == null))
                }

                if (scenarioWithSameIdentifiers == null)
                    acc.plus(payloadAdjustedScenario)
                else {
                    val combined = combine(scenarioWithSameIdentifiers, payloadAdjustedScenario)
                    acc.minus(scenarioWithSameIdentifiers).plus(combined)
                }
            }

        val paths: List<Pair<String, PathItem>> = rawCombinedScenarios.fold(emptyList()) { acc, scenario ->
            val pathName = scenario.httpRequestPattern.httpPathPattern!!.toOpenApiPath()

            val existingPathItem = acc.find { it.first == pathName }?.second
            val pathItem = existingPathItem ?: PathItem()

            val operation = when (scenario.httpRequestPattern.method!!) {
                "GET" -> pathItem.get
                "POST" -> pathItem.post
                "PUT" -> pathItem.put
                "DELETE" -> pathItem.delete
                "PATCH" -> pathItem.patch
                else -> TODO("Method \"${scenario.httpRequestPattern.method}\" in scenario ${scenario.name}")
            } ?: Operation().apply {
                this.summary = withoutQueryParams(scenario.name)
            }

            val pathParameters = scenario.httpRequestPattern.httpPathPattern.pathParameters()

            val openApiPathParameters = pathParameters.map {
                val pathParameter: Parameter = PathParameter()
                pathParameter.name = it.key
                pathParameter.schema = toOpenApiSchema(it.pattern)
                pathParameter
            }
            val queryParameters = scenario.httpRequestPattern.httpQueryParamPattern.queryPatterns
            val openApiQueryParameters = queryParameters.map { (key, pattern) ->
                val queryParameter: Parameter = QueryParameter()
                queryParameter.name = key.removeSuffix("?")
                queryParameter.schema = toOpenApiSchema(pattern)
                queryParameter
            }
            val openApiRequestHeaders = scenario.httpRequestPattern.headersPattern.pattern.map { (key, pattern) ->
                val headerParameter = HeaderParameter()
                headerParameter.name = key.removeSuffix("?")
                headerParameter.schema = toOpenApiSchema(pattern)
                headerParameter.required = key.contains("?").not()
                headerParameter
            }

            val requestBodyType = scenario.httpRequestPattern.body

            val requestBodySchema: Pair<String, MediaType>? = requestBodySchema(requestBodyType, scenario)

            if (requestBodySchema != null) {
                operation.requestBody = (operation.requestBody ?: RequestBody()).apply {
                    this.required = true
                    this.content = (this.content ?: Content()).apply {
                        this[requestBodySchema.first] = requestBodySchema.second
                    }
                }
            }

            operation.parameters = operation.parameters.orEmpty()
                .plus(openApiPathParameters + openApiQueryParameters + openApiRequestHeaders).distinct()
            val responses = operation.responses ?: ApiResponses()
            val apiResponse = responses[scenario.httpResponsePattern.status.toString()] ?: ApiResponse()

            apiResponse.description = withoutQueryParams(scenario.name)
            val openApiResponseHeaders = scenario.httpResponsePattern.headersPattern.pattern.map { (key, pattern) ->
                val header = Header()
                header.schema = toOpenApiSchema(pattern)
                header.required = !key.endsWith("?")

                Pair(withoutOptionality(key), header)
            }.toMap()

            if (openApiResponseHeaders.isNotEmpty()) {
                apiResponse.headers = apiResponse.headers.orEmpty().plus(openApiResponseHeaders)
            }

            if (scenario.httpResponsePattern.body !is EmptyStringPattern) {
                apiResponse.content = (apiResponse.content ?: Content()).apply {
                    val responseBodyType = scenario.httpResponsePattern.body
                    val responseBodySchema: Pair<String, MediaType> = responseBodySchema(responseBodyType, scenario)
                    this.addMediaType(responseBodySchema.first, responseBodySchema.second)
                }
            }

            when (scenario.httpRequestPattern.method) {
                "GET" -> pathItem.get = operation
                "POST" -> pathItem.post = operation
                "PUT" -> pathItem.put = operation
                "DELETE" -> pathItem.delete = operation
                "PATCH" -> pathItem.patch = operation
            }

            responses.addApiResponse(scenario.httpResponsePattern.status.toString(), apiResponse)
            operation.responses = responses
            acc.plus(pathName to pathItem)
        }

        val schemas: Map<String, Pattern> = payloadAdjustedScenarios.map {
            it.patterns.entries
        }.flatten().fold(emptyMap<String, Pattern>()) { acc, entry ->
            val key = withoutPatternDelimiters(entry.key)

            val accPattern = acc[key] ?: return@fold acc.plus(key to entry.value)

            if (isObjectType(accPattern)) {
                if (!isObjectType(entry.value)) {
                    return@fold acc
                }

                val converged: Map<String, Pattern> = objectStructure(accPattern)
                val new: Map<String, Pattern> = objectStructure(entry.value)

                return@fold acc.plus(key to TabularPattern(convergePatternMap(converged, new)))
            }

            if (accPattern is ListPattern && isObjectType(accPattern.pattern)) {
                val entryPattern = entry.value

                val new: Map<String, Pattern> =
                    if (entryPattern is ListPattern && isObjectType(entryPattern.pattern)) {
                        objectStructure(entryPattern)
                    } else if (entryPattern is JSONArrayPattern && entryPattern.pattern.isNotEmpty()) {
                        if (entryPattern.pattern.any { !isObjectType(it) }) {
                            return@fold acc
                        }

                        entryPattern.pattern.fold(emptyMap()) { acc, pattern ->
                            val patternStruct = objectStructure(pattern)
                            convergePatternMap(acc, patternStruct)
                        }
                    } else {
                        return@fold acc
                    }

                val converged: Map<String, Pattern> = objectStructure(accPattern.pattern)

                return@fold acc.plus(key to ListPattern(JSONObjectPattern(convergePatternMap(converged, new))))
            }

            if (accPattern is JSONArrayPattern && accPattern.pattern.isNotEmpty() && accPattern.pattern.all {
                    isObjectType(
                        it
                    )
                }) {
                val entryPattern = entry.value

                val new: Map<String, Pattern> =
                    if (entryPattern is ListPattern && isObjectType(entryPattern.pattern)) {
                        objectStructure(entryPattern)
                    } else if (entryPattern is JSONArrayPattern && entryPattern.pattern.isNotEmpty()) {
                        if (entryPattern.pattern.any { !isObjectType(it) }) {
                            return@fold acc
                        }

                        entryPattern.pattern.fold(emptyMap()) { acc, pattern ->
                            val patternStruct = objectStructure(pattern)
                            convergePatternMap(acc, patternStruct)
                        }
                    } else {
                        return@fold acc
                    }

                val converged: Map<String, Pattern> = accPattern.pattern.fold(emptyMap()) { acc, pattern ->
                    val patternStruct = objectStructure(pattern)
                    convergePatternMap(acc, patternStruct)
                }

                return@fold acc.plus(key to JSONObjectPattern(convergePatternMap(converged, new)))
            }

            return@fold acc.plus(key to entry.value)
        }.mapKeys {
            withoutPatternDelimiters(it.key)
        }

        if (schemas.isNotEmpty()) {
            openAPI.components = Components()
            openAPI.components.schemas = schemas.mapValues {
                toOpenApiSchema(it.value)
            }
        }

        openAPI.paths = Paths().also {
            paths.forEach { (pathName, newPath) ->
                it.addPathItem(pathName, newPath)
            }
        }

        return openAPI
    }

    private fun hasBodyJsonPattern(body: Pattern, resolver: Resolver): Boolean {
        return when (body) {
            is DeferredPattern -> body.pattern in listOf(
                "(RequestBody)",
                "(ResponseBody)"
            ) && isJSONPayload(body.resolvePattern(resolver))

            else -> isJSONPayload(body)
        }
    }

    private fun jsonPatternToUniqueName(
        pattern: Pattern,
        method: String,
        status: Int? = null,
        contentType: String? = null,
        prefix: String? = null
    ): String {
        val patternTypeAlias = if (pattern is ListPattern) pattern.pattern.typeAlias else pattern.typeAlias
        return listOfNotNull(
            prefix,
            method,
            status?.toString(),
            contentType,
            patternTypeAlias?.let(::withoutPatternDelimiters)
        )
            .joinToString(separator = "_")
            .replace(OPENAPI_MAP_KEY_NEGATED_PATTERN, "_")
            .replace(Regex("_{2,}"), "_")
    }

    private fun updateJsonPatternName(pattern: Pattern, name: String): Pattern {
        return when (pattern) {
            is DeferredPattern -> pattern.copy(pattern = name)
            is TabularPattern -> pattern.copy(typeAlias = name)
            is JSONArrayPattern -> pattern.copy(typeAlias = name)
            is JSONObjectPattern -> pattern.copy(typeAlias = name)
            is ListPattern -> pattern.copy(typeAlias = name, pattern = updateJsonPatternName(pattern.pattern, name))
            else -> pattern
        }
    }

    private fun updateScenarioContentTypeFromPattern(scenario: Scenario): Scenario {
        val (reqContentTypeEntry, otherReqHeaders) = partitionOnContentType(scenario.httpRequestPattern.headersPattern.pattern)
        val requestContentType = (reqContentTypeEntry?.value as? ExactValuePattern)?.pattern?.toStringLiteral()

        val (resContentTypeEntry, otherResHeaders) = partitionOnContentType(scenario.httpResponsePattern.headersPattern.pattern)
        val responseContentType = (resContentTypeEntry?.value as? ExactValuePattern)?.pattern?.toStringLiteral()

        return scenario.copy(
            httpRequestPattern = scenario.httpRequestPattern.copy(
                headersPattern = scenario.httpRequestPattern.headersPattern.copy(
                    pattern = otherReqHeaders, contentType = requestContentType
                )
            ),
            httpResponsePattern = scenario.httpResponsePattern.copy(
                headersPattern = scenario.httpResponsePattern.headersPattern.copy(
                    pattern = otherResHeaders, contentType = responseContentType
                )
            )
        )
    }

    private fun withoutQueryParams(name: String): String {
        return name.replace(Regex("""\?.*$"""), "")
    }

    private fun toPathPatternWithParameters(httpPathPattern: HttpPathPattern?): HttpPathPattern {
        if (httpPathPattern!!.pathSegmentPatterns.any { it.pattern !is ExactValuePattern }) return httpPathPattern
        return OpenApiPath.from(httpPathPattern.path).normalize().toHttpPathPattern()
    }

    private fun requestBodySchema(requestBodyType: Pattern, scenario: Scenario): Pair<String, MediaType>? = when {
        scenario.requestContentType?.takeUnless(String::isNullOrBlank) != null -> {
            val mediaType = MediaType()
            mediaType.schema = toOpenApiSchema(requestBodyType)
            Pair(scenario.requestContentType.orEmpty(), mediaType)
        }

        requestBodyType is LookupRowPattern -> {
            requestBodySchema(requestBodyType.pattern, scenario)
        }

        isJSONPayload(requestBodyType) || requestBodyType is DeferredPattern && isJSONPayload(
            requestBodyType.resolvePattern(
                scenario.resolver
            )
        ) -> {
            jsonMediaType(requestBodyType)
        }

        requestBodyType is XMLPattern || requestBodyType is DeferredPattern && requestBodyType.resolvePattern(scenario.resolver) is XMLPattern -> {
            throw ContractException("XML not supported yet")
        }

        requestBodyType is ExactValuePattern -> {
            val mediaType = MediaType()
            mediaType.schema = toOpenApiSchema(requestBodyType)
            Pair("text/plain", mediaType)
        }

        requestBodyType.pattern.let { it is String && builtInPatterns.contains(it) } -> {
            val mediaType = MediaType()
            mediaType.schema = toOpenApiSchema(requestBodyType)
            Pair("text/plain", mediaType)
        }

        else -> {
            if (scenario.httpRequestPattern.formFieldsPattern.isNotEmpty()) {
                val mediaType = MediaType()
                mediaType.schema = Schema<Any>().apply {
                    this.type = OBJECT_TYPE
                    this.required = scenario.httpRequestPattern.formFieldsPattern.keys.toList()
                    this.properties = scenario.httpRequestPattern.formFieldsPattern.map { (key, type) ->
                        val schema = toOpenApiSchema(type)
                        Pair(withoutOptionality(key), schema)
                    }.toMap()
                }

                val encoding: MutableMap<String, Encoding> =
                    scenario.httpRequestPattern.formFieldsPattern.map { (key, type) ->
                        when {
                            isJSONPayload(type) || (type is DeferredPattern && isJSONPayload(
                                type.resolvePattern(
                                    scenario.resolver
                                )
                            )) -> {
                                val encoding = Encoding().apply {
                                    this.contentType = "application/json"
                                }

                                Pair(withoutOptionality(key), encoding)
                            }

                            type is XMLPattern ->
                                throw NotImplementedError("XML encoding not supported for form fields")

                            else -> {
                                null
                            }
                        }
                    }.filterNotNull().toMap().toMutableMap()

                if (encoding.isNotEmpty())
                    mediaType.encoding = encoding

                Pair("application/x-www-form-urlencoded", mediaType)
            } else if (scenario.httpRequestPattern.multiPartFormDataPattern.isNotEmpty()) {
                throw NotImplementedError("multipart form data not yet supported")
            } else {
                null
            }
        }
    }

    private fun responseBodySchema(responseBodyType: Pattern, scenario: Scenario): Pair<String, MediaType> = when {
        scenario.responseContentType?.takeUnless(String::isNullOrBlank) != null -> {
            val mediaType = MediaType()
            mediaType.schema = toOpenApiSchema(responseBodyType)
            Pair(scenario.responseContentType.orEmpty(), mediaType)
        }

        responseBodyType is LookupRowPattern -> {
            responseBodySchema(responseBodyType.pattern, scenario)
        }

        isJSONPayload(responseBodyType) || responseBodyType is DeferredPattern && isJSONPayload(
            responseBodyType.resolvePattern(
                scenario.resolver
            )
        ) -> {
            jsonMediaType(responseBodyType)
        }

        responseBodyType is XMLPattern || responseBodyType is DeferredPattern && responseBodyType.resolvePattern(
            scenario.resolver
        ) is XMLPattern -> {
            throw ContractException("XML not supported yet")
        }

        else -> {
            val mediaType = MediaType()
            mediaType.schema = toOpenApiSchema(responseBodyType)
            val responseContentType = scenario.httpResponsePattern.headersPattern.contentType ?: "text/plain"
            Pair(responseContentType, mediaType)
        }
    }

    private fun jsonMediaType(requestBodyType: Pattern): Pair<String, MediaType> {
        val mediaType = MediaType()
        mediaType.schema = toOpenApiSchema(requestBodyType)
        return Pair("application/json", mediaType)
    }

    private fun cleanupDescriptor(descriptor: String): String {
        val withoutBrackets = withoutPatternDelimiters(descriptor)
        val modifiersTrimmed = withoutBrackets.trimEnd('*', '?')

        val (base, modifiers) = if (withoutBrackets == modifiersTrimmed)
            Pair(withoutBrackets, "")
        else {
            val modifiers = withoutBrackets.substring(modifiersTrimmed.length)
            Pair(modifiersTrimmed, modifiers)
        }

        return "${base.trim('_')}$modifiers"
    }

    private fun getTypeAndDescriptor(map: Map<String, Pattern>, key: String): Pair<String, Pattern> {
        val nonOptionalKey = withoutOptionality(key)
        val optionalKey = "$nonOptionalKey?"
        val commonValueType = map.getOrElse(nonOptionalKey) { map.getValue(optionalKey) }

        val descriptor = commonValueType.typeAlias
            ?: commonValueType.pattern.let { if (it is String) it else commonValueType.typeName }

        return Pair(descriptor, commonValueType)
    }

    private fun convergePatternMap(map1: Map<String, Pattern>, map2: Map<String, Pattern>): Map<String, Pattern> =
        try {
            val map2LowercaseKeys = map2.mapKeys { it.key.lowercase() }

            val common: Map<String, Pattern> = map1.filter { entry ->
                val cleanedKey = withoutOptionality(entry.key).lowercase()
                cleanedKey in map2LowercaseKeys || "$cleanedKey?" in map2
            }.mapKeys { entry ->
                val cleanedKey = withoutOptionality(entry.key)

                if (isOptional(entry.key) || "${cleanedKey.lowercase()}?" in map2LowercaseKeys) {
                    "${withoutOptionality(entry.key)}?"
                } else {
                    cleanedKey
                }
            }.mapValues { entry ->
                val (type1Descriptor, type1) = getTypeAndDescriptor(map1, entry.key)
                val (type2Descriptor, type2) = getTypeAndDescriptor(map2LowercaseKeys, entry.key.lowercase())

                if (type1Descriptor != type2Descriptor) {
                    val typeDescriptors = listOf(type1Descriptor, type2Descriptor).sorted()
                    val cleanedUpDescriptors = typeDescriptors.map { cleanupDescriptor(it) }

                    if (isEmptyOrNull(type1) || isEmptyOrNull(type2)) {
                        val type = if (isEmptyOrNull(type1)) type2 else type1

                        if (type is DeferredPattern) {
                            val descriptor = if (isEmptyOrNull(type1)) type2Descriptor else type1Descriptor
                            val withoutBrackets = withoutPatternDelimiters(descriptor)
                            val newPattern = withoutBrackets.removeSuffix("?").let { "($it)" }
                            val patterns = listOf(NullPattern, type.copy(pattern = newPattern))
                            AnyPattern(patterns, extensions = patterns.extractCombinedExtensions())
                        } else {
                            val patterns = listOf(NullPattern, type)
                            AnyPattern(patterns, extensions = patterns.extractCombinedExtensions())
                        }
                    } else if (cleanedUpDescriptors.first() == cleanedUpDescriptors.second()) {
                        entry.value
                    } else if (
                        withoutPatternDelimiters(cleanedUpDescriptors.second()).trimEnd('?') == withoutPatternDelimiters(
                            cleanedUpDescriptors.first()
                        )
                    ) {
                        val type: Pattern = listOf(map1, map2).map {
                            getTypeAndDescriptor(it, entry.key)
                        }.associate {
                            cleanupDescriptor(it.first) to it.second
                        }.getValue(cleanedUpDescriptors.second())

                        type
                    } else {
                        logger.log("Found conflicting values for the same key ${entry.key} ($type1Descriptor, $type2Descriptor).")
                        entry.value
                    }
                } else
                    entry.value
            }

            val commonLowercaseKey = common.mapKeys { it.key.lowercase() }

            val onlyInMap1: Map<String, Pattern> = entriesOnlyInFirstMap(map1, commonLowercaseKey)
            val onlyInMap2: Map<String, Pattern> = entriesOnlyInFirstMap(map2, commonLowercaseKey)

            common.plus(onlyInMap1).plus(onlyInMap2)
        } catch(e: Throwable) {
            throw e
        }

    private fun entriesOnlyInFirstMap(
        map1: Map<String, Pattern>,
        map2: Map<String, Pattern>
    ): Map<String, Pattern> {
        val onlyInMap1: Map<String, Pattern> = map1.filter { entry ->
            val cleanedKey = withoutOptionality(entry.key).lowercase()
            (cleanedKey !in map2 && "${cleanedKey}?" !in map2)
        }.mapKeys { entry ->
            val cleanedKey = withoutOptionality(entry.key)
            "${cleanedKey}?"
        }
        return onlyInMap1
    }

    private fun objectStructure(objectType: Pattern): Map<String, Pattern> {
        return when (objectType) {
            is TabularPattern -> objectType.pattern
            is JSONObjectPattern -> objectType.pattern
            else -> throw ContractException("Unrecognized type ${objectType.typeName}")
        }
    }

    private fun isObjectType(type: Pattern): Boolean = type is TabularPattern || type is JSONObjectPattern

    private fun isJSONPayload(type: Pattern) =
        type is TabularPattern || type is JSONObjectPattern || type is JSONArrayPattern || type is ListPattern

    @Suppress("MemberVisibilityCanBePrivate")
    fun toOpenApiSchema(pattern: Pattern): Schema<Any> {
        val schema = when {
            pattern is EmailPattern -> EmailSchema()
            pattern is NumberPattern -> {
                val schema = if (pattern.isDoubleFormat) NumberSchema() else IntegerSchema().apply { format = null }
                schema.apply {
                    minimum = pattern.minimum;
                    maximum = pattern.maximum;
                    exclusiveMinimum = pattern.exclusiveMinimum.takeIf { it }
                    exclusiveMaximum = pattern.exclusiveMaximum.takeIf { it }
                }
            }
            pattern is StringPattern -> {
                StringSchema().apply {
                    minLength = pattern.minLength;
                    maxLength = pattern.maxLength;
                    this.pattern = pattern.regex
                }
            }
            pattern is DictionaryPattern -> {
                ObjectSchema().apply {
                    additionalProperties = Schema<Any>().apply {
                        this.`$ref` = withoutPatternDelimiters(pattern.valuePattern.pattern.toString())
                    }
                }
            }
            pattern is LookupRowPattern -> toOpenApiSchema(pattern.pattern)
            pattern is TabularPattern -> tabularToSchema(pattern)
            pattern is JSONObjectPattern -> jsonObjectToSchema(pattern)
            isArrayOfNullables(pattern) -> {
                ArraySchema().apply {
                    val typeAlias =
                        ((pattern as ListPattern).pattern as AnyPattern).pattern.first { !isEmptyOrNull(it) }.let {
                            if (it.pattern is String && builtInPatterns.contains(it.pattern.toString()))
                                it.pattern as String
                            else
                                it.typeAlias?.let { typeAlias ->
                                    if (!typeAlias.startsWith("("))
                                        "($typeAlias)"
                                    else
                                        typeAlias
                                } ?: throw ContractException("Unknown type: $it")
                        }

                    val arrayItemSchema = getSchemaType(typeAlias)

                    this.items = nullableSchemaAsOneOf(arrayItemSchema)
                }
            }
            isArrayOrNull(pattern) -> {
                ArraySchema().apply {
                    pattern as AnyPattern

                    this.items =
                        getSchemaType(pattern.pattern.first { !isEmptyOrNull(it) }.let {
                            listInnerTypeDescriptor(it as ListPattern)
                        })

                    this.nullable = true
                }
            }
            isNullableDeferred(pattern) -> {
                pattern as AnyPattern

                val innerPattern: Pattern = pattern.pattern.first { !isEmptyOrNull(it) }
                innerPattern as DeferredPattern

                val typeSchema = Schema<Any>().apply {
                    this.`$ref` = withoutPatternDelimiters(innerPattern.pattern)
                }

                nullableSchemaAsOneOf(typeSchema)
            }
            isNullable(pattern) -> {
                pattern as AnyPattern

                val innerPattern: Pattern = pattern.pattern.first { !isEmptyOrNull(it) }

                when {
                    innerPattern.pattern is String && innerPattern.pattern in builtInPatterns -> toOpenApiSchema(
                        builtInPatterns.getValue(innerPattern.pattern as String)
                    )
                    else -> toOpenApiSchema(innerPattern)
                }.apply {
                    this.nullable = true
                }
            }
            pattern is ListPattern -> {
                if (pattern.pattern is DeferredPattern) {
                    ArraySchema().apply {
                        this.items = getSchemaType(pattern.pattern.typeAlias)
                    }
                } else if (isArrayOfNullables(pattern)) {
                    ArraySchema().apply {
                        val innerPattern: Pattern = (pattern.pattern as AnyPattern).pattern.first { it !is NullPattern }
                        this.items = nullableSchemaAsOneOf(toOpenApiSchema(innerPattern))
                    }
                } else {
                    ArraySchema().apply {
                        this.items = toOpenApiSchema(pattern.pattern)
                    }
                }
            }
            pattern is NumberPattern || (pattern is DeferredPattern && pattern.pattern == "(number)") -> NumberSchema()
            pattern is NumberPattern || (pattern is DeferredPattern && pattern.pattern == "(integer)") -> IntegerSchema().apply { format = null }
            pattern is BooleanPattern || (pattern is DeferredPattern && pattern.pattern == "(boolean)") -> BooleanSchema()
            pattern is DateTimePattern || (pattern is DeferredPattern && pattern.pattern == "(datetime)") -> DateTimeSchema()
            pattern is DatePattern || (pattern is DeferredPattern && pattern.pattern == "(date)") -> DateSchema()
            pattern is UUIDPattern || (pattern is DeferredPattern && pattern.pattern == "(uuid)") -> UUIDSchema()
            pattern is StringPattern || pattern is EmptyStringPattern || (pattern is DeferredPattern && pattern.pattern == "(string)") || (pattern is DeferredPattern && pattern.pattern == "(emptystring)") -> StringSchema()
            pattern is NullPattern || (pattern is DeferredPattern && pattern.pattern == "(null)") -> Schema<Any>().apply {
                this.nullable = true
            }
            pattern is DeferredPattern -> Schema<Any>().apply {
                this.`$ref` = withoutPatternDelimiters(pattern.pattern)
            }
            pattern is JSONArrayPattern && pattern.pattern.isEmpty() ->
                ArraySchema().apply {
                    this.items = StringSchema()
                }
            pattern is JSONArrayPattern && pattern.pattern.isNotEmpty() -> {
                if (pattern.pattern.all { it == pattern.pattern.first() })
                    ArraySchema().apply {
                        this.items = toOpenApiSchema(pattern.pattern.first())
                    }
                else
                    throw ContractException("Conversion of raw JSON array type to OpenAPI is not supported. Change the contract spec to define a type and use (type*) instead of a JSON array.")
            }
            pattern is ExactValuePattern -> {
                toOpenApiSchema(pattern.pattern.type()).apply {
                    this.enum = listOf(pattern.pattern.toStringLiteral())
                }
            }
            pattern is PatternInStringPattern -> {
                StringSchema()
            }
            pattern is AnyPattern && pattern.pattern.map { it.javaClass }.distinct().size == 1 && pattern.pattern.filterIsInstance<ExactValuePattern>().map { it.pattern }.filterIsInstance<ScalarValue>().isNotEmpty() && pattern.pattern.first() is ExactValuePattern -> {
                val specmaticType = (pattern.pattern.first() as ExactValuePattern).pattern.type()
                val values = pattern.pattern.filterIsInstance<ExactValuePattern>().map { it.pattern }.filterIsInstance<ScalarValue>().map { it.nativeValue }

                toOpenApiSchema(specmaticType).also {
                    it.enum = values
                }
            }
            pattern is QueryParameterScalarPattern -> {
                toOpenApiSchema(pattern.pattern)
            }
            pattern is EnumPattern -> toOpenApiSchema(pattern.pattern)
            else -> TODO("Not supported: ${pattern.typeAlias ?: pattern.typeName}, ${pattern.javaClass.name}")
        }

        return schema as Schema<Any>
    }

    private fun nullableSchemaAsOneOf(typeSchema: Schema<Any>): ComposedSchema {
        val nullableSchema = Schema<Any>().apply {
            this.nullable = true
            this.properties = emptyMap()
        }

        return ComposedSchema().apply {
            this.oneOf = listOf(nullableSchema, typeSchema)
        }
    }

    private fun listInnerTypeDescriptor(it: ListPattern): String {
        return it.pattern.typeAlias
            ?: when (val innerPattern = it.pattern.pattern) {
                is String -> innerPattern
                else -> throw ContractException("Type alias not found for type ${it.typeName}")
            }
    }

    private fun isNullableDeferred(pattern: Pattern): Boolean {
        return isNullable(pattern) && pattern is AnyPattern && pattern.pattern.first { it.pattern != "(empty)" && it.pattern != "(null)" }
            .let {
                it is DeferredPattern && withPatternDelimiters(
                    withoutPatternDelimiters(it.pattern).removeSuffix("*").removeSuffix("?").removeSuffix("*")
                ) !in builtInPatterns
            }
    }

    private fun getSchemaType(type: String): Schema<Any> {
        return if (builtInPatterns.contains(type)) {
            toOpenApiSchema(builtInPatterns.getValue(type))
        }
        else {
            val cleanedUpType = withoutPatternDelimiters(type)

            Schema<Any>().also { it.`$ref` = cleanedUpType }
        }
    }

    private fun isArrayOrNull(pattern: Pattern): Boolean =
        isNullable(pattern) && pattern is AnyPattern && pattern.pattern.first { !isEmptyOrNull(it) } is ListPattern

    private fun isArrayOfNullables(pattern: Pattern) =
        pattern is ListPattern && pattern.pattern is AnyPattern && isNullable(pattern.pattern)

    private fun isEmptyOrNull(pattern: Pattern): Boolean {
        return when (pattern) {
            is DeferredPattern -> pattern.typeAlias in listOf("(empty)", "(null)")
            is LookupRowPattern -> isEmptyOrNull(pattern.pattern)
            else -> pattern in listOf(EmptyStringPattern, NullPattern)
        }
    }

    private fun isNullable(pattern: Pattern) =
        pattern is AnyPattern && pattern.pattern.any { isEmptyOrNull(it) }

    private fun jsonObjectToSchema(pattern: JSONObjectPattern): Schema<Any> = jsonToSchema(pattern.pattern)
    private fun tabularToSchema(pattern: TabularPattern): Schema<Any> = jsonToSchema(pattern.pattern)

    private fun jsonToSchema(pattern: Map<String, Pattern>): Schema<Any> {
        val schema = Schema<Any>()
        schema.type = OBJECT_TYPE

        schema.required = pattern.keys.filterNot { it.endsWith("?") }

        val properties: Map<String, Schema<Any>> = pattern.mapValues { (_, valueType) ->
            toOpenApiSchema(valueType)
        }.mapKeys { withoutOptionality(it.key) }

        schema.properties = properties

        return schema
    }

    private fun useExamples(externalisedJSONExamples: Map<OpenApiSpecification.OperationIdentifier, List<Row>>): Feature {
        val scenariosWithExamples: List<Scenario> = scenarios.map {
            it.useExamples(externalisedJSONExamples)
        }

        return this.copy(scenarios = scenariosWithExamples)
    }

    private fun loadExternalisedJSONExamples(testsDirectory: File?): Map<OpenApiSpecification.OperationIdentifier, List<Row>> {
        if (testsDirectory == null)
            return emptyMap()

        if (!testsDirectory.exists())
            return emptyMap()

        val files = testsDirectory.walk().filterNot { it.isDirectory }.filter {
            it.extension == "json"
        }.toList().sortedBy { it.name }

        if (files.isEmpty()) return emptyMap()

        val examplesInSubdirectories: Map<OpenApiSpecification.OperationIdentifier, List<Row>> =
            files.filter {
                it.isDirectory
            }.fold(emptyMap()) { acc, item ->
                acc + loadExternalisedJSONExamples(item)
            }

        logger.log("Loading externalised examples in ${testsDirectory.path}: ")
        logger.boundary()

        return examplesInSubdirectories + files.asSequence().filterNot {
            it.isDirectory
        }.mapNotNull { example ->
            runCatching { ExampleFromFile(example) }.mapCatching { exampleFromFile ->
                with(exampleFromFile) {
                    OpenApiSpecification.OperationIdentifier(
                        requestMethod = requestMethod.orEmpty(),
                        requestPath = requestPath.orEmpty(),
                        responseStatus = responseStatus ?: 0,
                        requestContentType = requestContentType,
                        responseContentType = responseContentType
                    ) to toRow(specmaticConfig)
                }
            }.getOrElse { e ->
                logger.log("Could not load test file ${example.canonicalPath}")
                logger.log(e)
                logger.boundary()
                if (strictMode) throw ContractException(exceptionCauseMessage(e))
                null
            }
        }
        .groupBy { (operationIdentifier, _) -> operationIdentifier }
        .mapValues { (_, value) -> value.map { it.second } }
    }

    fun loadExternalisedExamplesAndListUnloadableExamples(): Pair<Feature, Set<String>> {
        val testsDirectory = getTestsDirectory(File(this.path))
        val externalisedExamplesFromDefaultDirectory = loadExternalisedJSONExamples(testsDirectory)
        val externalisedExampleDirsFromConfig = specmaticConfig.getExamples()

        val externalisedExamplesFromExampleDirs = externalisedExampleDirsFromConfig.flatMap { directory ->
            loadExternalisedJSONExamples(File(directory)).entries
        }.associate { it.toPair() }

        val allExternalisedJSONExamples = externalisedExamplesFromDefaultDirectory + externalisedExamplesFromExampleDirs

        if(allExternalisedJSONExamples.isEmpty())
            return this to emptySet()

        val featureWithExternalisedExamples = useExamples(allExternalisedJSONExamples)

        val externalizedExampleFilePaths =
            allExternalisedJSONExamples.entries.flatMap { (_, rows) ->
                rows.map {
                    it.fileSource
                }
            }.filterNotNull().sorted().toSet()

        val utilizedFileSources =
            featureWithExternalisedExamples.scenarios.asSequence().flatMap { scenarioInfo ->
                scenarioInfo.examples.flatMap { examples ->
                    examples.rows.map {
                        it.fileSource
                    }
                }
            }.filterNotNull()
                .sorted().toSet()

        val unusedExternalizedExamples = (externalizedExampleFilePaths - utilizedFileSources)
        if (unusedExternalizedExamples.isNotEmpty()) {
            println()
            logger.log("The following externalized examples were not used:")

            val errorMessages = unusedExternalizedExamples.sorted().map { externalizedExamplePath: String ->
                if(strictMode.not()) logger.log("  $externalizedExamplePath")

                try {
                    val example = ScenarioStub.parse(File(externalizedExamplePath).readText())

                    val method = example.requestMethod()
                    val path = example.requestPath()
                    val responseCode = example.responseStatus()
                    val errorMessage = "    $method $path -> $responseCode does not match any operation in the specification"
                    if(strictMode.not()) logger.log(errorMessage)
                    "The example $externalizedExamplePath is unused due to error: $errorMessage"
                } catch(e: Throwable) {
                    val errorMessage = "    Could not parse the example: ${exceptionCauseMessage(e)}"
                    if(strictMode.not()) logger.log(errorMessage)
                    "The example $externalizedExamplePath is unused due to error: $errorMessage"
                }
            }
            if(strictMode && errorMessages.isNotEmpty()) {
                throw ContractException(errorMessages.joinToString(System.lineSeparator()))
            }

            logger.newLine()
        }

        return featureWithExternalisedExamples to unusedExternalizedExamples
    }

    fun loadExternalisedExamples(): Feature {
        return loadExternalisedExamplesAndListUnloadableExamples().first
    }

    fun validateExamplesOrException(disallowExtraHeaders: Boolean = true) {
        val errors = scenarios.mapNotNull { scenario ->
            try {
                scenario.validExamplesOrException(flagsBased.copy(generation = NonGenerativeTests), disallowExtraHeaders)
                null
            } catch (e: Throwable) {
                exceptionCauseMessage(e)
            }
        }

        if(errors.isNotEmpty())
            throw ContractException(errors.joinToString("${System.lineSeparator()}${System.lineSeparator()}"))
    }

    private fun<T> List<DiscriminatorBasedItem<T>>.containsDiscriminatorValueAs(
        discriminatorValue: String
    ): Boolean {
        return this.any { it.discriminatorValue == discriminatorValue }
    }

    private fun <T> List<DiscriminatorBasedItem<T>>.getDiscriminatorItemWith(
        discriminatorValue: String
    ): DiscriminatorBasedItem<T> {
        return this.first { it.discriminatorValue == discriminatorValue }
    }


    companion object {
        private const val OBJECT_TYPE = "object"
        private val OPENAPI_MAP_KEY_NEGATED_PATTERN = Regex("[^a-zA-Z0-9._-]")

        private fun getTestsDirectory(contractFile: File): File? {
            val testDirectory = testDirectoryFileFromSpecificationPath(contractFile.path) ?: testDirectoryFileFromEnvironmentVariable()

            return when {
                testDirectory?.exists() == true -> {
                    logger.log("Test directory ${testDirectory.canonicalPath} found")
                    testDirectory
                }

                else -> {
                    null
                }
            }
        }

        private fun testDirectoryFileFromEnvironmentVariable(): File? {
            return readEnvVarOrProperty(testDirectoryEnvironmentVariable, testDirectoryProperty)?.let {
                File(System.getenv(testDirectoryEnvironmentVariable))
            }
        }

        private fun testDirectoryFileFromSpecificationPath(openApiFilePath: String): File? {
            if (openApiFilePath.isBlank())
                return null

            return examplesDirFor(openApiFilePath, TEST_DIR_SUFFIX)
        }

        fun from(
            scenarios: List<Scenario> = emptyList(),
            serverState: Map<String, Value> = emptyMap(),
            name: String,
            testVariables: Map<String, String> = emptyMap(),
            testBaseURLs: Map<String, String> = emptyMap(),
            path: String = "",
            sourceProvider:String? = null,
            sourceRepository:String? = null,
            sourceRepositoryBranch:String? = null,
            specification:String? = null,
            serviceType:String? = null,
            stubsFromExamples: Map<String, List<Pair<HttpRequest, HttpResponse>>> = emptyMap(),
            specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
            flagsBased: FlagsBased = strategiesFromFlags(specmaticConfig),
            strictMode: Boolean = false
        ): Feature {
            return Feature(
                scenarios = scenarios,
                serverState = serverState,
                name = name,
                testVariables = testVariables,
                testBaseURLs = testBaseURLs,
                path = path,
                sourceProvider = sourceProvider,
                sourceRepository = sourceRepository,
                sourceRepositoryBranch = sourceRepositoryBranch,
                specification = specification,
                serviceType = serviceType,
                specmaticConfig = specmaticConfig,
                flagsBased = flagsBased,
                strictMode = strictMode,
                exampleStore = ExampleStore.from(stubsFromExamples, type = ExampleType.INLINE)
            )
        }
    }
}

class EmptyContract : Throwable()

private fun toFixtureInfo(rest: String): Pair<String, Value> {
    val fixtureTokens = breakIntoPartsMaxLength(rest.trim(), 2)

    if (fixtureTokens.size != 2)
        throw ContractException("Couldn't parse fixture data: $rest")

    return Pair(fixtureTokens[0], toFixtureData(fixtureTokens[1]))
}

private fun toFixtureData(rawData: String): Value = parsedJSON(rawData)

internal fun stringOrDocString(string: String?, step: StepInfo): String {
    val trimmed = string?.trim() ?: ""
    return trimmed.ifEmpty { step.docString }
}

private fun toPatternInfo(step: StepInfo, rowsList: List<TableRow>, isWSDL: Boolean = false): Pair<String, Pattern> {
    val tokens = breakIntoPartsMaxLength(step.rest, 2)

    val patternName = withPatternDelimiters(tokens[0])

    val patternDefinition = stringOrDocString(tokens.getOrNull(1), step)

    val pattern = when {
        patternDefinition.isEmpty() -> rowsToTabularPattern(rowsList, typeAlias = patternName)
        else -> parsedPattern(patternDefinition, typeAlias = patternName, isWSDL = isWSDL)
    }

    return Pair(patternName, pattern)
}

private fun toFacts(rest: String, fixtures: Map<String, Value>): Map<String, Value> {
    return try {
        jsonStringToValueMap(rest)
    } catch (notValidJSON: Throwable) {
        val factTokens = breakIntoPartsMaxLength(rest, 2)
        val name = factTokens[0]
        val data = factTokens.getOrNull(1)?.let { StringValue(it) } ?: fixtures.getOrDefault(name, True)

        mapOf(name to data)
    }
}

private fun lexScenario(
    steps: List<Step>,
    examplesList: List<Examples>,
    featureTags: List<Tag>,
    backgroundScenarioInfo: ScenarioInfo?,
    filePath: String,
    includedSpecifications: List<IncludedSpecification?>,
    isWSDL: Boolean,
): ScenarioInfo {
    val filteredSteps =
        steps.map { step -> StepInfo(step.text, listOfDatatableRows(step), step) }.filterNot { it.isEmpty }

    val parsedScenarioInfo = filteredSteps.fold(backgroundScenarioInfo ?: ScenarioInfo(httpRequestPattern = HttpRequestPattern())) { scenarioInfo, step ->
        when (step.keyword) {
            in HTTP_METHODS -> {
                step.words.getOrNull(1)?.let {
                    val urlInSpec = step.rest
                    val pathParamPattern = try {
                        buildHttpPathPattern(URI.create(urlInSpec))
                    } catch (e: Throwable) {
                        throw Exception(
                            "Could not parse the contract URL \"${step.rest}\" in scenario \"${scenarioInfo.scenarioName}\"",
                            e
                        )
                    }

                    val queryParamPattern = buildQueryPattern(URI.create(urlInSpec))

                    scenarioInfo.copy(
                        httpRequestPattern = scenarioInfo.httpRequestPattern.copy(
                            httpPathPattern = pathParamPattern,
                            httpQueryParamPattern = queryParamPattern,
                            method = step.keyword.uppercase()
                        )
                    )
                } ?: throw ContractException("Line ${step.line}: $step.text")
            }
            "REQUEST-HEADER" ->
                scenarioInfo.copy(
                    httpRequestPattern = scenarioInfo.httpRequestPattern.copy(
                        headersPattern = plusHeaderPattern(
                            step.rest,
                            scenarioInfo.httpRequestPattern.headersPattern
                        )
                    )
                )
            "RESPONSE-HEADER" ->
                scenarioInfo.copy(
                    httpResponsePattern = scenarioInfo.httpResponsePattern.copy(
                        headersPattern = plusHeaderPattern(
                            step.rest,
                            scenarioInfo.httpResponsePattern.headersPattern
                        )
                    )
                )
            "STATUS" ->
                scenarioInfo.copy(
                    httpResponsePattern = scenarioInfo.httpResponsePattern.copy(
                        status = Integer.valueOf(
                            step.rest
                        )
                    )
                )
            "REQUEST-BODY" ->
                scenarioInfo.copy(httpRequestPattern = scenarioInfo.httpRequestPattern.copy(body = toPattern(step, isWSDL)))
            "RESPONSE-BODY" ->
                scenarioInfo.copy(httpResponsePattern = scenarioInfo.httpResponsePattern.bodyPattern(toPattern(step, isWSDL)))
            "FACT" ->
                scenarioInfo.copy(
                    expectedServerState = scenarioInfo.expectedServerState.plus(
                        toFacts(
                            step.rest,
                            scenarioInfo.fixtures
                        )
                    )
                )
            "TYPE", "PATTERN", "JSON" ->
                scenarioInfo.copy(patterns = scenarioInfo.patterns.plus(toPatternInfo(step, step.rowsList, isWSDL)))
            "ENUM" ->
                scenarioInfo.copy(patterns = scenarioInfo.patterns.plus(parseEnum(step)))
            "FIXTURE" ->
                scenarioInfo.copy(fixtures = scenarioInfo.fixtures.plus(toFixtureInfo(step.rest)))
            "FORM-FIELD" ->
                scenarioInfo.copy(
                    httpRequestPattern = scenarioInfo.httpRequestPattern.copy(
                        formFieldsPattern = plusFormFields(
                            scenarioInfo.httpRequestPattern.formFieldsPattern,
                            step.rest,
                            step.rowsList
                        )
                    )
                )
            "REQUEST-PART" ->
                scenarioInfo.copy(
                    httpRequestPattern = scenarioInfo.httpRequestPattern.copy(
                        multiPartFormDataPattern = scenarioInfo.httpRequestPattern.multiPartFormDataPattern.plus(
                            toFormDataPart(step, filePath)
                        )
                    )
                )
            "VALUE" ->
                scenarioInfo.copy(
                    references = values(
                        step.rest,
                        scenarioInfo.references,
                        backgroundScenarioInfo?.references ?: emptyMap(),
                        filePath
                    )
                )
            "EXPORT" ->
                scenarioInfo.copy(
                    bindings = setters(
                        step.rest,
                        backgroundScenarioInfo?.bindings ?: emptyMap(),
                        scenarioInfo.bindings
                    )
                )
            else -> {
                val location = when (step.raw.location) {
                    null -> ""
                    else -> " at line ${step.raw.location.line}"
                }

                throw ContractException("""Invalid syntax$location: ${step.raw.keyword.trim()} ${step.raw.text} -> keyword "${step.originalKeyword}" not recognised.""")
            }
        }
    }

    val tags = featureTags.map { tag -> tag.name }
    val ignoreFailure = when {
        tags.asSequence().map { it.uppercase() }.contains("@WIP") -> true
        else -> false
    }

    val scenarioInfo = if (includedSpecifications.isEmpty() || backgroundScenarioInfo == null) {
        scenarioInfoWithExamples(
            parsedScenarioInfo,
            backgroundScenarioInfo ?: ScenarioInfo(),
            examplesList,
            ignoreFailure
        )
    } else {
        val matchingScenarios: List<ScenarioInfo> = includedSpecifications.mapNotNull {
            it?.matches(parsedScenarioInfo, steps).orEmpty()
        }.flatten()

        if (matchingScenarios.size > 1) throw ContractException("Scenario: ${parsedScenarioInfo.scenarioName} is not specific, it matches ${matchingScenarios.size} in the included Wsdl / OpenApi")

        val matchingScenario = matchingScenarios.first().copy(bindings = parsedScenarioInfo.bindings)

        scenarioInfoWithExamples(matchingScenario, backgroundScenarioInfo, examplesList, ignoreFailure)
    }

    return scenarioInfo.copy(isGherkinScenario = true, specification = scenarioInfo.specification ?: filePath)
}

private fun listOfDatatableRows(it: Step): MutableList<TableRow> = it.dataTable.getOrNull()?.rows.orEmpty().toMutableList()

fun parseEnum(step: StepInfo): Pair<String, Pattern> {
    val tokens = step.text.split(" ")

    if (tokens.size < 5)
        throw ContractException("Enum syntax error in step at line ${step.raw.location.line}. Syntax should be Given(/When/Then) enum EnumName <TypeName> values choice1,choice2,choice3")
    val enumName = tokens[1]
    val enumValues = tokens[4].split(",")
    val enumType = tokens[2]
    val exactValuePatterns = enumValues.map { enumValue ->
        val enumPattern = parsedPattern(enumType).run {
            when (this) {
                is DeferredPattern -> this.resolvePattern(Resolver())
                is AnyPattern -> throw ContractException("Enums $enumName type $enumType cannot be nullable. To mark the enum nullable please use it with nullable syntax. Suggested Usage: (${enumName}?)")
                else -> this
            }
        }
        ExactValuePattern(
            when (enumPattern) {
                is StringPattern -> StringValue(enumValue)
                is NumberPattern -> NumberValue(enumValue.toInt())
                else -> throw ContractException("Enums can only be of type String or Number")
            }
        )
    }
    return Pair(
        "($enumName)",
        AnyPattern(exactValuePatterns, extensions = exactValuePatterns.extractCombinedExtensions())
    )
}

private fun scenarioInfoWithExamples(
    parsedScenarioInfo: ScenarioInfo,
    backgroundScenarioInfo: ScenarioInfo,
    examplesList: List<Examples>,
    ignoreFailure: Boolean
) = parsedScenarioInfo.copy(
    examples = backgroundScenarioInfo.examples.plus(examplesFrom(examplesList)),
    bindings = backgroundScenarioInfo.bindings.plus(parsedScenarioInfo.bindings),
    references = backgroundScenarioInfo.references.plus(parsedScenarioInfo.references),
    ignoreFailure = ignoreFailure
)

fun setters(
    rest: String,
    backgroundSetters: Map<String, String>,
    scenarioSetters: Map<String, String>
): Map<String, String> {
    val parts = breakIntoPartsMaxLength(rest, 3)

    if (parts.size != 3 || parts[1] != "=")
        throw ContractException("Setter syntax is incorrect in \"$rest\". Syntax should be \"Then set <variable> = <selector>\"")

    val variableName = parts[0]
    val selector = parts[2]

    return backgroundSetters.plus(scenarioSetters).plus(variableName to selector)
}

fun values(
    rest: String,
    scenarioReferences: Map<String, References>,
    backgroundReferences: Map<String, References>,
    filePath: String
): Map<String, References> {
    val parts = breakIntoPartsMaxLength(rest, 3)

    if (parts.size != 3 || parts[1] != "from")
        throw ContractException("Incorrect syntax for value statement: $rest - it should be \"Given value <value name> from <$APPLICATION_NAME file name>\"")

    val valueStoreName = parts[0]
    val specFileName = parts[2]

    val specFilePath = ContractFileWithExports(specFileName, AnchorFile(filePath))

    return backgroundReferences.plus(scenarioReferences).plus(
        valueStoreName to References(
            valueStoreName,
            specFilePath,
            contractCache = contractCache
        )
    )
}

private val contractCache = ContractCache()

fun toFormDataPart(step: StepInfo, contractFilePath: String): MultiPartFormDataPattern {
    val parts = breakIntoPartsMaxLength(step.rest, 4)

    if (parts.size < 2)
        throw ContractException("There must be at least 2 words after request-part in $step.line")

    val (name, content) = parts.slice(0..1)

    return when {
        content.startsWith("@") -> {
            val contentType = parts.getOrNull(2)
            val contentEncoding = parts.getOrNull(3)

            val multipartFilename = content.removePrefix("@")

            val expandedFilenamePattern = when (val filenamePattern = parsedPattern(multipartFilename)) {
                is ExactValuePattern -> {
                    val multipartFilePath =
                        File(contractFilePath).absoluteFile.parentFile.resolve(multipartFilename).absolutePath
                    ExactValuePattern(StringValue(multipartFilePath))
                }
                else ->
                    filenamePattern
            }

            MultiPartFilePattern(name, expandedFilenamePattern, contentType, contentEncoding)
        }
        isPatternToken(content) -> {
            MultiPartContentPattern(name, parsedPattern(content))
        }
        else -> {
            MultiPartContentPattern(name, parsedPattern(content.trim()))
//            MultiPartContentPattern(name, ExactValuePattern(parsedValue(content)))
        }
    }
}

fun toPattern(step: StepInfo, isWSDL: Boolean = false): Pattern {
    return when (val stringData = stringOrDocString(step.rest, step)) {
        "" -> {
            if (step.rowsList.isEmpty()) throw ContractException("Not enough information to describe a type in $step")
            rowsToTabularPattern(step.rowsList)
        }
        else -> parsedPattern(stringData, isWSDL = isWSDL)
    }
}

fun plusFormFields(
    formFields: Map<String, Pattern>,
    rest: String,
    rowsList: List<TableRow>
): Map<String, Pattern> =
    formFields.plus(when (rowsList.size) {
        0 -> toQueryParams(rest).map { (key, value) -> key to value }
        else -> rowsList.map { row -> row.cells[0].value to row.cells[1].value }
    }.associate { (key, value) -> key to parsedPattern(value) }
    )

private fun toQueryParams(rest: String) = rest.split("&")
    .map { breakIntoPartsMaxLength(it, 2) }

fun plusHeaderPattern(rest: String, headersPattern: HttpHeadersPattern): HttpHeadersPattern {
    val parts = breakIntoPartsMaxLength(rest, 2)

    return when (parts.size) {
        2 -> headersPattern.copy(pattern = headersPattern.pattern.plus(toPatternPair(parts[0], parts[1])))
        1 -> throw ContractException("Header $parts[0] should have a value")
        else -> throw ContractException("Unrecognised header params $rest")
    }
}

fun toPatternPair(key: String, value: String): Pair<String, Pattern> = key to parsedPattern(value)

fun breakIntoPartsMaxLength(whole: String, partCount: Int) = whole.split("\\s+".toRegex(), partCount)
fun breakIntoPartsMaxLength(whole: String, separator: String, partCount: Int) =
    whole.split(separator.toRegex(), partCount)

private val HTTP_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

internal fun parseGherkinString(gherkinData: String, sourceFilePath: String): GherkinDocument {
    val parser = GherkinParser.builder().includeSource(false).includePickles(false).build()
    val envelope = Envelope.of(Source(sourceFilePath, gherkinData, SourceMediaType.TEXT_X_CUCUMBER_GHERKIN_PLAIN))

    val gherkinDocument = parser.parse(envelope).findFirst().getOrNull()?.gherkinDocument?.getOrNull()
    return gherkinDocument ?: throw ContractException("There was no contract in the file $sourceFilePath.")
}

internal fun lex(gherkinDocument: GherkinDocument, filePath: String = "", isWSDL: Boolean = false): Pair<String, List<Scenario>> {
    val feature = gherkinDocument.unwrapFeature()
    return Pair(feature.name, lex(feature.children, filePath, isWSDL))
}

internal fun lex(featureChildren: List<FeatureChild>, filePath: String, isWSDL: Boolean = false): List<Scenario> {
    return scenarioInfos(featureChildren, filePath, isWSDL).map { scenarioInfo -> Scenario(scenarioInfo) }
}

fun scenarioInfos(
    featureChildren: List<FeatureChild>,
    filePath: String,
    isWSDL: Boolean = false,
): List<ScenarioInfo> {
    val openApiSpecification =
        toIncludedSpecification(featureChildren, { backgroundOpenApi(it) }) {
            OpenApiSpecification.fromFile(
                it,
                filePath
            )
        }

    val wsdlSpecification =
        toIncludedSpecification(featureChildren, { backgroundWsdl(it) }) { WsdlSpecification(WSDLFile(it)) }

    val includedSpecifications = listOfNotNull(openApiSpecification, wsdlSpecification)

    val scenarioInfosBelongingToIncludedSpecifications =
        includedSpecifications.map { it.toScenarioInfos().first }.flatten()

    val backgroundInfo = backgroundScenario(featureChildren)?.let { feature ->
        lexScenario(
            feature.unwrapBackground().steps
                .filter { !it.text.contains("openapi", true) }
                .filter { !it.text.contains("wsdl", true) },
            listOf(),
            emptyList(),
            null,
            filePath,
            includedSpecifications,
            isWSDL
        )
    } ?: ScenarioInfo()

    val specmaticScenarioInfos = scenarios(featureChildren).map { featureChild ->
        val scenario = featureChild.scenario.orElseThrow {
            IllegalStateException("Expected a Scenario in FeatureChild, but none was present.")
        }

        if (scenario.name.isBlank() && openApiSpecification == null && wsdlSpecification == null)
            throw ContractException("Error at line ${scenario.location.line}: scenario name must not be empty")

        val backgroundInfoCopy = backgroundInfo.copy(scenarioName = scenario.name)
        lexScenario(
            scenario.steps,
            scenario.examples,
            scenario.tags,
            backgroundInfoCopy,
            filePath,
            includedSpecifications,
            isWSDL,
        )
    }

    return specmaticScenarioInfos.plus(scenarioInfosBelongingToIncludedSpecifications.filter { scenarioInfo ->
        specmaticScenarioInfos.none {
            it.httpResponsePattern.status == scenarioInfo.httpResponsePattern.status
                    && it.httpRequestPattern.matchesSignature(scenarioInfo.httpRequestPattern)
        }
    })
}

private fun toIncludedSpecification(
    featureChildren: List<FeatureChild>,
    selector: (List<FeatureChild>) -> Step?,
    creator: (String) -> IncludedSpecification
): IncludedSpecification? =
    selector(featureChildren)?.run { creator(text.split(" ")[1]) }

private fun backgroundScenario(featureChildren: List<FeatureChild>) = featureChildren.firstOrNull { it.background.isPresent }

private fun backgroundOpenApi(featureChildren: List<FeatureChild>): Step? {
    return backgroundScenario(featureChildren)?.let {
        it.unwrapBackground().steps.firstOrNull { step ->
            step.keyword.contains("Given", true) && step.text.contains("openapi", true)
        }
    }
}

private fun backgroundWsdl(featureChildren: List<FeatureChild>): Step? {
    return backgroundScenario(featureChildren)?.let {
        it.unwrapBackground().steps.firstOrNull { step ->
            step.keyword.contains("Given", true) && step.text.contains("wsdl", true)
        }
    }
}

private fun scenarios(featureChildren: List<FeatureChild>) = featureChildren.filter { it.background.isEmpty }

fun toGherkinFeature(stub: NamedStub): String = toGherkinFeature("New Feature", listOf(stub))

private fun stubToClauses(namedStub: NamedStub): Pair<List<GherkinClause>, ExampleDeclarations> {
    val (requestClauses, typesFromRequest, examples) = toGherkinClauses(namedStub.stub.request)

    for (message in examples.messages) {
        logger.log(message)
    }

    val (responseClauses, allTypes, _) = toGherkinClauses(namedStub.stub.response, typesFromRequest)
    val typeClauses = toGherkinClauses(allTypes)
    return Pair(typeClauses.plus(requestClauses).plus(responseClauses), examples)
}

data class GherkinScenario(val scenarioName: String, val clauses: List<GherkinClause>)

fun toGherkinFeature(featureName: String, stubs: List<NamedStub>): String {
    val groupedStubs = stubs.map { stub ->
        val (clauses, examples) = stubToClauses(stub)
        val commentedExamples = addCommentsToExamples(examples, stub)

        Pair(GherkinScenario(stub.name, clauses), listOf(commentedExamples))
    }.fold(emptyMap<GherkinScenario, List<ExampleDeclarations>>()) { groups, (scenario, examples) ->
        groups.plus(scenario to groups.getOrDefault(scenario, emptyList()).plus(examples))
    }

    val scenarioStrings = groupedStubs.map { (nameAndClauses, examplesList) ->
        val (name, clauses) = nameAndClauses

        toGherkinScenario(name, clauses, examplesList)
    }

    return withFeatureClause(featureName, scenarioStrings.joinToString("\n\n"))
}

private fun addCommentsToExamples(examples: ExampleDeclarations, stub: NamedStub): ExampleDeclarations {
    val date = stub.stub.response.headers["Date"]
    return examples.withComment(date)
}

private fun List<String>.second(): String {
    return this[1]
}

fun similarURLPath(baseScenario: Scenario, newScenario: Scenario): Boolean {
    val encompassResult = baseScenario.httpRequestPattern.httpPathPattern?.encompasses(
        newScenario.httpRequestPattern.httpPathPattern!!,
        baseScenario.resolver,
        newScenario.resolver
    )
    if (encompassResult is Success) return true

    val normalizedBasePattern = OpenApiPath.from(baseScenario.httpRequestPattern.httpPathPattern?.path.orEmpty()).normalize().toHttpPathPattern()
    val normalizedNewPattern = OpenApiPath.from(newScenario.httpRequestPattern.httpPathPattern?.path.orEmpty()).normalize().toHttpPathPattern()
    return normalizedBasePattern.encompasses(normalizedNewPattern, baseScenario.resolver, newScenario.resolver) is Success
}

data class DiscriminatorBasedRequestResponse(
    val request: HttpRequest,
    val response: HttpResponse,
    val requestDiscriminator: DiscriminatorMetadata,
    val responseDiscriminator: DiscriminatorMetadata,
    val scenarioValue: HasValue<Scenario>
)
