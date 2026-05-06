package io.specmatic.core

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.asserts.toFailure
import io.specmatic.test.openAPIOperationFrom
import java.util.Collections
import java.util.IdentityHashMap

class OpenApiBackwardCompatibilityChecker(private val oldFeature: Feature, private val newFeature: Feature) {
    private val newScenariosByMethodAndReqContentType = newFeature.scenarios.groupBy { it.method to it.requestContentType }

    fun run(): Map<OpenAPIOperation, Results> {
        val requestFamilies = groupScenariosByPathAndMethod(oldFeature)
        val resultsByOperation = buildMap {
            requestFamilies.forEach { requestFamily ->
                val requestResults = validateRequestCompatibility(requestFamily)
                val responseResults = validateResponseCompatibility(requestFamily, requestResults)
                collectOperationResults(requestResults, responseResults, this@buildMap)
            }
        }

        return resultsByOperation.mapValues { (_, results) -> Results(results).distinct() }
    }

    private fun groupScenariosByPathAndMethod(feature: Feature): List<RequestFamily> {
        return feature.scenarios
            .filter { !it.ignoreFailure }
            .groupBy { scenario -> scenario.path to scenario.method }.values
            .map(::RequestFamily)
    }

    private fun validateRequestCompatibility(requestFamily: RequestFamily): List<ResultWithScenario> {
        val uniqueRequestScenarios = requestFamily.getUniqueRequestScenarios()
        val positiveMutations = generatePositiveMutations(uniqueRequestScenarios)
        val totalPositiveMutations = positiveMutations.size

        logger.boundary()
        logger.log(initialLogMessage(requestFamily, totalPositiveMutations))
        return positiveMutations.withIndex().flatMap { (index, mutations) ->
            evaluateMutation(mutations).also {
                logProgress(index.inc(), totalPositiveMutations)
            }
        }
    }

    private fun generatePositiveMutations(scenarios: List<Scenario>): List<Scenario> {
        return scenarios.flatMap { scenario ->
            scenario.copy(examples = emptyList()).generateBackwardCompatibilityScenarios()
        }
    }

    private fun initialLogMessage(requestFamily: RequestFamily, totalMutations: Int): String {
        return "[Compatibility Check] Executing $totalMutations scenarios for ${requestFamily.description()}"
    }

    private fun logProgress(current: Int, total: Int) {
        if (total >= PROGRESSION_LOG_THRESHOLD && (current % PROGRESSION_LOG_INCREMENT == 0 || current == total)) {
            logger.log("[Compatibility Check] Completed $current/$total")
        }
    }

    private fun evaluateMutation(generatedScenario: Scenario): List<ResultWithScenario> {
        return try {
            val request = generatedScenario.generateHttpRequest()
            requestMatches(request, generatedScenario)
        } catch (contractException: ContractException) {
            val result = contractException.failure()
            listOf(ResultWithScenario(result, generatedScenario))
        } catch (_: StackOverflowError) {
            val result = Result.Failure(STACK_OVERFLOW_MESSAGE)
            listOf(ResultWithScenario(result, generatedScenario))
        } catch (_: EmptyContract) {
            val newFilePath = if (newFeature.path.isNotEmpty()) " at ${newFeature.path}" else ""
            val result = Result.Failure("The contract$newFilePath had no operations")
            listOf(ResultWithScenario(result, generatedScenario))
        } catch (throwable: Throwable) {
            val result = Result.Failure("Exception: ${throwable.localizedMessage}")
            listOf(ResultWithScenario(result, generatedScenario))
        }
    }

    private fun requestMatches(request: HttpRequest, scenario: Scenario): List<ResultWithScenario> {
        if (newFeature.scenarios.isEmpty()) throw EmptyContract()

        val candidates = newScenariosByMethodAndReqContentType.findExactOrFirst(scenario.method, scenario.requestContentType)
        val identifierMatches = candidates.filter { candidate -> candidate.matchesPathStructureAndMethod(request) }
        if (identifierMatches.isEmpty()) {
            val result = Result.Failure("This API exists in the old contract but not in the new contract")
            return listOf(ResultWithScenario(result.updateScenario(scenario), scenario))
        }

        val matchResult = identifierMatches.first().matches(
            httpRequest = request,
            serverState = scenario.expectedFacts,
            unexpectedKeyCheck = IgnoreUnexpectedKeys,
            mismatchMessages = NewAndOldSpecificationRequestMismatches,
        )

        val resultList = identifierMatches.map { candidate ->
            ResultWithScenario(matchResult.updateScenario(candidate), candidate)
        }

        val successfulResults = resultList.filter { (result, _) -> result is Result.Success }
        if (successfulResults.isNotEmpty()) return successfulResults
        return resultList
    }

