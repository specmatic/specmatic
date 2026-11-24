package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

data class XMLListPattern(
    override val pattern: Pattern,
    val schemaTypeName: String? = null,
    val itemTypeName: String? = null
) : Pattern, ScalarType {
    override val typeAlias: String? = null
    override val typeName: String = schemaTypeName?.let { "XSD list '$it'" } ?: "XSD list"

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData !is StringValue) return Result.Failure("Expected whitespace-separated text for $typeName")
        val items = sampleData.string.trim(' ', '\t', '\r', '\n')
            .takeIf(String::isNotEmpty)?.split(Regex("[ \\t\\r\\n]+")) ?: emptyList()
        return Result.fromResults(items.mapIndexed { index, item ->
            val result = try {
                pattern.matches(pattern.parse(item, resolver), resolver)
            } catch (e: ContractException) {
                e.failure()
            }
            val itemDescription = itemTypeName?.let { "XSD item type '$it'" } ?: "the declared XSD item type"
            val explained = if (result is Result.Failure)
                result.copy(causes = listOf(Result.FailureCause(
                    "Value ${StringValue(item).displayableValue()} in $typeName must match $itemDescription. " +
                        result.removeViolationReport().toFailureReport().errorMessage()
                )))
            else result
            explained.breadCrumb("[$index]")
        })
    }

    override fun generate(resolver: Resolver): Value = StringValue(
        List(2) { resolver.generate(pattern).toStringLiteral() }.joinToString(" ")
    )

    override fun parse(value: String, resolver: Resolver): Value = StringValue(value)
    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(this))
    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> = sequenceOf(this)
    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> = emptySequence()
    override fun listOf(valueList: List<Value>, resolver: Resolver): Value = JSONArrayValue(valueList)

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result =
        when (otherPattern) {
            is XMLListPattern -> pattern.encompasses(otherPattern.pattern, thisResolver, otherResolver, typeStack)
            is ExactValuePattern -> matches(otherPattern.pattern, thisResolver)
            else -> Result.Failure("Expected $typeName, got ${otherPattern.typeName}")
        }
}
