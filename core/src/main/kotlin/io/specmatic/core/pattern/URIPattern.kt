package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.dataTypeMismatchResult
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.net.URI

data class URIPattern(override val typeAlias: String? = null) : Pattern, ScalarType {
    override val pattern: String = "(uri)"

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        return resultOf {
            when {
                sampleData is StringValue && parse(sampleData.string, resolver).string == sampleData.string -> Result.Success()
                else -> dataTypeMismatchResult(this, sampleData, resolver.mismatchMessages)
            }
        }
    }

    override fun generate(resolver: Resolver): StringValue {
        val providedString = resolver.provideString(this)
        return providedString ?: StringValue("https://${randomString().lowercase()}.com/${randomString().lowercase()}")
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(this))

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> = sequenceOf(this)

    override fun negativeBasedOn(
        row: Row,
        resolver: Resolver,
        config: io.specmatic.core.pattern.config.NegativePatternConfiguration
    ): Sequence<ReturnValue<Pattern>> = newBasedOn(row, resolver)

    override fun parse(value: String, resolver: Resolver): StringValue =
        attemptParse(this, value, resolver.mismatchMessages) {
            val parsed = URI.create(value)
            if (!parsed.isAbsolute) throw IllegalArgumentException("Expected an absolute URI")
            StringValue(parsed.toString())
        }

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        return when (otherPattern) {
            this -> Result.Success()
            is URIPattern -> Result.Success()
            else -> Result.Failure("Expected $typeName, got ${otherPattern.typeName}")
        }
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value = JSONArrayValue(valueList)

    override val typeName: String = "uri"
}
