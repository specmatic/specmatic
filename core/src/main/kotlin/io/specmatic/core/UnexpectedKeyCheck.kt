package io.specmatic.core

interface UnexpectedKeyCheck {
    fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): UnexpectedKeyError?
    fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError>
    fun validateListCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError>
}
