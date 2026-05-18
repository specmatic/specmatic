package io.specmatic.core

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.reporter.internal.dto.bcc.ChangeStatus
import io.specmatic.test.asserts.toFailure
import java.util.Collections
import java.util.IdentityHashMap

class OpenApiBackwardCompatibilityChecker(private val oldFeature: Feature, private val newFeature: Feature) {
    private val newScenariosByMethodAndReqContentType = newFeature.scenarios.groupBy { it.method to it.requestContentType }
    private val newScenariosByPathAndMethod = newFeature.scenarios.groupBy { it.path to it.method }

    fun run(): List<OpenApiBackwardCompatibilityCheckRecord> {
        val requestFamilies = groupScenariosByPathAndMethod(oldFeature)
        return buildList {
            requestFamilies.forEach { requestFamily ->
                val changeStatus = operationChangeStatus(requestFamily)
                val requestResults = validateRequestCompatibility(requestFamily, changeStatus).also(::addAll)
                val responseResults = validateResponseCompatibility(requestFamily, requestResults, changeStatus).also(::addAll)
                logOperationResult(requestResults.plus(responseResults))
            }
        }
    }

    private fun operationChangeStatus(requestFamily: RequestFamily): ChangeStatus {
        val newScenariosForOperation = newScenariosByPathAndMethod[requestFamily.path to requestFamily.method].orEmpty()
        return ScenarioFingerprint.changeStatusBetween(requestFamily.scenarios, newScenariosForOperation)
    }

    private fun groupScenariosByPathAndMethod(feature: Feature): List<RequestFamily> {
        return feature.scenarios
            .filter { !it.ignoreFailure }
            .groupBy { scenario -> scenario.path to scenario.method }.values
            .map(::RequestFamily)
    }

    private fun validateRequestCompatibility(requestFamily: RequestFamily, changeStatus: ChangeStatus): List<OpenApiBackwardCompatibilityCheckRecord> {
        val scenarioPerReqIdentifier = requestFamily.oneScenarioPerReqIdentifiers()
        val positiveVariations = generatePositiveVariations(scenarioPerReqIdentifier)
        val totalPositiveVariations = positiveVariations.size

        logger.boundary()
        logger.log(initialLogMessage(requestFamily, totalPositiveVariations))
        return positiveVariations.withIndex().flatMap { (index, variation) ->
            evaluateVariation(variation, changeStatus).also {
                logProgress(index.inc(), totalPositiveVariations)
            }
        }
    }

