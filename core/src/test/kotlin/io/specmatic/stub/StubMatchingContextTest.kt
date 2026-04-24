package io.specmatic.stub

import io.mockk.every
import io.mockk.mockk
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
import io.specmatic.stub.StubMatchingContext.Companion.toStubMatchingContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

internal class StubMatchingContextTest {
    @Test
    fun `should create generative context from invalid matching scenario when generative is enabled`() {
        val feature = featureWithResponses(contractPath = "contracts/generative-enabled.yaml", invalidResponseCode = 422)
        val config = configFor(generativePaths = setOf(feature.path))
        val request = HttpRequest(method = "POST", path = "/hello")

        val context = listOf(feature).toStubMatchingContext(request, config)
        val generativeContext = context.toGenerativeContextOrNull()
        assertThat(generativeContext).isNotNull

        val stubResponse = generativeContext!!.toStubResponse { scenario -> HttpResponse(scenario.status) }
        assertThat(stubResponse.contractPath).isEqualTo(feature.path)
        assertThat(stubResponse.scenario?.status).isEqualTo(422)
        assertThat(stubResponse.response.status).isEqualTo(422)
    }

    @Test
    fun `should not create generative context when generative is disabled`() {
        val config = configFor(generativePaths = emptySet())
        val request = HttpRequest(method = "POST", path = "/hello")
        val feature = featureWithResponses(contractPath = "contracts/generative-disabled.yaml", invalidResponseCode = 422)

        val context = listOf(feature).toStubMatchingContext(request, config)
        assertThat(context.toGenerativeContextOrNull()).isNull()

        val stubResponse = context.toStubResponse(HttpResponse(499))
        assertThat(stubResponse.contractPath).isEqualTo(feature.path)
        assertThat(stubResponse.scenario?.status).isEqualTo(200)
    }

    @Test
    fun `strict mode context should retain only strict matching features`() {
        val nonStrictFeature = featureWithResponses(contractPath = "contracts/non-strict.yaml", successCode = 201)
        val strictFeature = featureWithResponses(contractPath = "contracts/strict.yaml", successCode = 202)
        val config = configFor(strictPaths = setOf(strictFeature.path))
        val request = HttpRequest(method = "POST", path = "/hello")

        val context = listOf(nonStrictFeature, strictFeature).toStubMatchingContext(request, config)
        assertThat(context.isStrictModePossible()).isTrue()

        val strictContext = context.toStrictModeContextOrDefault()
        val stubResponse = strictContext.toStubResponse(HttpResponse(499))
        assertThat(stubResponse.contractPath).isEqualTo(strictFeature.path)
        assertThat(stubResponse.scenario?.status).isEqualTo(202)
    }

    @Test
    fun `strict mode context should fallback to original pool when no strict matching feature exists`() {
        val firstFeature = featureWithResponses(contractPath = "contracts/first.yaml", successCode = 201)
        val secondFeature = featureWithResponses(contractPath = "contracts/second.yaml", successCode = 202)
        val config = configFor(strictPaths = emptySet())
        val request = HttpRequest(method = "POST", path = "/hello")

        val context = listOf(firstFeature, secondFeature).toStubMatchingContext(request, config)
        assertThat(context.isStrictModePossible()).isFalse()

        val strictContext = context.toStrictModeContextOrDefault()
        val stubResponse = strictContext.toStubResponse(HttpResponse(499))
        assertThat(stubResponse.contractPath).isEqualTo(firstFeature.path)
        assertThat(stubResponse.scenario?.status).isEqualTo(201)
    }

    @Test
    fun `empty matching pool should produce empty stub response metadata`() {
        val config = configFor()
        val request = HttpRequest(method = "GET", path = "/unknown")
        val feature = featureWithResponses(contractPath = "contracts/no-match.yaml")

        val context = listOf(feature).toStubMatchingContext(request, config)
        val stubResponse = context.toStubResponse(HttpResponse(418))

        assertThat(context.isStrictModePossible()).isFalse()
        assertThat(context.toGenerativeContextOrNull()).isNull()
        assertThat(stubResponse.contractPath).isEmpty()
        assertThat(stubResponse.scenario).isNull()
        assertThat(stubResponse.feature).isNull()
    }

    private fun featureWithResponses(contractPath: String, successCode: Int = 200, invalidResponseCode: Int? = null) = OpenApiSpecification.fromYAML("""
    openapi: 3.0.3
    info:
      title: Stub Matching Context
      version: 1.0.0
    paths:
      /hello:
        post:
          responses:
            '$successCode':
              description: Success
            ${invalidResponseCode?.let { "'$it':\n                  description: Invalid request" } ?: ""}
    """.trimIndent(), contractPath).toFeature()

    private fun configFor(generativePaths: Set<String> = emptySet(), strictPaths: Set<String> = emptySet()): SpecmaticConfig {
        val config = mockk<SpecmaticConfig>(relaxed = true)
        val normalizedGenerativePaths = generativePaths.map(::normalizePathSeparators).toSet()
        val normalizedStrictPaths = strictPaths.map(::normalizePathSeparators).toSet()

        every { config.getStubGenerative(any()) } answers {
            val path = firstArg<File?>()?.path?.let(::normalizePathSeparators)
            path != null && normalizedGenerativePaths.contains(path)
        }

        every { config.getStubStrictMode(any()) } answers {
            val path = firstArg<File?>()?.path?.let(::normalizePathSeparators)
            path != null && normalizedStrictPaths.contains(path)
        }

        return config
    }

    private fun normalizePathSeparators(path: String): String = path.replace('\\', '/')
}
