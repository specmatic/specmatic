package io.specmatic.conversions.links

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit

class CycleDetectorTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("acyclicGraphs")
    fun `should return empty list for acyclic graphs`(description: String, graph: Map<String, Set<String>>) {
        val detector = CycleDetector(graph)
        val cycles = detector.findAllCycles()
        assertThat(cycles).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleCycleGraphs")
    fun `should detect single cycle correctly`(description: String, graph: Map<String, Set<String>>, expectedCycleNodes: Set<String>) {
        val detector = CycleDetector(graph)
        val cycles = detector.findAllCycles()

        assertThat(cycles).hasSize(1)
        assertThat(cycles[0].toSet()).isEqualTo(expectedCycleNodes)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("multipleCycleGraphs")
    fun `should detect multiple cycles`(description: String, graph: Map<String, Set<String>>, expectedCycleCount: Int, expectedCycleSets: List<Set<String>>) {
        val detector = CycleDetector(graph)

        val cycles = detector.findAllCycles()
        assertThat(cycles).hasSize(expectedCycleCount)

        val cycleSets = cycles.map { it.toSet() }
        assertThat(cycleSets).containsExactlyInAnyOrderElementsOf(expectedCycleSets)
    }

    @Test
    fun `should work with custom data class nodes`() {
        data class Node(val id: String)
        val nodeA = Node("A")
        val nodeB = Node("B")
        val nodeC = Node("C")

        val graph = mapOf(
            nodeA to setOf(nodeB),
            nodeB to setOf(nodeC),
            nodeC to setOf(nodeA),
        )
        val detector = CycleDetector(graph)
        val cycles = detector.findAllCycles()

        assertThat(cycles).hasSize(1)
        assertThat(cycles[0]).containsExactly(nodeA, nodeB, nodeC, nodeA)
    }

    @Timeout(1, unit = TimeUnit.SECONDS)
    @Test
    fun `should handle dense graph with many edges per node`() {
        val size = 1000
        val graph = (0 until size).associateWith { node ->
            setOf((node + 1) % size, (node + 2) % size, (node + 5) % size, (node + 10) % size)
        }

        val detector = CycleDetector(graph)
        val cycles = detector.findAllCycles()

        assertThat(cycles).isNotEmpty
    }

    companion object {
        @JvmStatic
        fun acyclicGraphs() = listOf(
            Arguments.of(
                "empty graph",
                emptyMap<String, Set<String>>(),
            ),
            Arguments.of(
                "single node with no edges",
                mapOf("A" to emptySet<String>()),
            ),
            Arguments.of(
                "linear chain",
                mapOf("A" to setOf("B"), "B" to setOf("C"), "C" to setOf("D")),
            ),
            Arguments.of(
                "tree structure",
                mapOf("A" to setOf("B", "C"), "B" to setOf("D", "E"), "C" to setOf("F")),
            ),
            Arguments.of(
                "diamond DAG",
                mapOf("A" to setOf("B", "C"), "B" to setOf("D"), "C" to setOf("D"), "D" to emptySet()),
            ),
            Arguments.of(
                "dangling reference",
                mapOf("A" to setOf("B"), "B" to setOf("D")),
            ),
        )

        @JvmStatic
        fun singleCycleGraphs() = listOf(
            Arguments.of(
                "self-loop",
                mapOf("A" to setOf("A")),
                setOf("A"),
            ),
            Arguments.of(
                "two-node cycle",
                mapOf("A" to setOf("B"), "B" to setOf("A")),
                setOf("A", "B"),
            ),
            Arguments.of(
                "three-node cycle",
                mapOf("A" to setOf("B"), "B" to setOf("C"), "C" to setOf("A")),
                setOf("A", "B", "C"),
            ),
            Arguments.of(
                "cycle at end of chain",
                mapOf("A" to setOf("B"), "B" to setOf("C"), "C" to setOf("D"), "D" to setOf("E"), "E" to setOf("C")),
                setOf("C", "D", "E"),
            ),
            Arguments.of(
                "cycle with acyclic branch",
                mapOf("A" to setOf("B", "D"), "B" to setOf("C"), "C" to setOf("A"), "D" to setOf("E"), "E" to emptySet()),
                setOf("A", "B", "C"),
            ),
            Arguments.of(
                "triangle cycle with multiple entry points",
                mapOf("ENTRY1" to setOf("A"), "ENTRY2" to setOf("B"), "A" to setOf("B"), "B" to setOf("C"), "C" to setOf("A")),
                setOf("A", "B", "C"),
            ),
        )

        @JvmStatic
        fun multipleCycleGraphs() = listOf(
            Arguments.of(
                "two separate cycles",
                mapOf("A" to setOf("B"), "B" to setOf("A"), "C" to setOf("D"), "D" to setOf("C")),
                2,
                listOf(setOf("A", "B"), setOf("C", "D")),
            ),
            Arguments.of(
                "nested cycles",
                mapOf("A" to setOf("B"), "B" to setOf("C", "D"), "C" to setOf("A"), "D" to setOf("B")),
                2,
                listOf(setOf("B", "D"), setOf("A", "B", "C")),
            ),
            Arguments.of(
                "disconnected components with cycles",
                mapOf("A" to setOf("B"), "B" to setOf("A"), "C" to setOf("D"), "D" to setOf("E"), "E" to setOf("C"), "F" to setOf("G"), "G" to emptySet()),
                2,
                listOf(setOf("A", "B"), setOf("C", "D", "E")),
            ),
        )
    }
}
