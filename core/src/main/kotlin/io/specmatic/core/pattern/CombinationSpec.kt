package io.specmatic.core.pattern

/**
 * Provides utility access to all combinations of multiple sets of candidate values. Represented as the cartesian
 * product of all sets where each combination is represented as a numerical index from 0 to (MAX_COMBOS - 1).
 *
 * Supports sequential iteration over all combinations, which is used to test as many combinations as possible.
 *
 * Supports translating a combination of candidate values to an index, which is used to first test priority combinations
 * (and to remember these when later iterating remaining combinations to not double-include these priority combinations)
 *
 * See <a href="https://softwareengineering.stackexchange.com/questions/228478/testing-all-combinations"> this
 * stackoverflow article</a> accepted answer. A notable difference is that our representation is reversed such that
 * a sequential iteration produces all combinations with the first candidate value of the first set before producing
 * all combinations using the second candidate value of the first set, and so on for each subsequent value and set.
 *
 * Note: This code now returns a sequence instead of a list, and no longer calculates up-front the max size of any
 * sequence. There are significant changes to how it works. However, it continues to prioritise combinations in the
 * same way as before.
 */
class CombinationSpec<ValueType>(
    keyToCandidatesOrig: Map<String, Sequence<ReturnValue<ValueType>>>,
    maxCombinations: Int
) {
    companion object {
        fun <ValueType> from(keyToCandidates: Map<String, Sequence<ValueType>>, maxCombinations: Int): CombinationSpec<ValueType> {
            return CombinationSpec(keyToCandidates.mapValues { it.value.map { HasValue(it) } }, maxCombinations)
        }
    }

    val keyToCandidates: Map<String, Sequence<ReturnValue<ValueType>>> = keyToCandidatesOrig

    init {
        if (maxCombinations < 1) throw IllegalArgumentException("maxCombinations must be > 0 and <= ${Int.MAX_VALUE}")
    }

    val selectedCombinations: Sequence<ReturnValue<Map<String, ValueType>>> = toSelectedCombinations(keyToCandidates, maxCombinations)

    fun <ValueType> toSelectedCombinations(rawPatternCollection: Map<String, Sequence<ReturnValue<ValueType>>>, maxCombinations: Int): Sequence<ReturnValue<Map<String, ValueType>>> {
        val iterators = rawPatternCollection.mapValues { it.value.iterator() }.filterValues { it.hasNext() }
        if (iterators.isEmpty()) return emptySequence()

        val cachedValues = iterators.mapValues { mutableListOf<ReturnValue<ValueType>>() }
        val prioritisedGenerations = mutableSetOf<ReturnValue<Map<String, ValueType>>>()
        val ranOut = cachedValues.mapValues { false }.toMutableMap()

        return sequence {
            var ctr = 0

            while (true) {
                val nextValue: Map<String, ReturnValue<ValueType>> = iterators.mapValues { (key, iterator) ->
                    val nextValueFromIterator = if (iterator.hasNext()) {
                        val value = iterator.next()

                        cachedValues.getValue(key).add(value)

                        value
                    } else {
                        ranOut[key] = true

                        val cachedValuesForKey = cachedValues.getValue(key)
                        val value = cachedValuesForKey[ctr % cachedValuesForKey.size]

                        value
                    }

                    nextValueFromIterator
                }

                val _nextValue: ReturnValue<Map<String, ValueType>> = nextValue.mapFold()

                if(ranOut.all { it.value })
                    break

                ctr ++

                yield(_nextValue)
                prioritisedGenerations.add(_nextValue)

                if(prioritisedGenerations.size == maxCombinations)
                    break
            }

            if(prioritisedGenerations.size == maxCombinations)
                return@sequence

            val otherPatterns: Sequence<ReturnValue<Map<String, ValueType>>> = allCombinations(rawPatternCollection).map { it.mapFold() }

            val maxCountOfUnPrioritisedGenerations = maxCombinations - prioritisedGenerations.size

            // TODO Handle equality between return values to ignore the messages and breadcrumbs
            val filtered = otherPatterns.filter { it !in prioritisedGenerations }
            val limited = filtered.take(maxCountOfUnPrioritisedGenerations)

            yieldAll(limited)
        }
    }

    private fun <ValueType> allCombinations(patternCollection: Map<String, Sequence<ValueType>>): Sequence<Map<String, ValueType>> {
        if(patternCollection.isEmpty())
            return sequenceOf(emptyMap())

        val entry = patternCollection.entries.first()

        val subsequentGenerations: Sequence<Map<String, ValueType>> = allCombinations(patternCollection - entry.key)

        return entry.value.flatMap { value ->
            subsequentGenerations.map { mapOf(entry.key to value) + it }
        }
    }
}
