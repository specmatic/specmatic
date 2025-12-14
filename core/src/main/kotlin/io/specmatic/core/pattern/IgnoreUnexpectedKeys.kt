package io.specmatic.core.pattern

import io.specmatic.core.UnexpectedKeyCheck
import io.specmatic.core.UnexpectedKeyError
import io.specmatic.core.value.StringValue

object IgnoreUnexpectedKeys: UnexpectedKeyCheck {
    override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): UnexpectedKeyError? = null

    override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
        return emptyList()
    }

    override fun validateListCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
        return emptyList()
    }
}