package io.specmatic.core

import io.specmatic.core.pattern.ReferencedPatterns

data class ValueAssertion(val expectedExactResponsePattern: HttpResponsePattern) : ResponseValueAssertion {
    override fun collectReferences(references: ReferencedPatterns) {
        expectedExactResponsePattern.collectReferences(references)
    }

    override fun matches(response: HttpResponse, resolver: Resolver): Result {
        return expectedExactResponsePattern.matchesResponse(response, resolver)
    }
}
