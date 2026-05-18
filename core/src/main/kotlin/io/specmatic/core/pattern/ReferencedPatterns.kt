package io.specmatic.core.pattern

import java.util.Collections
import java.util.IdentityHashMap

class ReferencedPatterns(private val availablePatterns: Map<String, Pattern>) {
    private val references = linkedMapOf<String, Pattern>()
    private val visitedReferences = mutableSetOf<String>()
    private val visitedPatterns = Collections.newSetFromMap(IdentityHashMap<Pattern, Boolean>())

    fun add(pattern: Pattern) {
        if (visitedPatterns.add(pattern)) pattern.collectReferences(this)
    }

    fun addAll(patterns: Iterable<Pattern>) {
        patterns.forEach(::add)
    }

    fun addReference(patternName: String) {
        val normalizedPatternName = patternName.trim()
        if (!visitedReferences.add(normalizedPatternName)) return

        val referencedPattern = availablePatterns[normalizedPatternName] ?: return
        references[normalizedPatternName] = referencedPattern
        add(referencedPattern)
    }

    fun toMap(): Map<String, Pattern> = references.toMap()
}
