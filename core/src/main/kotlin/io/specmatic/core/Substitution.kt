package io.specmatic.core

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.substitution.InterpolatedSubstitution
import io.specmatic.core.value.Value

interface Substitution {
    fun isDropDirective(value: Value): Boolean
    fun substitute(value: Value): ReturnValue<Value>
    fun substitute(value: Value, pattern: Pattern, resolver: Resolver = Resolver(), key: String? = null): ReturnValue<Value>
    fun upsertStoreUsing(originalValue: Value, runningValue: Value, resolver: Resolver = Resolver()): Substitution

    companion object {
        fun isOrContainsLookup(text: String): Boolean = InterpolatedSubstitution.containsLookup(text)
        fun isOrContainsCapture(text: String): Boolean = InterpolatedSubstitution.containsCapture(text)
    }
}
