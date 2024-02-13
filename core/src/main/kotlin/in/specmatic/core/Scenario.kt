package `in`.specmatic.core

import `in`.specmatic.conversions.OpenApiSpecification
import `in`.specmatic.core.log.logger
import `in`.specmatic.core.pattern.*
import `in`.specmatic.core.utilities.capitalizeFirstChar
import `in`.specmatic.core.utilities.exceptionCauseMessage
import `in`.specmatic.core.utilities.mapZip
import `in`.specmatic.core.value.*
import `in`.specmatic.test.ContractTest
import `in`.specmatic.test.ScenarioTest
import `in`.specmatic.test.ScenarioTestGenerationFailure
import `in`.specmatic.test.TestExecutor

object ContractAndStubMismatchMessages : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Contract expected $expected but stub contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.lowercase().capitalizeFirstChar()} named $keyName in the stub was not in the contract"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "${keyLabel.lowercase().capitalizeFirstChar()} named $keyName in the contract was not found in the stub"
    }
}

interface ScenarioDetailsForResult {
    val status: Int
    val ignoreFailure: Boolean
    val name: String
    val method: String
    val path: String

    fun testDescription(): String
}

data class Scenario(
    override val name: String,
    val httpRequestPattern: HttpRequestPattern,
    val httpResponsePattern: HttpResponsePattern,
    val expectedFacts: Map<String, Value>,
    val examples: List<Examples>,
    val patterns: Map<String, Pattern>,
    val fixtures: Map<String, Value>,
    override val ignoreFailure: Boolean = false,
    val references: Map<String, References> = emptyMap(),
    val bindings: Map<String, String> = emptyMap(),
    val isGherkinScenario: Boolean = false,
    val isNegative: Boolean = false,
    val badRequestOrDefault: BadRequestOrDefault? = null,
    val exampleName: String? = null,
    val generatedFromExamples: Boolean = examples.isNotEmpty(),
    val sourceProvider:String? = null,
    val sourceRepository:String? = null,
    val sourceRepositoryBranch:String? = null,
    val specification:String? = null,
    val serviceType:String? = null,
    val generativePrefix: String = "",
    val statusInDescription: String = httpResponsePattern.status.toString(),
    val disambiguate: () -> String = { "" }
): ScenarioDetailsForResult {
    constructor(scenarioInfo: ScenarioInfo) : this(
        scenarioInfo.scenarioName,
        scenarioInfo.httpRequestPattern,
        scenarioInfo.httpResponsePattern,
        scenarioInfo.expectedServerState,
        scenarioInfo.examples,
        scenarioInfo.patterns,
        scenarioInfo.fixtures,
        scenarioInfo.ignoreFailure,
        scenarioInfo.references,
        scenarioInfo.bindings,
        sourceProvider = scenarioInfo.sourceProvider,
        sourceRepository = scenarioInfo.sourceRepository,
        sourceRepositoryBranch = scenarioInfo.sourceRepositoryBranch,
        specification = scenarioInfo.specification,
        serviceType = scenarioInfo.serviceType
    )

    val apiIdentifier: String
        get() = "$method $path $status"

    override val method: String
        get() {
            return httpRequestPattern.method ?: ""
        }

    override val path: String
        get() {
            return httpRequestPattern.httpPathPattern?.path ?: ""
        }

    override val status: Int
        get() {
            return if(isNegative) 400 else httpResponsePattern.status
        }

    private fun serverStateMatches(actualState: Map<String, Value>, resolver: Resolver) =
        expectedFacts.keys == actualState.keys &&
                mapZip(expectedFacts, actualState).all { (key, expectedStateValue, actualStateValue) ->
                    when {
                        actualStateValue == True || expectedStateValue == True -> true
                        expectedStateValue is StringValue && expectedStateValue.isPatternToken() -> {
                            val pattern = resolver.getPattern(expectedStateValue.string)
                            try {
                                resolver.matchesPattern(
                                    key,
                                    pattern,
                                    pattern.parse(actualStateValue.toString(), resolver)
                                ).isSuccess()
                            } catch (e: Exception) {
                                false
                            }
                        }
                        else -> expectedStateValue.toStringLiteral() == actualStateValue.toStringLiteral()
                    }
                }

    fun matches(
        httpRequest: HttpRequest,
        serverState: Map<String, Value>,
        mismatchMessages: MismatchMessages = DefaultMismatchMessages,
        unexpectedKeyCheck: UnexpectedKeyCheck? = null
    ): Result {
        val resolver = Resolver(serverState, false, patterns).copy(mismatchMessages = mismatchMessages).let {
            if(unexpectedKeyCheck != null) {
                val keyCheck = it.findKeyErrorCheck
                it.copy(findKeyErrorCheck = keyCheck.copy(unexpectedKeyCheck = unexpectedKeyCheck))
            }
            else
                it
        }
        return matches(httpRequest, serverState, resolver, resolver)
    }

    fun matchesStub(
        httpRequest: HttpRequest,
        serverState: Map<String, Value>,
        mismatchMessages: MismatchMessages = DefaultMismatchMessages
    ): Result {
        val headersResolver = Resolver(serverState, false, patterns).copy(mismatchMessages = mismatchMessages)
        val nonHeadersResolver = headersResolver.disableOverrideUnexpectedKeycheck()

        return matches(httpRequest, serverState, nonHeadersResolver, headersResolver)
    }

    private fun matches(
        httpRequest: HttpRequest,
        serverState: Map<String, Value>,
        resolver: Resolver,
        headersResolver: Resolver
    ): Result {
        if (!serverStateMatches(serverState, resolver)) {
            return Result.Failure("Facts mismatch", breadCrumb = "FACTS").also { it.updateScenario(this) }
        }
        return httpRequestPattern.matches(httpRequest, resolver, headersResolver).also {
            it.updateScenario(this)
        }
    }

    fun generateHttpResponse(actualFacts: Map<String, Value>): HttpResponse =
        scenarioBreadCrumb(this) {
            Resolver(emptyMap(), false, patterns)
            val resolver = Resolver(actualFacts, false, patterns)
            val facts = combineFacts(expectedFacts, actualFacts, resolver)

            httpResponsePattern.generateResponse(resolver.copy(factStore = CheckFacts(facts)))
        }

    private fun combineFacts(
        expected: Map<String, Value>,
        actual: Map<String, Value>,
        resolver: Resolver
    ): Map<String, Value> {
        val combinedServerState = HashMap<String, Value>()

        for (key in expected.keys + actual.keys) {
            val expectedValue = expected.getValue(key)
            val actualValue = actual.getValue(key)

            when {
                key in expected && key in actual -> {
                    when {
                        expectedValue == actualValue -> combinedServerState[key] = actualValue
                        expectedValue is StringValue && expectedValue.isPatternToken() -> {
                            ifMatches(key, expectedValue, actualValue, resolver) {
                                combinedServerState[key] = actualValue
                            }
                        }
                    }
                }
                key in expected -> combinedServerState[key] = expectedValue
                key in actual -> combinedServerState[key] = actualValue
            }
        }

        return combinedServerState
    }

    private fun ifMatches(
        key: String,
        expectedValue: StringValue,
        actualValue: Value,
        resolver: Resolver,
        code: () -> Unit
    ) {
        val expectedPattern = resolver.getPattern(expectedValue.string)

        try {
            if (resolver.matchesPattern(key, expectedPattern, expectedPattern.parse(actualValue.toString(), resolver))
                    .isSuccess()
            )
                code()
        } catch (e: Throwable) {
            throw ContractException("Couldn't match state values. Expected $expectedValue in key $key" +
                ", actual value is $actualValue", exceptionCause = e)
        }
    }

    fun generateHttpRequest(resolverStrategies: ResolverStrategies = DefaultStrategies): HttpRequest =
        scenarioBreadCrumb(this) { httpRequestPattern.generate(resolverStrategies.update(Resolver(expectedFacts, false, patterns))) }

    fun matches(httpResponse: HttpResponse, mismatchMessages: MismatchMessages = DefaultMismatchMessages, unexpectedKeyCheck: UnexpectedKeyCheck? = null): Result {
        val resolver = Resolver(expectedFacts, false, patterns).copy(mismatchMessages = mismatchMessages).let {
            if(unexpectedKeyCheck != null)
                it.copy(findKeyErrorCheck = it.findKeyErrorCheck.copy(unexpectedKeyCheck = unexpectedKeyCheck))
            else
                it
        }

        if (this.isNegative) {
            return if (is4xxResponse(httpResponse)) {
                if(badRequestOrDefault != null && badRequestOrDefault.supports(httpResponse.status))
                    badRequestOrDefault.matches(httpResponse, resolver).updateScenario(this)
                else
                    Result.Failure("Received ${httpResponse.status}, but the specification does not contain a 4xx response, hence unable to verify this response", breadCrumb = "RESPONSE.STATUS").updateScenario(this)
            }
            else
                Result.Failure("Expected 4xx status, but received ${httpResponse.status}", breadCrumb = "RESPONSE.STATUS").updateScenario(this)
        }

        return try {
            httpResponsePattern.matches(httpResponse, resolver).updateScenario(this)
        } catch (exception: Throwable) {
            Result.Failure("Exception: ${exception.message}")
        }
    }

    private fun is4xxResponse(httpResponse: HttpResponse) = (400..499).contains(httpResponse.status)

    object ContractAndRowValueMismatch : MismatchMessages {
        override fun mismatchMessage(expected: String, actual: String): String {
            return "Contract expected $expected but found value $actual"
        }

        override fun unexpectedKey(keyLabel: String, keyName: String): String {
            return "${
                keyLabel.lowercase().capitalizeFirstChar()
            } named $keyName in the example was not in the specification"
        }

        override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
            return "${
                keyLabel.lowercase().capitalizeFirstChar()
            } named $keyName in the specification was not found in the example"
        }
    }

    private fun newBasedOn(row: Row, resolverStrategies: ResolverStrategies): List<Scenario> {
        val ignoreFailure = this.ignoreFailure || row.name.startsWith("[WIP]")
        val resolver =
            Resolver(expectedFacts, false, patterns)
            .copy(
                mismatchMessages = ContractAndRowValueMismatch
            ).let { resolverStrategies.update(it) }

        val newExpectedServerState = newExpectedServerStateBasedOn(row, expectedFacts, fixtures, resolver)

        return scenarioBreadCrumb(this) {
            attempt {
                when (isNegative) {
                    false -> httpRequestPattern.newBasedOn(row, resolver, httpResponsePattern.status)
                    else -> httpRequestPattern.negativeBasedOn(row, resolver.copy(isNegative = true))
                }.map { newHttpRequestPattern ->
                    this.copy(
                        httpRequestPattern = newHttpRequestPattern,
                        expectedFacts = newExpectedServerState,
                        ignoreFailure = ignoreFailure,
                        exampleName = row.name
                    )
                }
            }
        }
    }

    private fun newBasedOnBackwardCompatibility(row: Row): List<Scenario> {
        val resolver = Resolver(expectedFacts, false, patterns)

        val newExpectedServerState = newExpectedServerStateBasedOn(row, expectedFacts, fixtures, resolver)

        return httpRequestPattern.newBasedOn(resolver).map { newHttpRequestPattern ->
            this.copy(
                httpRequestPattern = newHttpRequestPattern,
                expectedFacts = newExpectedServerState
            )
        }
    }

    fun generateTestScenarios(
        resolverStrategies: ResolverStrategies,
        variables: Map<String, String> = emptyMap(),
        testBaseURLs: Map<String, String> = emptyMap(),
    ): List<Scenario> {
        val referencesWithBaseURLs = references.mapValues { (_, reference) ->
            reference.copy(variables = variables, baseURLs = testBaseURLs)
        }

        return scenarioBreadCrumb(this) {
            when (examples.size) {
                0 -> listOf(Row())
                else -> examples.flatMap {
                    it.rows.map { row ->
                        row.copy(variables = variables, references = referencesWithBaseURLs)
                    }
                }
            }.flatMap { row ->
                newBasedOn(row, resolverStrategies)
            }.map {
                it.copy(generativePrefix = resolverStrategies.generation.positivePrefix)
            }
        }
    }

    fun generateContractTests(
        resolverStrategies: ResolverStrategies,
        variables: Map<String, String> = emptyMap(),
        testBaseURLs: Map<String, String> = emptyMap(),
    ): List<ContractTest> {
        val referencesWithBaseURLs = references.mapValues { (_, reference) ->
            reference.copy(variables = variables, baseURLs = testBaseURLs)
        }

        return scenarioBreadCrumb(this) {
            when (examples.size) {
                0 -> listOf(Row())
                else -> examples.flatMap {
                    it.rows.map { row ->
                        row.copy(variables = variables, references = referencesWithBaseURLs)
                    }
                }
            }.flatMap { row ->
                try {
                    newBasedOn(row, resolverStrategies).map { ScenarioTest(it, resolverStrategies) }
                } catch (e: Throwable) {
                    listOf(ScenarioTestGenerationFailure(this, e))
                }
            }
        }
    }

    fun generateBackwardCompatibilityScenarios(
        variables: Map<String, String> = emptyMap(),
        testBaseURLs: Map<String, String> = emptyMap()
    ): List<Scenario> {
        val referencesWithBaseURLs = references.mapValues { (_, reference) ->
            reference.copy(variables = variables, baseURLs = testBaseURLs)
        }

        return scenarioBreadCrumb(this) {
            when (examples.size) {
                0 -> listOf(Row())
                else -> examples.flatMap {
                    it.rows.map { row ->
                        row.copy(variables = variables, references = referencesWithBaseURLs)
                    }
                }
            }.flatMap { row ->
                newBasedOnBackwardCompatibility(row)
            }
        }
    }

    val resolver: Resolver = Resolver(newPatterns = patterns)

    val serverState: Map<String, Value>
        get() = expectedFacts

    fun matchesMock(
        request: HttpRequest,
        response: HttpResponse,
        mismatchMessages: MismatchMessages = DefaultMismatchMessages
    ): Result {
        scenarioBreadCrumb(this) {
            val resolver = Resolver(
                IgnoreFacts(),
                true,
                patterns,
                findKeyErrorCheck = DefaultKeyCheck.disableOverrideUnexpectedKeycheck(),
                mismatchMessages = mismatchMessages
            )

            val requestMatchResult = attempt(breadCrumb = "REQUEST") { httpRequestPattern.matches(request, resolver) }

            if (requestMatchResult is Result.Failure)
                requestMatchResult.updateScenario(this)

            if (requestMatchResult is Result.Failure && response.status != httpResponsePattern.status)
                return Result.Failure(
                    cause = requestMatchResult,
                    failureReason = FailureReason.RequestMismatchButStatusAlsoWrong
                )

            val responseMatchResult =
                attempt(breadCrumb = "RESPONSE") { httpResponsePattern.matchesMock(response, resolver) }

            if (requestMatchResult is Result.Failure)
                responseMatchResult.updateScenario(this)

            val failures = listOf(requestMatchResult, responseMatchResult).filterIsInstance<Result.Failure>()

            return if (failures.isEmpty())
                Result.Success()
            else
                Result.Failure.fromFailures(failures)
        }
    }

    fun resolverAndResponseFrom(response: HttpResponse): Pair<Resolver, HttpResponse> =
        scenarioBreadCrumb(this) {
            attempt(breadCrumb = "RESPONSE") {
                val resolver = Resolver(expectedFacts, false, patterns)
                Pair(resolver, HttpResponsePattern(response).generateResponse(resolver))
            }
        }

    override fun testDescription(): String {
        val method = this.httpRequestPattern.method
        val path = this.httpRequestPattern.httpPathPattern?.path ?: ""
        val exampleIdentifier = if(exampleName.isNullOrBlank()) "" else { " | EX:${exampleName.trim()}" }

        val generativePrefix = this.generativePrefix

        return "$generativePrefix Scenario: $method $path ${disambiguate()}-> $statusInDescription$exampleIdentifier"
    }

    fun newBasedOn(scenario: Scenario): Scenario {
        return this.copy(
            examples = scenario.examples,
            references = scenario.references
        )
    }

    fun newBasedOn(suggestions: List<Scenario>) =
        this.newBasedOn(suggestions.find { it.name == this.name } ?: this)

    fun isA2xxScenario(): Boolean = this.httpResponsePattern.status in 200..299
    fun negativeBasedOn(badRequestOrDefault: BadRequestOrDefault?): Scenario {
        return this.copy(
            isNegative = true,
            badRequestOrDefault = badRequestOrDefault,
            statusInDescription = "4xx"
        )
    }

    fun getStatus(response: HttpResponse?): Int {
        // TODO: This should return a string so that we can return a 4xx when response is null for a negative scenario
        return when {
            response == null -> status
            isNegative -> response.status
            else -> status
        }
    }

    fun useExamples(externalisedJSONExamples: Map<OpenApiSpecification.OperationIdentifier, List<Row>>): Scenario {
        val matchingTestData: Map<OpenApiSpecification.OperationIdentifier, List<Row>> = matchingRows(externalisedJSONExamples)

        val newExamples: List<Examples> = matchingTestData.map { (operationId, rows) ->
            if(rows.isEmpty())
                return@map emptyList()

            val rowsWithPathData: List<Row> = rows.map { row -> httpRequestPattern.addPathParamsToRows(operationId.requestPath, row, resolver) }

            val columns = rowsWithPathData.first().columnNames

            listOf(Examples(columns, rowsWithPathData))
        }.flatten()

        return this.copy(examples = newExamples)
    }

    private fun matchingRows(externalisedJSONExamples: Map<OpenApiSpecification.OperationIdentifier, List<Row>>) =
        externalisedJSONExamples.filter { (operationId, rows) ->
            operationId.requestMethod.equals(method, ignoreCase = true)
                    && operationId.responseStatus == status
                    && httpRequestPattern.matchesPath(operationId.requestPath, resolver).isSuccess()
        }
}

