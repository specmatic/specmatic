package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.fold.PatternVisitor
import io.specmatic.core.pattern.fold.ScalarPatternCase
import io.specmatic.core.value.Value
import io.specmatic.core.value.StringValue

/** An XML lexical value must validate against a member, not merely parse as one. */
data class XMLUnionPattern(
    val members: List<Pattern>,
    val schemaTypeName: String? = null,
    val memberTypeNames: List<String> = emptyList()
) : Pattern by AnyPattern(members, extensions = emptyMap()), ScalarType {
    override fun <C, R> accept(visitor: PatternVisitor<C, R>, context: C): R =
        visitor.scalar(ScalarPatternCase(pattern = this, context = context))

    override val typeName: String = schemaTypeName?.let { "XSD union '$it'" } ?: "XSD union"

    override fun parse(value: String, resolver: Resolver): Value {
        val failures = members.mapIndexed { index, member ->
            val failure = try {
                val parsed = member.parse(value, resolver)
                val result = member.matches(parsed, resolver)
                if (result.isSuccess()) return parsed
                result
            } catch (e: ContractException) {
                e.failure()
            }
            val memberName = memberTypeNames.getOrNull(index)
            if (memberName != null && failure is Result.Failure)
                Result.Failure("XSD member type '$memberName':", cause = failure)
            else failure
        }
        throw ContractException("Value ${StringValue(value).displayableValue()} does not match $typeName.\n\n" +
            Result.fromResults(failures).reportString().prependIndent("  "))
    }
}
