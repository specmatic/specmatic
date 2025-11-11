package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.listFold
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class MatcherRegistry(
    private val keyedFactories: ConcurrentHashMap<String, MatcherFactory> = ConcurrentHashMap<String, MatcherFactory>(),
    private val fallbackFactories: CopyOnWriteArrayList<MatcherFactory> = CopyOnWriteArrayList<MatcherFactory>()
) {
    fun parse(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<out Matcher>? {
        val lastKey = path.last().value
        if (keyedFactories.containsKey(lastKey)) {
            val factory = keyedFactories.getValue(lastKey)
            return factory.parse(path, value, context)
        }

        val matcherValueDetails = extractMatcherFromValue(value)
        if (matcherValueDetails != null && keyedFactories.containsKey(matcherValueDetails.first)) {
            val factory = keyedFactories.getValue(matcherValueDetails.first)
            return factory.parse(path, matcherValueDetails.second, context)
        }

        return fallbackFactories.firstNotNullOfOrNull { factory ->
            factory.parse(path, value, context).withDefault(null) { it }
        }?.let(::HasValue)
    }

    fun parse(path: BreadCrumb, properties: Map<String, Value>, context: MatcherContext): ReturnValue<List<Matcher>> {
        return keyedFactories.values.plus(fallbackFactories).filter {
            it.canParseFrom(path, properties)
        }.map {
            it.parseFrom(path, properties, context)
        }.listFold()
    }

    fun toPatternSimplified(value: Value): Pattern? {
        val matcherValueDetails = extractMatcherFromValue(value)
        if (matcherValueDetails != null && keyedFactories.containsKey(matcherValueDetails.first)) {
            val factory = keyedFactories.getValue(matcherValueDetails.first)
            return factory.toPatternSimplified(matcherValueDetails.second)
        }

        return fallbackFactories.firstNotNullOfOrNull { factory ->
            factory.toPatternSimplified(value)
        }
    }

    fun toPatternSimplified(properties: Map<String, Value>): Pattern? {
        val patterns = keyedFactories.values.plus(fallbackFactories).filter {
            it.canParseFrom(BreadCrumb.from(), properties)
        }.mapNotNull {
            it.toPatternSimplified(properties)
        }

        return when (patterns.size){
            0 -> null
            1 -> patterns.single()
            // TODO: Should ideally be an allOf Pattern
            else -> AnyPattern(patterns, extensions = emptyMap())
        }
    }

    companion object {
        private const val MATCHER_PREFIX = "$"
        private val MATCHER_PATTERN = Regex("^\\$(\\w+)\\((.*)\\)$")

        fun build(defaults: List<MatcherFactory>): MatcherRegistry {
            val keyToFactory = ConcurrentHashMap<String, MatcherFactory>()
            val fallbackFactories = CopyOnWriteArrayList<MatcherFactory>()

            ServiceLoader.load(MatcherFactory::class.java).plus(defaults).forEach { factory ->
                val annotation = factory::class.java.getAnnotation(MatcherKey::class.java)
                if (annotation != null) {
                    keyToFactory[annotation.key] = factory
                } else {
                    fallbackFactories.add(factory)
                }
            }

            return MatcherRegistry(keyToFactory, fallbackFactories)
        }

        fun extractMatcherFromValue(value: Value): Pair<String, StringValue>? {
            if (value !is StringValue) return null
            if (!value.string.startsWith(MATCHER_PREFIX)) return null

            val match = MATCHER_PATTERN.find(value.string) ?: return null
            val matcherKey = match.groupValues[1]
            val argsString = match.groupValues[2]

            val argsValue = StringValue(argsString)
            return matcherKey to argsValue
        }
    }
}