fun newExpectedServerStateBasedOn(
    row: Row,
    expectedServerState: Map<String, Value>,
    fixtures: Map<String, Value>,
    resolver: Resolver
): Map<String, Value> =
    attempt(errorMessage = "Scenario fact generation failed") {
        expectedServerState.mapValues { (key, value) ->
            when {
                row.containsField(key) -> {
                    val fieldValue = row.getField(key)

                    when {
                        fixtures.containsKey(fieldValue) -> fixtures.getValue(fieldValue)
                        isPatternToken(fieldValue) -> {
                            val fieldPattern = resolver.getPattern(fieldValue)
                            resolver.withCyclePrevention(fieldPattern, fieldPattern::generate)
                        }
                        else -> StringValue(fieldValue)
                    }
                }
                value is StringValue && isPatternToken(value) -> resolver.getPattern(value.string).generate(resolver)
                else -> value
            }
        }
    }

object ContractAndResponseMismatch : MismatchMessages {
    override fun mismatchMessage(expected: String, actual: String): String {
        return "Contract expected $expected but response contained $actual"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.lowercase().capitalizeFirstChar()} named $keyName in the response was not in the specification"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "${
            keyLabel.lowercase().capitalizeFirstChar()
        } named $keyName in the specification was not found in the response"
    }
}

