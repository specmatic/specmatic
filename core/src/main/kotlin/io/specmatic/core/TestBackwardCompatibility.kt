package io.specmatic.core

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.Value
import java.util.IdentityHashMap

fun testBackwardCompatibility(older: Feature, newer: Feature): Results {
    return BackwardCompatibilityChecker(older, newer).run()
}

private class BackwardCompatibilityChecker(private val older: Feature, private val newer: Feature) {
    fun run(): Results {
        val requestFamilyExecutions = older.requestFamilies().map { requestFamily ->
            RequestFamilyExecution(
                requestFamily = requestFamily,
                generatedRequestCases = generatedRequestCases(requestFamily)
            )
        }

        val results = requestFamilyExecutions.fold(Results()) { acc, requestFamilyExecution ->
            acc.plus(runRequestFamily(requestFamilyExecution))
        }

        println()
        return results.distinct()
    }

    private fun runRequestFamily(requestFamilyExecution: RequestFamilyExecution): Results {
        logger.boundary()
        logger.log(initialLogMessage(requestFamilyExecution))

        val responseComparisonCache = IdentityHashMap<Scenario, IdentityHashMap<Scenario, Result>>()
        val results = requestFamilyExecution.generatedRequestCases.withIndex().fold(Results()) { acc, (mutationIndex, generatedRequestCase) ->
            val mutationEvaluation = evaluateMutation(generatedRequestCase)
            val scenarioResults = evaluateMutationAgainstRequestFamily(
                mutationEvaluation = mutationEvaluation,
                responseComparisonCache = responseComparisonCache,
                oldScenarios = requestFamilyExecution.requestFamily.scenariosByRequestContentType.values.flatten(),
            )

            logProgress(mutationIndex + 1, requestFamilyExecution.generatedRequestCases.size)
            acc.plus(Results(scenarioResults))
        }

        logger.log("[Compatibility Check] Verdict: ${if (results.success()) "PASS" else "FAIL"}")
        return results
    }

    private fun initialLogMessage(requestFamilyExecution: RequestFamilyExecution): String {
        val totalMutations = requestFamilyExecution.generatedRequestCases.size
        val candidatesCount = requestFamilyExecution.requestFamily.scenariosByRequestContentType.values.flatten().size
        return "[Compatibility Check] Executing $totalMutations scenarios for ${requestFamilyExecution.requestFamily.description(candidatesCount)}"
    }

    private fun logProgress(current: Int, total: Int) {
        if (total >= 1000 && (current % 100 == 0 || current == total)) {
            logger.log("[Compatibility Check] Completed $current/$total")
        }
    }

    private fun Feature.requestFamilies(): List<RequestFamily> {
        return scenarios
            .filter { !it.ignoreFailure }
            .groupBy { scenario -> scenario.path to scenario.method }
            .values.map { groupedScenarios ->
                RequestFamily(
                    representativeScenario = groupedScenarios.first(),
                    scenariosByRequestContentType = groupedScenarios.groupBy { it.requestContentType.orEmpty() }
                )
            }
    }

    private fun generatedRequestCases(requestFamily: RequestFamily): List<Scenario> {
        return requestFamily.scenariosByRequestContentType.values.flatMap { scenariosForContentType ->
            scenariosForContentType.first().copy(examples = emptyList()).generateBackwardCompatibilityScenarios()
        }
    }

    private fun evaluateMutation(generatedScenario: Scenario): MutationEvaluation {
        return try {
            val request = generatedScenario.generateHttpRequest(backwardCompatibilityStrategies)
            val requestMatches = requestMatches(request, generatedScenario.expectedFacts)
            MutationEvaluation.RequestMatched(candidates = requestMatches)
        } catch (contractException: ContractException) {
            MutationEvaluation.GenerationFailure(listOf(contractException.failure()))
        } catch (_: StackOverflowError) {
            MutationEvaluation.GenerationFailure(listOf(Result.Failure(stackOverflowMessage())))
        } catch (_: EmptyContract) {
            val atThisFilePath = if (newer.path.isNotEmpty()) " at ${newer.path}" else ""
            MutationEvaluation.GenerationFailure(listOf(Result.Failure("The contract$atThisFilePath had no operations")))
        } catch (throwable: Throwable) {
            MutationEvaluation.GenerationFailure(listOf(Result.Failure("Exception: ${throwable.localizedMessage}")))
        }
    }

    private fun requestMatches(request: HttpRequest, expectedFacts: Map<String, Value>): List<Pair<Scenario, Result>> {
        if (newer.scenarios.isEmpty()) throw EmptyContract()
        val identifierMatches = newer.scenarios.filter { candidate ->
            candidate.httpRequestPattern.matchesPathStructureMethodAndContentType(request, candidate.resolver).isSuccess()
        }

        val resultList = identifierMatches.map { candidate ->
            candidate to candidate.matches(
                httpRequest = request,
                serverState = expectedFacts,
                mismatchMessages = NewAndOldSpecificationRequestMismatches,
                unexpectedKeyCheck = IgnoreUnexpectedKeys
            )
        }

        val successfulResults = resultList.filter { (_, result) -> result is Result.Success }
        if (successfulResults.isNotEmpty()) return successfulResults

        return resultList.filter { (_, result) ->
            when (result) {
                is Result.Success -> true
                is Result.Failure -> !result.isFluffy()
            }
        }
    }

