package io.specmatic.core

interface KeyCheck {
    val isPartial: Boolean
    val isExtensible: Boolean

    fun disableOverrideUnexpectedKeyCheck(): KeyCheck

    fun withUnexpectedKeyCheck(unexpectedKeyCheck: UnexpectedKeyCheck): KeyCheck

    fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): KeyError?

    fun validateAll(pattern: Map<String, Any>, actual: Map<String, Any>): List<KeyError>

    fun validateAllCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<KeyError>

    fun toPartialKeyCheck(): KeyCheck
}

val DefaultKeyCheck = DefaultKeyCheckImpl()