    private fun generatePositiveVariations(scenarios: List<Scenario>): List<Scenario> {
        return scenarios.flatMap { scenario ->
            scenario.withoutExamples().generateBackwardCompatibilityScenarios()
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

    private fun logOperationResult(results: List<OpenApiBackwardCompatibilityCheckRecord>) {
        val verdict = if (results.any { it.compatResult is Result.Failure }) "FAIL" else "PASS"
        logger.log("[Compatibility Check] Verdict: $verdict")
    }

    private fun evaluateVariation(variationFromOldScenario: Scenario, changeStatus: ChangeStatus): List<OpenApiBackwardCompatibilityCheckRecord> {
        return try {
            val request = variationFromOldScenario.generateHttpRequest(backwardCompatibilityStrategies)
            requestMatches(request, variationFromOldScenario, changeStatus)
        } catch (contractException: ContractException) {
            val result = contractException.failure()
            listOf(newRecord(variationFromOldScenario, result, changeStatus))
        } catch (_: StackOverflowError) {
            val result = Result.Failure(STACK_OVERFLOW_MESSAGE)
            listOf(newRecord(variationFromOldScenario, result, changeStatus))
        } catch (_: EmptyContract) {
            val newFilePath = if (newFeature.path.isNotEmpty()) " at ${newFeature.path}" else ""
            val result = Result.Failure("The contract$newFilePath had no operations")
            listOf(newRecord(variationFromOldScenario, result, changeStatus))
        } catch (throwable: Throwable) {
            val result = Result.Failure("Exception: ${throwable.localizedMessage}")
            listOf(newRecord(variationFromOldScenario, result, changeStatus))
        }
    }

    private fun requestMatches(request: HttpRequest, variationFromOldScenario: Scenario, changeStatus: ChangeStatus): List<OpenApiBackwardCompatibilityCheckRecord> {
        if (newFeature.scenarios.isEmpty()) throw EmptyContract()
        val identifierMatches = newScenariosByMethodAndReqContentType.findExactOrSingle(
            first = variationFromOldScenario.method,
            second = variationFromOldScenario.requestContentType,
            valueFilter = { it.matchesPathStructureAndMethod(request) }
        )

        if (identifierMatches.isEmpty()) {
            val result = Result.Failure("This API exists in the old contract but not in the new contract")
            return listOf(newRecord(variationFromOldScenario, result, changeStatus))
        }

        // This is a performance optimization: If Path + Method + RequestContentType has many scenarios,
        // then the request schemas will be the same across so we only evaluate the first time
        val matchResult = identifierMatches.first().matches(
            httpRequest = request,
            unexpectedKeyCheck = IgnoreUnexpectedKeys,
            serverState = variationFromOldScenario.expectedFacts,
            mismatchMessages = NewAndOldSpecificationRequestMismatches,
        )

        // This is a performance optimization: As there can be multiple scenarios with responseCode and contentTypes,
        // we can associate first result with remaining avoiding re-valuation as they will share the same request schema as per OAS
        return identifierMatches.map { newScenario ->
            newRecord(newScenario, matchResult, changeStatus)
        }
    }

    private fun validateResponseCompatibility(requestFamily: RequestFamily, requestResults: List<OpenApiBackwardCompatibilityCheckRecord>, changeStatus: ChangeStatus): List<OpenApiBackwardCompatibilityCheckRecord> {
        val oldScenarios = requestFamily.oneScenarioPerResIdentifiers()
        val newScenarios = dedupeNewScenariosByIdentity(requestResults)
        val newScenariosByStatusAndResContentType = newScenarios.groupBy { it.status to it.responseContentType }

        return oldScenarios.flatMap { oldScenario ->
            val identifierMatches = newScenariosByStatusAndResContentType.findExactOrSingle(oldScenario.status, oldScenario.responseContentType)
            if (identifierMatches.isEmpty()) {
                val result = Result.Failure("This API exists in the old contract but not in the new contract")
                return@flatMap listOf(newRecord(oldScenario, result, changeStatus))
            }

            identifierMatches.map { newScenario -> checkResponseEncompasses(oldScenario, newScenario, changeStatus) }
        }
    }

    private fun dedupeNewScenariosByIdentity(requestResults: List<OpenApiBackwardCompatibilityCheckRecord>): List<Scenario> {
        val seenScenarios = Collections.newSetFromMap(IdentityHashMap<Scenario, Boolean>())
        return requestResults.asSequence().map { it.scenario }.filter(seenScenarios::add).toList()
    }

    private fun checkResponseEncompasses(oldScenario: Scenario, newScenario: Scenario, changeStatus: ChangeStatus): OpenApiBackwardCompatibilityCheckRecord {
        return try {
            newRecord(
                scenario = newScenario,
                result = oldScenario.httpResponsePattern.encompasses(
                    other = newScenario.httpResponsePattern,
                    olderResolver = oldScenario.resolver.copy(mismatchMessages = NewAndOldSpecificationResponseMismatches),
                    newerResolver = newScenario.resolver.copy(mismatchMessages = NewAndOldSpecificationResponseMismatches),
                ),
                changeStatus = changeStatus,
            )
        } catch (_: StackOverflowError) {
            newRecord(newScenario, Result.Failure(STACK_OVERFLOW_MESSAGE), changeStatus)
        } catch (throwable: Throwable) {
            newRecord(newScenario, throwable.toFailure(), changeStatus)
        }
    }

    private fun newRecord(scenario: Scenario, result: Result, changeStatus: ChangeStatus): OpenApiBackwardCompatibilityCheckRecord {
        return OpenApiBackwardCompatibilityCheckRecord(
            feature = newFeature,
            scenario = scenario,
            compatResult = result.updateScenario(scenario).withoutFailureReasons(),
            changeStatus = changeStatus,
        )
    }

    private fun <First, Second, Item> Map<Pair<First, Second?>, List<Item>>.findExactOrSingle(
        first: First,
        second: Second?,
        valueFilter: (Item) -> Boolean = { true },
    ): List<Item> {
        val exact = this[first to second]?.filter(valueFilter)
        if (!exact.isNullOrEmpty()) return exact

        val fallBackEntries = entries.asSequence().filter { it.key.first == first }.flatMap { it.value.asSequence() }
        return fallBackEntries.singleOrNull(valueFilter)?.let(::listOf).orEmpty()
    }

    companion object {
        private const val PROGRESSION_LOG_INCREMENT = 100
        private const val PROGRESSION_LOG_THRESHOLD = 1000
        private const val BACKWARD_COMPATIBILITY_RANDOM_ARRAY_SIZE = 1
        private const val STACK_OVERFLOW_MESSAGE = "Exception: Stack overflow error, most likely caused by a recursive definition. Please report this with a sample contract as a bug!"
        private val backwardCompatibilityStrategies = DefaultStrategies.copy(randomArraySize = BACKWARD_COMPATIBILITY_RANDOM_ARRAY_SIZE)
    }
}

private data class RequestFamily(val scenarios: List<Scenario>) {
    private val representativeScenario: Scenario = scenarios.first()
    private val groupedByRequestIdentifiers = scenarios.groupBy { Triple(it.path, it.method, it.requestContentType) }
    private val groupedByResponseIdentifiers = scenarios.groupBy { Pair(it.status, it.responseContentType) }

    val path: String get() = representativeScenario.path
    val method: String get() = representativeScenario.method

    fun oneScenarioPerReqIdentifiers(): List<Scenario> {
        return groupedByRequestIdentifiers.values.mapNotNull { scenarios -> scenarios.firstOrNull() }
    }

    fun oneScenarioPerResIdentifiers(): List<Scenario> {
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