    private fun evaluateMutationAgainstRequestFamily(
        mutationEvaluation: MutationEvaluation,
        oldScenarios: List<Scenario>,
        responseComparisonCache: IdentityHashMap<Scenario, IdentityHashMap<Scenario, Result>>
    ): List<Result> {
        return when (mutationEvaluation) {
            is MutationEvaluation.GenerationFailure -> mutationEvaluation.results
            is MutationEvaluation.RequestMatched -> oldScenarios.flatMap { oldScenario ->
                evaluateMatchedScenario(oldScenario, mutationEvaluation.candidates, responseComparisonCache)
            }
        }
    }

    private fun evaluateMatchedScenario(
        oldScenario: Scenario,
        candidates: List<Pair<Scenario, Result>>,
        responseComparisonCache: IdentityHashMap<Scenario, IdentityHashMap<Scenario, Result>>
    ): List<Result> {
        val wholeMatchResults = candidates.mapNotNull { (newerScenario, requestResult) ->
            val responseResult = responseComparison(oldScenario, newerScenario, responseComparisonCache)
            responseResult.takeUnless(Result::isFluffy)?.let {
                requestResult.updateScenario(newerScenario) to it.updateScenario(newerScenario)
            }
        }

        return when {
            wholeMatchResults.isEmpty() ->
                listOf(Result.Failure("This API exists in the old contract but not in the new contract").updateScenario(oldScenario))
            wholeMatchResults.any { it.first is Result.Success && it.second is Result.Success } ->
                listOf(Result.Success())
            else ->
                wholeMatchResults.flatMap { it.toList() }.filterIsInstance<Result.Failure>()
        }
    }

    private fun responseComparison(
        oldScenario: Scenario,
        newerScenario: Scenario,
        responseComparisonCache: IdentityHashMap<Scenario, IdentityHashMap<Scenario, Result>>
    ): Result {
        val resultsByNewScenario = responseComparisonCache.getOrPut(oldScenario) { IdentityHashMap() }
        return resultsByNewScenario.getOrPut(newerScenario) {
            oldScenario.httpResponsePattern.encompasses(
                other = newerScenario.httpResponsePattern,
                olderResolver = oldScenario.resolver.copy(mismatchMessages = NewAndOldSpecificationResponseMismatches),
                newerResolver = newerScenario.resolver.copy(mismatchMessages = NewAndOldSpecificationResponseMismatches),
            )
        }
    }

    private fun stackOverflowMessage(): String {
        return "Exception: Stack overflow error, most likely caused by a recursive definition. Please report this with a sample contract as a bug!"
    }

    companion object {
        private const val BACKWARD_COMPATIBILITY_MAX_RANDOM_ARRAY_SIZE = 1
        private val backwardCompatibilityStrategies = DefaultStrategies.copy(maxRandomArraySize = BACKWARD_COMPATIBILITY_MAX_RANDOM_ARRAY_SIZE)

        private data class RequestFamilyExecution(val requestFamily: RequestFamily, val generatedRequestCases: List<Scenario>)
        private data class RequestFamily(val representativeScenario: Scenario, val scenariosByRequestContentType: Map<String, List<Scenario>>) {
            fun description(candidateCount: Int): String {
                val scenarios = scenariosByRequestContentType.values.flatten()
                val header = buildString {
                    append("${representativeScenario.method} ${representativeScenario.path}")
                    append(" against $candidateCount operations")
                }

                val scenarioLines = scenarios.joinToString(separator = "\n") { scenario ->
                    "  - ${scenario.fullApiDescription}"
                }

                return "$header\n$scenarioLines"
            }
        }

        private sealed interface MutationEvaluation {
            data class GenerationFailure(val results: List<Result>) : MutationEvaluation
            data class RequestMatched(val candidates: List<Pair<Scenario, Result>>) : MutationEvaluation
        }
    }
}

object NewAndOldSpecificationRequestMismatches: MismatchMessages {
    override fun toPartFromValue(value: Value?): String {
        return when (value) {
            is NullValue -> "nullable"
            else -> value?.type()?.typeName ?: "null"
        }
    }

    override fun mismatchMessage(expected: String, actual: String): String {
        return "This is $expected in the new specification, but $actual in the old specification"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" in the request from the old specification is not in the new specification"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "New specification expects $keyLabel \"$keyName\" in the request but it is missing from the old specification"
    }

    override fun typeMismatch(expectedType: String, actualValue: String?, actualType: String?): String {
        val expectedPart = "type $expectedType"
        val actualPart = "type $actualType"
        return mismatchMessage(expectedPart, actualPart)
    }
}

object NewAndOldSpecificationResponseMismatches: MismatchMessages {
    override fun toPartFromValue(value: Value?): String {
        return when (value) {
            is NullValue -> "nullable"
            else -> value?.type()?.typeName ?: "null"
        }
    }

    override fun mismatchMessage(expected: String, actual: String): String {
        return "This is $actual in the new specification response but $expected in the old specification"
    }

    override fun unexpectedKey(keyLabel: String, keyName: String): String {
        return "${keyLabel.capitalizeFirstChar()} \"$keyName\" in the response from the new specification is not in the old specification"
    }

    override fun expectedKeyWasMissing(keyLabel: String, keyName: String): String {
        return "The old specification expects $keyLabel \"$keyName\" but it is missing in the new specification"
    }

    override fun typeMismatch(expectedType: String, actualValue: String?, actualType: String?): String {
        val expectedPart = "type $expectedType"
        val actualPart = "type $actualType"
        return NewAndOldSpecificationRequestMismatches.mismatchMessage(expectedPart, actualPart)
    }
}
