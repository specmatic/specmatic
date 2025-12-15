package io.specmatic.core.fuzzy

import kotlin.math.max
import kotlin.math.min

enum class MatchQuality(val priority: Int) {
    NONE(0),
    FUZZY(1),
    PREFIX(2),
    EXACT(3);

    fun matched(): Boolean = this != NONE
}

interface SimilarityScorer {
    fun score(inputTokens: List<String>, candidateTokens: List<String>, weights: Map<String, Double>): Double
    fun matchToken(input: String, candidate: String): MatchQuality
    fun tokensMatch(input: String, candidate: String): Boolean = matchToken(input, candidate).matched()
}

class WeightedSimilarityScorer(
    private val similarityStrategy: SimilarityStrategy = LevenshteinStrategy(),
    private val minimumFuzzyMatchLength: Int = 3,
    private val minimumPrefixLength: Int = 3
): SimilarityScorer {
    override fun score(inputTokens: List<String>, candidateTokens: List<String>, weights: Map<String, Double>): Double {
        if (inputTokens.isEmpty() || candidateTokens.isEmpty()) return 0.0
        val candidateCoverage = computeCoverage(inputTokens, candidateTokens, weights)
        val inputCoverage = computeCoverage(candidateTokens, inputTokens, weights)
        return if (candidateCoverage + inputCoverage > 0) {
            2 * (candidateCoverage * inputCoverage) / (candidateCoverage + inputCoverage)
        } else 0.0
    }

    override fun matchToken(input: String, candidate: String): MatchQuality = getMatchQuality(input, candidate)

    private fun computeCoverage(sourceTokens: List<String>, targetTokens: List<String>, weights: Map<String, Double>): Double {
        val totalWeight = targetTokens.sumOf { weights[it] ?: DEFAULT_WEIGHT }
        if (totalWeight == 0.0) return 0.0

        val usedIndices = mutableSetOf<Int>()
        val matchedWeight = targetTokens.sumOf { target ->
            val matchIndex = findBestMatch(target, sourceTokens, usedIndices)
            if (matchIndex == -1) return@sumOf 0.0
            usedIndices.add(matchIndex)
            weights[target] ?: DEFAULT_WEIGHT
        }

        return matchedWeight / totalWeight
    }

    private fun findBestMatch(targetToken: String, sourceTokens: List<String>, usedIndices: Set<Int>): Int {
        val bestMatch = sourceTokens.withIndex()
            .filter { it.index !in usedIndices }
            .map { indexed -> indexed.index to getMatchQuality(indexed.value, targetToken) }
            .maxByOrNull { it.second.priority }

        return if (bestMatch != null && bestMatch.second.matched()) bestMatch.first else -1
    }

    private fun getMatchQuality(input: String, candidate: String): MatchQuality {
        if (input == candidate) return MatchQuality.EXACT
        val minLen = min(input.length, candidate.length)
        val maxLen = max(input.length, candidate.length)

        if (maxLen < minimumFuzzyMatchLength) return MatchQuality.NONE
        if (minLen >= minimumPrefixLength && (candidate.startsWith(input) || input.startsWith(candidate))) {
            return MatchQuality.PREFIX
        }

        val distance = similarityStrategy.score(input, candidate)
        val maxAllowed = similarityStrategy.maxAllowedDistance(maxLen)
        return if (distance <= maxAllowed) MatchQuality.FUZZY else MatchQuality.NONE
    }

    companion object {
        private const val DEFAULT_WEIGHT = 1.0
    }
}
