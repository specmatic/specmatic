package io.specmatic.core.fuzzy

import kotlin.math.min

interface SimilarityStrategy {
    fun score(input: String, candidate: String): Int
    fun maxAllowedDistance(length: Int): Int
}

class LevenshteinStrategy : SimilarityStrategy {
    override fun score(input: String, candidate: String): Int = distance(input, candidate)

    override fun maxAllowedDistance(length: Int): Int = when {
        length <= 5 -> 2
        else -> 3
    }

    private fun distance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        val costs = IntArray(s2.length + 1) { it }

        for (i in 1..s1.length) {
            var prev = i - 1
            costs[0] = i
            for (j in 1..s2.length) {
                val current = costs[j]
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                costs[j] = min(min(costs[j - 1] + 1, costs[j] + 1), prev + cost)
                prev = current
            }
        }

        return costs[s2.length]
    }
}
