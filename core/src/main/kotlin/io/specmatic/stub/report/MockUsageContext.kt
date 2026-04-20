package io.specmatic.stub.report

import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord

data class MockUsageContext(
    val tests: List<TestResultRecord>,
    val allSpecEndpoints: List<StubEndpoint>,
) {
    fun specOperations(): Set<OpenAPIOperation> {
        return allSpecEndpoints.map { it.toOpenApiOperation() }.toSet()
    }

    fun allCoverageOperations(): Set<OpenAPIOperation> {
        return specOperations() + tests.map { it.toMockUsageOperation() }.toSet()
    }
}
