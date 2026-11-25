package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.Substitution
import io.specmatic.core.log.logger
import io.specmatic.core.substitution.SubstitutionImpl
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.mock.AFTER_FIXTURES
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.ctrf.model.FixtureExecutionResult
import io.specmatic.test.fixtures.FixtureExecutionMetadata
import io.specmatic.test.fixtures.FixtureScenarioType
import io.specmatic.test.fixtures.OpenAPIFixtureExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.File
import java.util.ServiceLoader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

internal class StubAfterFixtureRunner(private val config: SpecmaticConfig, private val strictMode: Boolean) {
    private val fixtureClassLoader = Thread.currentThread().contextClassLoader

    fun start(request: HttpRequest, served: HttpStubResponse, response: HttpResponse): CompletableFuture<List<FixtureExecutionResult>?>? {
        val stub = served.mock ?: return null
        val example = stub.scenarioStub ?: return null
        if (example.afterFixtures.isEmpty()) return null
        val exampleIdentifier = when {
            example.id != null -> "with id '${example.id}'"
            example.name != null -> "with name '${example.name}'"
            else -> example.nameOrFileName ?: "<unnamed>"
        }

        return CompletableFuture.supplyAsync(
            /* supplier = */ { execute(request, response, stub, example, exampleIdentifier) },
            /* executor = */ Dispatchers.IO.asExecutor(),
        ).exceptionally { error ->
            val cause = (error as? CompletionException)?.cause ?: error
            logger.log(cause, "After fixtures failed for mock example $exampleIdentifier")
            listOf(AfterFixtureFailure(exceptionCauseMessage(cause)))
        }
    }

    private fun execute(request: HttpRequest, response: HttpResponse, stub: HttpStubData, example: ScenarioStub, exampleIdentifier: String): List<FixtureExecutionResult>? {
        val executor = ServiceLoader.load(OpenAPIFixtureExecutor::class.java, fixtureClassLoader).firstOrNull()
            ?: return null

        val id = example.id.orEmpty()
        val execution = executor.execute(
            id = id,
            data = example.data,
            fixtures = example.afterFixtures,
            fixtureDiscriminatorKey = AFTER_FIXTURES,
            substitution = substitutionFor(request, response, stub, example),
            executionMetadata = FixtureExecutionMetadata(
                scenarioType = stub.scenario?.let(FixtureScenarioType::from) ?: FixtureScenarioType.POSITIVE,
                specmaticConfig = stub.feature?.specmaticConfig ?: config,
            ),
        )

        if (!execution.combinedResult.isSuccess()) {
            logger.log("After fixtures failed for mock example $exampleIdentifier: ${execution.combinedResult.reportString()}")
        }

        return execution.fixtureExecutionResults
    }

    private fun substitutionFor(request: HttpRequest, response: HttpResponse, stub: HttpStubData, example: ScenarioStub): Substitution {
        val resolver = stub.resolver.copy(mockMode = true)
        val requestSubstitution = SubstitutionImpl.from(
            data = example.data,
            resolver = resolver,
            runningRequest = request,
            originalRequest = stub.resolveOriginalRequest() ?: example.requestElsePartialRequest(),
            strictMode = config.getStubStrictMode(stub.feature?.path?.let(::File)) ?: strictMode,
        )

        return requestSubstitution.upsertStoreUsing(
            resolver = resolver,
            runningValue = response.toFixtureBindingJson(),
            originalValue = example.responseElsePartialResponse().toFixtureBindingJson(),
        )
    }

    private data class AfterFixtureFailure(override val errorMessage: String) : FixtureExecutionResult {
        override val fixtureNumber = ""
        override val fixtureKind = AFTER_FIXTURES
    }

    private fun HttpResponse.toFixtureBindingJson(): JSONObjectValue {
        return copy(headers = headers.mapKeys { it.key.lowercase() }).toJSON()
    }
}
