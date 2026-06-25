package io.specmatic.test.fixtures

import io.specmatic.core.Substitution
import io.specmatic.core.value.Value
import io.specmatic.test.FixtureExecutionDetails

interface OpenAPIFixtureExecutor {
    fun execute(
        id: String,
        fixtures: List<Value>,
        fixtureDiscriminatorKey: String,
        substitution: Substitution
    ): FixtureExecutionDetails
}
