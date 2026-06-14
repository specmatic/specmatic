package io.specmatic.core

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

sealed class NestedQuerySyntaxInferenceResult {
    data object SyntaxNotRequired : NestedQuerySyntaxInferenceResult()
    data class SyntaxInferred(val syntax: ObjectQuerySyntax) : NestedQuerySyntaxInferenceResult()
    data class Failure(val messages: List<String>) : NestedQuerySyntaxInferenceResult()
}

data class NestedQueryParameterExamples(
    val example: String? = null,
    val examples: List<String> = emptyList()
) {
    fun examplesInPrecedenceOrder(): List<String> {
        return listOfNotNull(example) + examples
    }
}

object NestedObjectQuerySyntaxInference {
    fun infer(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        parameterExamples: NestedQueryParameterExamples
    ): NestedQuerySyntaxInferenceResult {
        return infer(parameterName, schema, parameterExamples.examplesInPrecedenceOrder())
    }

    fun infer(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        examples: List<String>
    ): NestedQuerySyntaxInferenceResult {
        if (!schema.requiresSyntaxExamples()) return NestedQuerySyntaxInferenceResult.SyntaxNotRequired

        if (examples.isEmpty()) {
            return NestedQuerySyntaxInferenceResult.Failure(listOf(missingExampleMessage(parameterName)))
        }

        val requiredGuidance = RequiredSyntaxGuidance(
            propertyStyle = schema.requiresNestedPropertyStyle()
        )

        var inferredGuidance = InferredSyntaxGuidance()
        for (keys in examples.map(::queryKeysFrom)) {
            val exampleGuidance = guidanceFrom(parameterName, schema, keys, requiredGuidance)

            when (exampleGuidance) {
                is SyntaxGuidanceResult.Conflict -> return NestedQuerySyntaxInferenceResult.Failure(listOf(exampleGuidance.message))
                SyntaxGuidanceResult.NoGuidance -> continue
                is SyntaxGuidanceResult.Guidance -> {
                    val mergeResult = inferredGuidance.with(exampleGuidance.guidance)
                    when (mergeResult) {
                        is GuidanceMergeResult.Conflict -> return NestedQuerySyntaxInferenceResult.Failure(listOf(mergeResult.message))
                        is GuidanceMergeResult.Merged -> {
                            inferredGuidance = mergeResult.guidance
                            if (inferredGuidance.isComplete(requiredGuidance)) break
                        }
                    }
                }
            }
        }

        return if (inferredGuidance.hasAnyGuidance()) {
            NestedQuerySyntaxInferenceResult.SyntaxInferred(inferredGuidance.toSyntax())
        } else {
            NestedQuerySyntaxInferenceResult.Failure(listOf(missingExampleMessage(parameterName)))
        }
    }

    private fun guidanceFrom(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        keys: List<String>,
        requiredGuidance: RequiredSyntaxGuidance
    ): SyntaxGuidanceResult {
        val relevantKeys = keys.filter { key -> key.couldBelongTo(parameterName, schema) }
        if (relevantKeys.isEmpty()) return SyntaxGuidanceResult.NoGuidance

        var exampleGuidance = InferredSyntaxGuidance()
        relevantKeys.forEach { key ->
            val keyGuidance = guidanceFromKey(parameterName, schema, key, requiredGuidance)
                ?: return SyntaxGuidanceResult.Conflict(unparseableNestedQueryKeyMessage(parameterName, key))
            val mergeResult = exampleGuidance.with(keyGuidance)
            when (mergeResult) {
                is GuidanceMergeResult.Conflict -> return SyntaxGuidanceResult.Conflict(mergeResult.message)
                is GuidanceMergeResult.Merged -> exampleGuidance = mergeResult.guidance
            }
        }

        return if (exampleGuidance.hasAnyGuidance()) {
            SyntaxGuidanceResult.Guidance(exampleGuidance)
        } else {
            SyntaxGuidanceResult.NoGuidance
        }
    }

    private fun guidanceFromKey(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        key: String,
        requiredGuidance: RequiredSyntaxGuidance
    ): InferredSyntaxGuidance? {
        val candidates = candidatesFor(parameterName, schema, listOf(key))
        if (candidates.isEmpty()) return null

        return InferredSyntaxGuidance(
            root = ObjectQueryRoot.explicitRootFor(key, parameterName)?.let { InferredValue(it, key) },
            propertyStyle = candidates.singlePropertyStyleOrNull(requiredGuidance)?.let { InferredValue(it, key) },
            sawParseableExample = true
        )
    }

    private fun candidatesFor(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        keys: List<String>
    ): List<ObjectQuerySyntax> {
        return ObjectQuerySyntax.supportedSyntaxes().filter { syntax ->
            canParseAll(parameterName, schema, syntax, keys)
        }
    }

    private fun canParseAll(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        syntax: ObjectQuerySyntax,
        keys: List<String>
    ): Boolean {
        val relevantKeys = keys.filter { key -> key.couldBelongTo(parameterName, schema) }
        if (relevantKeys.isEmpty()) return false

        return runCatching {
            relevantKeys.forEach { key -> ObjectQueryKeyParser.parseWithSchema(key, parameterName, schema, syntax) }
        }.isSuccess
    }

