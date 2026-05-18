package io.specmatic.core

import io.specmatic.core.pattern.ReferencedPatterns

interface ResponseValueAssertion {
    fun collectReferences(references: ReferencedPatterns) {}
    fun matches(response: HttpResponse, resolver: Resolver): Result
}
