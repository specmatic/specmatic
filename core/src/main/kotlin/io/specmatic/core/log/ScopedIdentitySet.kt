package io.specmatic.core.log

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Tracks object identity within a scoped execution boundary.
 *
 * Identity semantics matter here because two exceptions with the same message should still be treated
 * as different failures:
 *
 *   val first = ContractException("boom")
 *   val second = ContractException("boom")
 *
 * Even though the messages match, only the exact exception instance that was already logged should be
 * considered "seen". Child threads inherit a copy of the current set so asynchronous work can observe
 * the same scoped state without mutating the parent's backing set directly.
 */
class ScopedIdentitySet<T : Any> {
    private val scopedValues = object : InheritableThreadLocal<MutableSet<T>?>() {
        override fun childValue(parentValue: MutableSet<T>?): MutableSet<T>? {
            return parentValue?.copyIdentitySet()
        }
    }

    fun add(value: T) {
        values().add(value)
    }

    fun anyMatch(candidates: Sequence<T>): Boolean {
        val currentValues = scopedValues.get() ?: return false
        return candidates.any { it in currentValues }
    }

    fun <R> withFreshScope(fn: () -> R): R {
        val old = scopedValues.get()
        scopedValues.set(newIdentitySet())
        return try {
            fn()
        } finally {
            if (old == null) scopedValues.remove() else scopedValues.set(old)
        }
    }

    private fun values(): MutableSet<T> {
        return scopedValues.get() ?: newIdentitySet().also(scopedValues::set)
    }

    private fun newIdentitySet(): MutableSet<T> {
        return Collections.newSetFromMap(IdentityHashMap())
    }

    private fun MutableSet<T>.copyIdentitySet(): MutableSet<T> {
        return newIdentitySet().also { it.addAll(this) }
    }
}
