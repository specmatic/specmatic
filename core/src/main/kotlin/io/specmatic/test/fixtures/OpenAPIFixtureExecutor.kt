package io.specmatic.test.fixtures

import io.specmatic.core.Result
import io.specmatic.core.value.Value
import io.specmatic.test.FixtureExecutionResultRecords

interface OpenAPIFixtureExecutor {
    fun execute(id: String, fixtures: List<Value>, fixtureDiscriminatorKey: String): FixtureExecutionResultRecords
}