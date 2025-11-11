package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

private interface RepetitionStrategyInterface {
    fun isExhausted(path: BreadCrumb, value: Value, context: MatcherContext, times: Int): ReturnValue<Boolean>

    fun tickAgainst(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<MatcherContext>

    fun getItemsArray(path: BreadCrumb, context: MatcherContext): ReturnValue<JSONArrayValue> {
        val pathToLookInto = updatePath(path)
        return context.getJsonArray(pathToLookInto)
    }

    fun storeIntoItemsArray(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<MatcherContext> {
        val pathToStoreInto = updatePath(path)
        return context.appendToJsonArray(pathToStoreInto, value)
    }

    fun updatePath(path: BreadCrumb): BreadCrumb = path.plus("RepetitionMatcher")
}

enum class RepetitionStrategy(val value: String) : RepetitionStrategyInterface {
    ANY("any") {
        private val markerValue = StringValue("*")

        override fun isExhausted(path: BreadCrumb, value: Value, context: MatcherContext, times: Int): ReturnValue<Boolean> {
            val itemsArray = getItemsArray(path, context).unwrapOrReturn { return it.cast() }
            return HasValue(itemsArray.list.size >= times)
        }

        override fun tickAgainst(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<MatcherContext> {
            return storeIntoItemsArray(path, markerValue, context.addToCurrentExhaustionChecks(path, markerValue))
        }
    },

    EACH("each") {
        override fun isExhausted(path: BreadCrumb, value: Value, context: MatcherContext, times: Int): ReturnValue<Boolean> {
            val itemsArray = getItemsArray(path, context).unwrapOrReturn { return it.cast() }
            val matchingItems = itemsArray.list.filter { it == value }
            return HasValue(matchingItems.size >= times)
        }

        override fun tickAgainst(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<MatcherContext> {
            return storeIntoItemsArray(path, value, context.addToCurrentExhaustionChecks(path, value))
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
    val path: BreadCrumb = BreadCrumb.from(),
    val times: Int = -1,
    val strategy: RepetitionStrategy = RepetitionStrategy.ANY,
) : Matcher {
    override val canBeExhausted: Boolean = times != -1

    override fun createDynamicMatchers(context: MatcherContext): List<Matcher> {
        // TODO: Support dynamic matchers based on array length
        return listOf(this)
    }

    override fun rawExecute(context: MatcherContext): MatcherResult {
        if (times == -1) return MatcherResult.Success(context)
        val updatedContext = context.withUpdatedTimes(times)

        val valueToMatch = updatedContext.getValueToMatch(path).unwrapOrReturn {
            return MatcherResult.from("Couldn't extract path $path", path, updatedContext)
        }.getOrNull()

        if (valueToMatch == null) return MatcherResult.from("Couldn't find value at path $path", path, updatedContext)
        val isExhausted = strategy.isExhausted(path, valueToMatch, context, times).unwrapOrReturn {
            return MatcherResult.from(it.cast(), updatedContext)
        }

        val strategyUpdatedContext = strategy.tickAgainst(path, valueToMatch, updatedContext)
        return MatcherResult.from(strategyUpdatedContext, updatedContext, isExhausted = isExhausted)
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
