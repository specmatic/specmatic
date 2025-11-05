package io.specmatic.conversions.links

private typealias Cycle<T> = List<T>

class CycleDetector<T>(private val graph: Map<T, Set<T>>) {
    fun findAllCycles(): List<Cycle<T>> {
        val cycles = mutableListOf<List<T>>()
        val visited = mutableSetOf<T>()

        graph.keys.forEach { node ->
            if (node !in visited) {
                dfs(node, mutableSetOf(), emptyList(), visited, cycles)
            }
        }

        return cycles.distinctBy { it.toSet() }
    }

    private fun dfs(node: T, recursionStack: MutableSet<T>, path: List<T>, visited: MutableSet<T>, cycles: MutableList<List<T>>) {
        if (node in recursionStack) {
            val cycleStart = path.indexOf(node)
            cycles.add(path.subList(cycleStart, path.size) + node)
            return
        }

        if (visited.add(node)) {
            recursionStack.add(node)

            val newPath = path + node
            graph[node]?.forEach { neighbor ->
                dfs(neighbor, recursionStack, newPath, visited, cycles)
            }

            recursionStack.remove(node)
        }

        return
    }
}
