package io.specmatic.test.fixtures

import io.specmatic.core.Result
import io.specmatic.core.value.Value

interface OpenAPIFixtureExecutor {
    fun execute(id: String, fixtures: List<Value>, fixtureDiscriminatorKey: String): Result
}