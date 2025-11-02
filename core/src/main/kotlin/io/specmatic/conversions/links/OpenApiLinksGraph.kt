package io.specmatic.conversions.links

import io.specmatic.core.DEFAULT_RESPONSE_CODE
import io.specmatic.core.Scenario
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.utilities.Flags
import java.util.PriorityQueue

private typealias DependencyMap = Map<OpenApiOperationReference, Set<OpenApiOperationReference>>

private data class DependencyCycle(val operationReferences: List<OpenApiOperationReference>) {
    override fun toString(): String {
        val cycleSteps = operationReferences.joinToString(" → ") { formatOperationRef(it) }
        val firstStep = formatOperationRef(operationReferences.first())
        return "$cycleSteps → $firstStep"
    }

    private fun formatOperationRef(ref: OpenApiOperationReference): String {
        val identifiers = listOfNotNull(ref.operationId?.let { "Operation: $it"}, "${ref.method} ${ref.path} → [${ref.status}]")
        return identifiers.joinToString(separator = " ")
    }
}

data class OpenApiLinksGraph(private val dependencyMap: DependencyMap = emptyMap()) {
    fun sortScenariosBasedOnDependency(scenarios: List<Scenario>): ReturnValue<List<Scenario>> {
        if (dependencyMap.isEmpty()) return HasValue(scenarios)
        val scenarioGraph = buildScenarioGraph(scenarios)
        return topologicalSort(scenarioGraph, scenarios)
    }

    private fun buildScenarioGraph(scenarios: List<Scenario>): Map<Scenario, Set<Scenario>> {
        val operationDependencies = buildOperationDependencies()
        val scenariosByOperation = scenarios.groupBy { scenario ->
            OpenApiOperationReference(
                path = scenario.path,
                method = scenario.method,
                status = DEFAULT_RESPONSE_CODE,
                operationId = scenario.operationMetadata?.operationId,
            ).operationIdOrPathAndMethod()
        }

        return buildMap {
            scenarios.forEach { put(it, emptySet()) }
            operationDependencies.forEach { (fromOp, toOps) ->
                val fromScenarios = scenariosByOperation[fromOp] ?: emptyList()
                toOps.forEach { toOp ->
                    val toScenarios = scenariosByOperation[toOp] ?: emptyList()
                    fromScenarios.forEach { from ->
                        put(from, get(from)?.plus(toScenarios) ?: toScenarios.toSet())
                    }
                }
            }
        }
    }

    private fun topologicalSort(graph: Map<Scenario, Set<Scenario>>, scenarios: List<Scenario>): ReturnValue<List<Scenario>> {
        val inDegree = scenarios.associateWith { scenario -> graph.values.count { scenario in it } }.toMutableMap()
        val queue = PriorityQueue(scenarioComparator).apply { addAll(inDegree.filter { it.value == 0 }.keys) }
        val sorted = mutableListOf<Scenario>()

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            sorted.add(current)

            graph[current]?.forEach { dependent ->
                inDegree[dependent] = inDegree.getValue(dependent) - 1
                if (inDegree[dependent] == 0) {
                    queue.add(dependent)
                }
            }
        }

        return if (sorted.size == scenarios.size) {
            HasValue(sorted)
        } else {
            val missingScenariosCount = scenarios.size - sorted.size
            HasFailure(@Suppress("ktlint:standard:wrapping")
            """
            Cyclic dependencies detected during topological sort
            Expected ${scenarios.size} scenarios but could only order ${sorted.size}
            $missingScenariosCount scenario(s) are part of dependency cycles
            """.trimIndent(),
            )
        }
    }

    private fun buildOperationDependencies(): Map<String, Set<String>> {
        return buildMap {
            dependencyMap.forEach { (fromRef, toRefs) ->
                val fromOp = fromRef.operationIdOrPathAndMethod()
                val toOps = toRefs.map { it.operationIdOrPathAndMethod() }.toSet()
                put(fromOp, get(fromOp)?.plus(toOps) ?: toOps)
            }
        }
    }

    companion object {
        private const val ENABLE_LINKS_EXECUTION_REORDERING = "SPECMATIC_REORDER_ON_LINKS"
        private val enableReOrdering: Boolean = Flags.getBooleanValue(ENABLE_LINKS_EXECUTION_REORDERING, true)
        private val scenarioComparator = compareBy<Scenario> {
            it.operationMetadata?.operationId ?: "${it.path} ${it.method}"
        }.thenBy(nullsLast()) { it.status }

        fun from(links: List<OpenApiLink>): ReturnValue<OpenApiLinksGraph> {
            if (links.isEmpty() || !enableReOrdering) return HasValue(OpenApiLinksGraph(emptyMap()))
            val dependencyMap = buildDependencyMap(links)
            val detector = CycleDetector(dependencyMap)
            val cycles = detector.findAllCycles()

            return if (cycles.isEmpty()) {
                HasValue(OpenApiLinksGraph(dependencyMap))
            } else {
                HasFailure(buildCycleErrorMessage(cycles))
            }
        }

        private fun buildDependencyMap(links: List<OpenApiLink>): DependencyMap {
            return links.groupBy({ it.byOperation }, { it.forOperation })
                .mapValues { (_, targets) -> targets.toSet() }
        }

        private fun buildCycleErrorMessage(cycles: List<List<OpenApiOperationReference>>): String {
            val cycleWord = if (cycles.size == 1) "cycle" else "cycles"
            val header = "Cannot process OpenAPI links due to circular dependencies"
            val summary = "Detected ${cycles.size} dependency $cycleWord:"
            val details = cycles.mapIndexed { index, cycle ->
                val dependencyCycle = DependencyCycle(cycle)
                "  ${index + 1}. $dependencyCycle"
            }.joinToString("\n")

            return listOf(
                header,
                summary,
                details,
                """Each operation in a cycle depends on another operation that eventually depends back on itself
                Please Review your OpenAPI link definitions to break these circular dependencies.""",
            ).joinToString("\n\n")
        }
    }
}
