package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.patternMismatchResult
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

data class RegexConstrainedPattern(
    val basePattern: Pattern,
    val regex: String,
    private val resolver: Resolver,
    private val eagerRegexValidation: Boolean = true,
) : Pattern by basePattern, ScalarType {
    private val regexPattern = StringPattern(regex = regex)

    init {
        if (eagerRegexValidation) this.validateRegexAgainstBasePattern()
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData?.hasSupportedTemplate() == true) return Result.Success()
        val baseResult = basePattern.matches(sampleData, resolver)
        if (baseResult is Result.Failure) return baseResult
        val stringValue = sampleData?.toStringLiteral()?.let(::StringValue) ?: return baseResult
        return regexPattern.matches(stringValue, resolver)
    }

    override fun generate(resolver: Resolver): Value {
        val candidate = regexPattern.generate(resolver)
        return try {
            basePattern.parse(candidate.toStringLiteral(), resolver)
        } catch(_: Throwable) {
            throw ContractException("The provided regex '$regex' could not generate a value of type '${basePattern.typeName}'")
        }
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        return when (val resolvedOther = resolvedHop(otherPattern, otherResolver)) {
            is RegexConstrainedPattern -> {
                val otherFitsThis = matches(resolvedOther.generate(otherResolver), thisResolver)
                if (otherFitsThis is Result.Success) Result.Success()
                else {
                    val thisFitsOther = resolvedOther.matches(generate(thisResolver), otherResolver)
                    if (thisFitsOther is Result.Success) Result.Success()
                    else patternMismatchResult(this, otherPattern, thisResolver.mismatchMessages)
                }
            }
            else -> basePattern.encompasses(resolvedOther, thisResolver, otherResolver, typeStack)
        }
    }

    override fun patternFrom(value: Value, resolver: Resolver, parseValueToType: (Value) -> Pattern): Pattern {
        return RegexConstrainedPattern(basePattern.patternFrom(value, resolver, parseValueToType), this.regex, resolver)
    }

    private fun validateRegexAgainstBasePattern() {
        this.generate(resolver)
    }
}