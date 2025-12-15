package io.specmatic.core.fuzzy

import kotlin.math.ln

class FuzzyLearner(
    private val keys: Set<String>,
    private val similarityThreshold: Double = 0.65,
    private val suffixMinFrequencyRatio: Double = 0.05,
    private val scorer: SimilarityScorer = WeightedSimilarityScorer(),
    private val tokenizer: StringTokenizer = StringTokenizer.StandardTokenizer(),
) {
    fun learn(): FuzzyModel {
        val tokenizedKeys = keys.associateWith { tokenizer.tokenize(it) }
        val tokenWeights = computeTokenWeights(tokenizedKeys)
        val commonSuffixes = findCommonSuffixes(tokenizedKeys)
        val distinctiveTokens = findDistinctiveTokens(tokenizedKeys, tokenWeights)
        return FuzzyModel(keys, tokenWeights, commonSuffixes, distinctiveTokens)
    }

    private fun computeTokenWeights(tokenizedKeys: Map<String, List<String>>): Map<String, Double> {
        val totalKeys = tokenizedKeys.size
        if (totalKeys == 0) return emptyMap()
        return tokenizedKeys.values.flatMap(List<String>::distinct).groupingBy { it }.eachCount()
            .mapValues { (_, count) -> ln(totalKeys.toDouble() / count) + 1.0 }
    }

    private fun findCommonSuffixes(tokenizedKeys: Map<String, List<String>>): Set<String> {
        val totalKeys = tokenizedKeys.size
        val minCount = maxOf(2, (totalKeys * suffixMinFrequencyRatio).toInt())
        return tokenizedKeys.values.filter(List<String>::isNotEmpty).map(List<String>::last)
            .groupingBy { it }.eachCount()
            .filter { (_, count) -> count >= minCount }
            .keys
    }

    private fun findDistinctiveTokens(tokenizedKeys: Map<String, List<String>>, tokenWeights: Map<String, Double>): Map<String, Set<String>> {
        val keysList = tokenizedKeys.keys.toList()
        val similarPairs = findSimilarPairs(keysList, tokenizedKeys, tokenWeights)
        return similarPairs.flatMap { pair ->
            listOf(pair.key1 to pair.uniqueToKey1, pair.key2 to pair.uniqueToKey2)
        }.filter { (_, uniqueTokens) ->
            uniqueTokens.isNotEmpty()
        }.groupBy({ it.first }, { it.second }).mapValues { (_, tokenSets) ->
            tokenSets.flatten().toSet()
        }
    }

    private fun findSimilarPairs(keysList: List<String>, tokenizedKeys: Map<String, List<String>>, tokenWeights: Map<String, Double>): List<SimilarPair> {
        val tokenIndex = buildTokenIndex(tokenizedKeys)
        return keysList.flatMap { key1 ->
            getCandidates(key1, tokenizedKeys, tokenIndex).mapNotNull { key2 ->
                if (key1 >= key2) return@mapNotNull null
                checkIfSimilar(key1, key2, tokenizedKeys, tokenWeights)
            }
        }
    }

    private fun buildTokenIndex(tokenizedKeys: Map<String, List<String>>): Map<String, Set<String>> {
        return tokenizedKeys.flatMap { (key, tokens) -> tokens.map { token -> token to key } }.groupBy(
            keySelector = { it.first }, valueTransform = { it.second }
        ).mapValues { it.value.toSet() }
    }

    private fun getCandidates(key: String, tokenizedKeys: Map<String, List<String>>, tokenIndex: Map<String, Set<String>>): Set<String> {
        val tokens = tokenizedKeys[key] ?: return emptySet()
        return tokens.flatMap { tokenIndex[it].orEmpty() }.toSet() - key
    }

    private fun checkIfSimilar(key1: String, key2: String, tokenizedKeys: Map<String, List<String>>, tokenWeights: Map<String, Double>): SimilarPair? {
        val tokens1 = tokenizedKeys[key1] ?: return null
        val tokens2 = tokenizedKeys[key2] ?: return null

        val similarity = scorer.score(tokens1, tokens2, tokenWeights)
        if (similarity <= similarityThreshold) return null

        val uniqueToKey1 = findUniqueTokens(tokens1, tokens2)
        val uniqueToKey2 = findUniqueTokens(tokens2, tokens1)
        return SimilarPair(key1, uniqueToKey1, key2, uniqueToKey2)
    }

    private fun findUniqueTokens(sourceTokens: List<String>, targetTokens: List<String>): Set<String> {
        return (sourceTokens.toSet() - targetTokens.toSet()).filter { it.length >= 3 }.toSet()
    }

    private data class SimilarPair(
        val key1: String, val uniqueToKey1: Set<String>,
        val key2: String, val uniqueToKey2: Set<String>
    )
}
