package io.specmatic.core

interface KeyErrorCheck {
    fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): MissingKeyError?
    fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError>
    fun validateListCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError>
}