    private fun validateResponseCompatibility(requestFamily: RequestFamily, requestResults: List<ResultWithScenario>): List<ResultWithScenario> {
        val oldScenarios = requestFamily.getUniqueResponseScenarios()
        val newScenarios = dedupeNewScenariosByIdentity(requestResults)
        val newScenariosByStatusAndResContentType = newScenarios.groupBy { it.status to it.responseContentType }

        return oldScenarios.flatMap { oldScenario ->
            val matchingScenarios = newScenariosByStatusAndResContentType.findExactOrFirst(oldScenario.status, oldScenario.responseContentType)
            if (matchingScenarios.isEmpty()) {
                val result = Result.Failure("This API exists in the old contract but not in the new contract")
                return@flatMap listOf(ResultWithScenario(result.updateScenario(oldScenario), oldScenario))
            }

            matchingScenarios.map { newScenario -> checkResponseEncompasses(oldScenario, newScenario) }
        }
    }

    private fun dedupeNewScenariosByIdentity(requestResults: List<ResultWithScenario>): List<Scenario> {
        val seenScenarios = Collections.newSetFromMap(IdentityHashMap<Scenario, Boolean>())
        return requestResults.asSequence().map { it.scenario }.filter(seenScenarios::add).toList()
    }

    private fun checkResponseEncompasses(oldScenario: Scenario, newScenario: Scenario): ResultWithScenario {
        return try {
            ResultWithScenario(
                scenario = newScenario,
                result = oldScenario.httpResponsePattern.encompasses(
                    other = newScenario.httpResponsePattern,
                    olderResolver = oldScenario.resolver.copy(mismatchMessages = NewAndOldSpecificationResponseMismatches),
                    newerResolver = newScenario.resolver.copy(mismatchMessages = NewAndOldSpecificationResponseMismatches),
                ).updateScenario(newScenario)
            )
        } catch (_: StackOverflowError) {
            ResultWithScenario(Result.Failure(STACK_OVERFLOW_MESSAGE), newScenario)
        } catch (throwable: Throwable) {
            ResultWithScenario(throwable.toFailure(), newScenario)
        }
    }

    private fun collectOperationResults(
        requestResults: List<ResultWithScenario>,
        responseResults: List<ResultWithScenario>,
        resultsByOperation: MutableMap<OpenAPIOperation, MutableList<Result>>,
    ) {
        val failures = (requestResults + responseResults).filter { it.result is Result.Failure }
        failures.forEach { resultWithScenario ->
            val operation = openAPIOperationFrom(resultWithScenario.scenario, convertPathParameterStyle(resultWithScenario.scenario.path))
            val operationResults = resultsByOperation.getOrPut(operation) { mutableListOf() }
            operationResults.add(resultWithScenario.result)
        }

        logger.log("[Compatibility Check] Verdict: ${if (failures.isNotEmpty()) "FAIL" else "PASS"}")
    }

    private fun <First, Second, Item> Map<Pair<First, Second>, List<Item>>.findExactOrFirst(first: First, second: Second): List<Item> {
        val exact = this[first to second]
        if (!exact.isNullOrEmpty()) return exact
        return buildList {
            for ((key, value) in this@findExactOrFirst) {
                if (key.first == first) addAll(value)
            }
        }
    }

    companion object {
        private const val PROGRESSION_LOG_INCREMENT = 100
        private const val PROGRESSION_LOG_THRESHOLD = 1000
        private const val STACK_OVERFLOW_MESSAGE = "Exception: Stack overflow error, most likely caused by a recursive definition. Please report this with a sample contract as a bug!"
    }
}

private data class ResultWithScenario(val result: Result, val scenario: Scenario)
private data class RequestFamily(val scenarios: List<Scenario>) {
    private val representativeScenario: Scenario = scenarios.first()
    private val groupedByRequestIdentifiers = scenarios.groupBy { Triple(it.path, it.method, it.requestContentType) }
    private val groupedByResponseIdentifiers = scenarios.groupBy { Pair(it.status, it.responseContentType) }

    fun getUniqueRequestScenarios(): List<Scenario> {
        return groupedByRequestIdentifiers.values.mapNotNull { scenarios -> scenarios.firstOrNull() }
    }

    fun getUniqueResponseScenarios(): List<Scenario> {
        return groupedByResponseIdentifiers.values.mapNotNull { scenarios -> scenarios.firstOrNull() }
    }

    fun description(): String {
        val header = buildString {
            append("${representativeScenario.method} ${representativeScenario.path}")
            append(" against ${scenarios.size} operations")
        }

        val scenarioLines = scenarios.joinToString(separator = "\n") { scenario ->
            "  - ${scenario.fullApiDescription}"
        }

        return "$header\n$scenarioLines"
    }
}