    private fun String.couldBelongTo(parameterName: String, schema: NestedQuerySchema.Object): Boolean {
        return schema.couldOwnQueryKey(this, parameterName)
    }

    private fun queryKeysFrom(example: String): List<String> {
        return example.split("&")
            .map { it.substringBefore("=") }
            .map { key -> URLDecoder.decode(key, StandardCharsets.UTF_8) }
            .filter(String::isNotBlank)
    }

    private fun missingExampleMessage(parameterName: String): String {
        return "No example of query parameter $parameterName demonstrates how nested properties should be serialized as query parameters."
    }

    private fun unparseableNestedQueryKeyMessage(parameterName: String, key: String): String {
        return "Example of query parameter $parameterName contains nested query key \"$key\" that could not be parsed with any supported nested query syntax."
    }
}

private data class RequiredSyntaxGuidance(
    val propertyStyle: Boolean
)

private data class InferredValue<T>(
    val value: T,
    val key: String
)

private data class InferredSyntaxGuidance(
    val root: InferredValue<ObjectQueryRoot>? = null,
    val propertyStyle: InferredValue<QueryPropertyStyle>? = null,
    val sawParseableExample: Boolean = false
) {
    fun with(other: InferredSyntaxGuidance): GuidanceMergeResult {
        val mergedRoot = when (val result = mergeGuidance(root, other.root, ::conflictingRootStyleMessage)) {
            is MergeValueResult.Conflict -> return GuidanceMergeResult.Conflict(result.message)
            is MergeValueResult.Merged -> result.value
        }

        val mergedPropertyStyle = when (val result = mergeGuidance(propertyStyle, other.propertyStyle, ::conflictingPropertyStyleMessage)) {
            is MergeValueResult.Conflict -> return GuidanceMergeResult.Conflict(result.message)
            is MergeValueResult.Merged -> result.value
        }

        return GuidanceMergeResult.Merged(copy(
            root = mergedRoot,
            propertyStyle = mergedPropertyStyle,
            sawParseableExample = sawParseableExample || other.sawParseableExample
        ))
    }

    fun isComplete(requiredGuidance: RequiredSyntaxGuidance): Boolean {
        return !requiredGuidance.propertyStyle || propertyStyle != null
    }

    fun hasAnyGuidance(): Boolean {
        return sawParseableExample || root != null || propertyStyle != null
    }

    fun toSyntax(): ObjectQuerySyntax {
        return ObjectQuerySyntax.Default.copy(
            root = root?.value ?: ObjectQuerySyntax.Default.root,
            propertyStyle = propertyStyle?.value ?: ObjectQuerySyntax.Default.propertyStyle
        )
    }
}

private sealed class SyntaxGuidanceResult {
    data object NoGuidance : SyntaxGuidanceResult()
    data class Guidance(val guidance: InferredSyntaxGuidance) : SyntaxGuidanceResult()
    data class Conflict(val message: String) : SyntaxGuidanceResult()
}

private sealed class GuidanceMergeResult {
    data class Merged(val guidance: InferredSyntaxGuidance) : GuidanceMergeResult()
    data class Conflict(val message: String) : GuidanceMergeResult()
}

private sealed class MergeValueResult<out T> {
    data class Merged<T>(val value: InferredValue<T>?) : MergeValueResult<T>()
    data class Conflict(val message: String) : MergeValueResult<Nothing>()
}

private fun <T> mergeGuidance(
    existing: InferredValue<T>?,
    candidate: InferredValue<T>?,
    conflictMessage: (InferredValue<T>, InferredValue<T>) -> String
): MergeValueResult<T> {
    return when {
        existing == null -> MergeValueResult.Merged(candidate)
        candidate == null || existing.value == candidate.value -> MergeValueResult.Merged(existing)
        else -> MergeValueResult.Conflict(conflictMessage(existing, candidate))
    }
}

private fun conflictingRootStyleMessage(existing: InferredValue<ObjectQueryRoot>, candidate: InferredValue<ObjectQueryRoot>): String {
    return "Examples use conflicting root serialization styles for nested query parameters: \"${existing.key}\" uses ${existing.value.displayName}, but \"${candidate.key}\" uses ${candidate.value.displayName}."
}

private fun conflictingPropertyStyleMessage(existing: InferredValue<QueryPropertyStyle>, candidate: InferredValue<QueryPropertyStyle>): String {
    return "Examples use conflicting property serialization styles for nested query parameters: \"${existing.key}\" uses ${existing.value.displayName}, but \"${candidate.key}\" uses ${candidate.value.displayName}."
}

private fun List<ObjectQuerySyntax>.singlePropertyStyleOrNull(requiredGuidance: RequiredSyntaxGuidance): QueryPropertyStyle? {
    if (!requiredGuidance.propertyStyle) return null

    return map(ObjectQuerySyntax::propertyStyle).distinct().singleOrNull()
}