fun executeTest(testScenario: Scenario, testExecutor: TestExecutor, resolverStrategies: ResolverStrategies = DefaultStrategies): Result {
    return executeTestAndReturnResultAndResponse(testScenario, testExecutor, resolverStrategies).first
}

fun executeTestAndReturnResultAndResponse(
    testScenario: Scenario,
    testExecutor: TestExecutor,
    resolverStrategies: ResolverStrategies
): Pair<Result, HttpResponse?> {
    val request = testScenario.generateHttpRequest(resolverStrategies)

    return try {
        testExecutor.setServerState(testScenario.serverState)

        val response = testExecutor.execute(request)

        val result = testResult(response, testScenario)

        Pair(result.withBindings(testScenario.bindings, response), response)
    } catch (exception: Throwable) {
        Pair(Result.Failure(exceptionCauseMessage(exception))
            .also { failure -> failure.updateScenario(testScenario) }, null)
    }
}

private fun testResult(
    response: HttpResponse,
    testScenario: Scenario
): Result {

    val result = when {
        response.specmaticResultHeaderValue() == "failure" -> Result.Failure(response.body.toStringLiteral())
            .updateScenario(testScenario)
        response.body is JSONObjectValue && ignorable(response.body) -> Result.Success()
        else -> testScenario.matches(response, ContractAndResponseMismatch, ValidateUnexpectedKeys)
    }.also { result ->
        if (result is Result.Success && result.isPartialSuccess()) {
            logger.log("    PARTIAL SUCCESS: ${result.partialSuccessMessage}")
            logger.newLine()
        }
    }

    return result
}

fun ignorable(body: JSONObjectValue): Boolean {
    return Flags.customResponse() &&
        (body.findFirstChildByPath("resultStatus.status")?.toStringLiteral() == "FAILED" &&
            (body.findFirstChildByPath("resultStatus.errorCode")?.toStringLiteral() == "INVALID_REQUEST"))
}
