package io.specmatic.core

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.value.Value

interface Substitution {
    fun isDropDirective(value: Value): Boolean
    fun resolveIfLookup(value: Value, pattern: Pattern): Value
    fun substitute(value: Value, pattern: Pattern, key: String?): ReturnValue<Value>
    fun upsertStoreUsing(originalValue: Value, runningValue: Value): Substitution = this
}
