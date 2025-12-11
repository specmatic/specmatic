package io.specmatic.core.fuzzy

data class FuzzyModel(
    val validKeys: Set<String> = emptySet(),
    val tokenWeights: Map<String, Double> = emptyMap(),
    val strictSuffixes: Set<String> = emptySet(),
    val keyDiscriminators: Map<String, Set<String>> = emptyMap()
)

sealed interface FuzzyMatchResult {
    data class ExactMatch(val key: String) : FuzzyMatchResult
    data class FuzzyMatch(val key: String, val score: Double) : FuzzyMatchResult
    data object NoMatch: FuzzyMatchResult
}

class FuzzyMatcher(
    private val model: FuzzyModel,
    private val threshold: Double = 0.60,
    private val scorer: SimilarityScorer = WeightedSimilarityScorer(),
    private val tokenizer: StringTokenizer = StringTokenizer.StandardTokenizer(),
    private val normalizer: StringNormalizer = StringNormalizer.AlphanumericNormalizer(),
    validators: List<MatchValidator> = listOf(DiscriminatorValidator(), SuffixValidator())
) {
    private val normalizedKeyMap: Map<String, String> by lazy { model.validKeys.associateBy { normalizer.normalize(it) } }
    private val tokenizedKeys by lazy { model.validKeys.associateWith { tokenizer.tokenize(it) } }
    private val compositeValidator = CompositeValidator(validators)

    fun match(inputKey: String): FuzzyMatchResult {
        if (inputKey.isBlank()) return FuzzyMatchResult.NoMatch
        if (inputKey in model.validKeys) return FuzzyMatchResult.ExactMatch(inputKey)
        normalizedKeyMap[normalizer.normalize(inputKey)]?.let { return FuzzyMatchResult.FuzzyMatch(it, 1.0) }

        val inputTokens = tokenizer.tokenize(inputKey)
        if (inputTokens.isEmpty()) return FuzzyMatchResult.NoMatch
        return findBestFuzzyMatch(inputKey, inputTokens)
    }

    private fun findBestFuzzyMatch(inputKey: String, inputTokens: List<String>): FuzzyMatchResult {
        return tokenizedKeys.asSequence()
            .map { (candidateKey, candidateTokens) -> evaluateCandidate(candidateKey, candidateTokens, inputTokens) }
            .filter { it.score > threshold }
            .filter { isValidCandidate(inputKey, inputTokens, it.key, it.tokens) }
            .maxByOrNull { it.score }
            ?.let { FuzzyMatchResult.FuzzyMatch(it.key, it.score) }
            ?: FuzzyMatchResult.NoMatch
    }

    private fun isValidCandidate(inputKey: String, inputTokens: List<String>, candidateKey: String, candidateTokens: List<String>): Boolean {
        val context = ValidationContext(inputKey, inputTokens, candidateKey, candidateTokens, model, scorer)
        return compositeValidator.isValid(context)
    }

    private fun evaluateCandidate(candidateKey: String, candidateTokens: List<String>, inputTokens: List<String>): ScoredCandidate {
        val score = scorer.score(inputTokens, candidateTokens, model.tokenWeights)
        return ScoredCandidate(candidateKey, candidateTokens, score)
    }

    private data class ScoredCandidate(val key: String, val tokens: List<String>, val score: Double)

    companion object {
        class FuzzyBuilder {
            private var validKeys: Set<String> = emptySet()
            private var threshold = 0.60
            private var tokenizer: StringTokenizer = StringTokenizer.StandardTokenizer()
            private var normalizer: StringNormalizer = StringNormalizer.AlphanumericNormalizer()
            private var scorer: SimilarityScorer = WeightedSimilarityScorer()
            private var validators: MutableList<MatchValidator> = mutableListOf(DiscriminatorValidator(), SuffixValidator())

            fun fromKeys(keys: Set<String>) = apply { this.validKeys = keys }
            fun withThreshold(threshold: Double) = apply { this.threshold = threshold }
            fun withTokenizer(tokenizer: StringTokenizer) = apply { this.tokenizer = tokenizer }
            fun withNormalizer(normalizer: StringNormalizer) = apply { this.normalizer = normalizer }
            fun withScorer(scorer: SimilarityScorer) = apply { this.scorer = scorer }
            fun withValidator(validator: MatchValidator) { this.validators.add(validator) }

            fun build(): FuzzyMatcher {
                val model = FuzzyLearner(keys = validKeys, similarityThreshold = threshold, scorer = scorer, tokenizer = tokenizer).learn()
                return FuzzyMatcher(model, threshold, scorer, tokenizer, normalizer, validators)
            }
        }

        fun fuzzyMatcher(block: FuzzyBuilder.() -> Unit) : FuzzyMatcher {
            val builder = FuzzyBuilder()
            block(builder)
            return builder.build()
        }
    }
}
