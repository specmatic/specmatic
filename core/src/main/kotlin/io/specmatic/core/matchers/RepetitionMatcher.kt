package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.jsonoperator.value.ArrayValueOperator
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

private interface RepetitionStrategyInterface {
    fun isExhausted(path: BreadCrumb, value: Value, context: MatcherContext, times: Int): ReturnValue<Boolean>

    fun tickAgainst(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<MatcherContext>

    fun getItemsArray(path: BreadCrumb, context: MatcherContext): ReturnValue<JSONArrayValue> {
        val pathToLookInto = updatePath(path)
        val valueInShared = context.getSharedValue(pathToLookInto).unwrapOrReturn { return it.cast() }
        return HasValue(valueInShared.getOrElse { JSONArrayValue() } as? JSONArrayValue ?: JSONArrayValue())
    }

    fun storeIntoItemsArray(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<MatcherContext> {
        val pathToStoreInto = updatePath(path).plus(ArrayValueOperator.APPEND.toString())
        return context.storeIntoSharedValue(pathToStoreInto, value)
    }

    fun updatePath(path: BreadCrumb): BreadCrumb = path.plus("Repetition")
}

enum class RepetitionStrategy(val value: String) : RepetitionStrategyInterface {
    ANY("any") {
        override fun isExhausted(path: BreadCrumb, value: Value, context: MatcherContext, times: Int): ReturnValue<Boolean> {
            val itemsArray = getItemsArray(path, context).unwrapOrReturn { return it.cast() }
            return HasValue(itemsArray.list.size >= times)
        }

        override fun tickAgainst(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<MatcherContext> {
            return storeIntoItemsArray(path, NullValue, context)
        }
    },

    EACH("each") {
        override fun isExhausted(path: BreadCrumb, value: Value, context: MatcherContext, times: Int): ReturnValue<Boolean> {
            val itemsArray = getItemsArray(path, context).unwrapOrReturn { return it.cast() }
            return HasValue(itemsArray.list.contains(value))
        }

        override fun tickAgainst(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<MatcherContext> {
            return storeIntoItemsArray(path, value, context)
        }
    };

    companion object {
        fun from(value: String): ReturnValue<RepetitionStrategy> {
            return entries.firstOrNull {
                it.value.equals(value, ignoreCase=true)
            }?.let(::HasValue) ?: HasFailure("Invalid RepetitionStrategy '$value' must be one of any, each")
        }
    }
}

data class RepetitionMatcher(
    private val path: BreadCrumb = BreadCrumb.from(),
    private val times: Int = -1,
    private val strategy: RepetitionStrategy = RepetitionStrategy.ANY,
) : Matcher {
    override val canBeExhausted: Boolean = times != -1

    override fun createDynamicMatchers(context: MatcherContext): List<Matcher> {
        // TODO: Support dynamic matchers based on array length
        return listOf(this)
    }

    override fun rawExecute(context: MatcherContext): MatcherResult {
        if (times == -1) return MatcherResult.Success(context)
        val valueToMatch = context.getValueToMatch(path).unwrapOrReturn {
            return MatcherResult.from("Couldn't extract path $path", path)
        }.getOrNull()

        if (valueToMatch == null) return MatcherResult.from("Couldn't find value at path $path", path)
        val isExhausted = strategy.isExhausted(path, valueToMatch, context, times).unwrapOrReturn {
            return MatcherResult.from(it.cast())
        }

        return if (isExhausted) MatcherResult.Exhausted
        else MatcherResult.from(strategy.tickAgainst(path, valueToMatch, context))
    }

    @MatcherKey("repeat")
    companion object : MatcherFactory {
        private const val TIMES_KEY = "times"
        private const val STRATEGY_KEY = "value"

        override fun parse(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<out Matcher> {
            val properties = extractPropertiesIfExist(value)
            return if (properties.isNullOrEmpty() || !canParseFrom(path, properties)) {
                HasFailure("RepetitionMatcher can not be created from '${value.displayableValue()}'", path.value)
            } else {
                parseFrom(path, properties, context)
            }
        }

        override fun canParseFrom(path: BreadCrumb, properties: Map<String, Value>): Boolean {
            return TIMES_KEY in properties || STRATEGY_KEY in properties
        }

        override fun parseFrom(path: BreadCrumb, properties: Map<String, Value>, context: MatcherContext): ReturnValue<out Matcher> {
            val times = properties.getOrDefault(TIMES_KEY, NumberValue(1))
            if (times !is NumberValue) return HasFailure("Expected key '$TIMES_KEY' to be a number", path.value)

            val strategyKey = properties.getOrDefault(STRATEGY_KEY, StringValue("any"))
            if (strategyKey !is StringValue) return HasFailure("Expected key '$TIMES_KEY' to be a string", path.value)

            val strategy = RepetitionStrategy.from(strategyKey.nativeValue).unwrapOrReturn { return it.cast() }
            val timesInt = times.nativeValue.toInt()
            return HasValue(RepetitionMatcher(path, timesInt, strategy))
        }
    }
}
