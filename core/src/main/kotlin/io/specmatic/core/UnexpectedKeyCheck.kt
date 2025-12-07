package io.specmatic.core

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.StringValue

interface UnexpectedKeyCheck {
    fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): KeyError?
    fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<KeyError>
    fun validateListCaseInsensitive(pattern: Map<String, Pattern>, actual: Map<String, StringValue>): List<KeyError>
}