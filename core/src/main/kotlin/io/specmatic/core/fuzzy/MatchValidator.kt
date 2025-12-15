package io.specmatic.core.fuzzy

data class ValidationContext(
    val inputKey: String,
    val inputTokens: List<String>,
    val candidateKey: String,
    val candidateTokens: List<String>,
    val model: FuzzyModel,
    val scorer: SimilarityScorer
)

interface MatchValidator {
    fun isValid(context: ValidationContext): Boolean
}

class DiscriminatorValidator: MatchValidator {
    override fun isValid(context: ValidationContext): Boolean {
        val requiredTokens = context.model.keyDiscriminators[context.candidateKey] ?: return true
        return requiredTokens.all { required ->
            context.inputTokens.any { context.scorer.tokensMatch(it, required) }
        }
    }
}

class SuffixValidator: MatchValidator {
    override fun isValid(context: ValidationContext): Boolean {
        val candidateSuffix = context.candidateTokens.lastOrNull() ?: return true
        if (!context.model.strictSuffixes.contains(candidateSuffix)) return true
        val inputSuffix = context.inputTokens.lastOrNull() ?: return false
        return context.scorer.tokensMatch(inputSuffix, candidateSuffix)
    }
}

class CompositeValidator(private val validators: List<MatchValidator>) : MatchValidator {
    override fun isValid(context: ValidationContext): Boolean {
        return validators.all { it.isValid(context) }
    }
}
