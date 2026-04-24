package io.specmatic.stub

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.invalidRequestStatuses
import java.io.File

sealed interface StubMatchingContext {
    class Default internal constructor(private val featurePool: List<FeatureContext>) : StubMatchingContext {
        fun isStrictModePossible(): Boolean {
            return featurePool.isNotEmpty() && featurePool.any { it.isStrictModeEnabled }
        }

        fun toStrictModeContextOrDefault(): Default {
            return Default(featurePool.filter { it.isStrictModeEnabled }.ifEmpty { featurePool })
        }

        fun toGenerativeContextOrNull(): Generative? {
            val generativeFeatureContexts = featurePool.filterIsInstance<FeatureContext.Generative>()
            if (generativeFeatureContexts.isEmpty()) return null
            return Generative(generativeFeatureContexts)
        }

        fun toStubResponse(response: HttpResponse): HttpStubResponse {
            val context = featurePool.firstOrNull()
            return HttpStubResponse(scenario = context?.firstMatching, response = response, feature = context?.feature, contractPath = context?.feature?.path.orEmpty())
        }

        companion object {
            fun from(features: List<Feature>, httpRequest: HttpRequest, specmaticConfig: SpecmaticConfig): Default {
                val matchingFeatures = features.mapNotNull { it.toMatchingContext(httpRequest, specmaticConfig) }
                return Default(matchingFeatures)
            }
        }
    }

    class Generative internal constructor(private val featurePool: List<FeatureContext.Generative>) : StubMatchingContext {
        fun toStubResponse(block: (Scenario) -> HttpResponse): HttpStubResponse {
            val context = featurePool.firstOrNull() ?: throw IllegalStateException("Stub feature pool cannot be empty for Generative Context")
            return HttpStubResponse(scenario = context.firstMatchingInvalid, response = block(context.firstMatchingInvalid), feature = context.feature, contractPath = context.feature.path)
        }
    }

    companion object {
        fun List<Feature>.toStubMatchingContext(httpRequest: HttpRequest, specmaticConfig: SpecmaticConfig): Default {
            return Default.from(this, httpRequest, specmaticConfig)
        }

        private fun Feature.toMatchingContext(
            httpRequest: HttpRequest,
            specmaticConfig: SpecmaticConfig
        ): FeatureContext? {
            val isGenerativeEnabled = specmaticConfig.getStubGenerative(File(path))
            val accumulator = MatchingAccumulator(isGenerativeEnabled)
            val result = scenarios.fold(accumulator) { acc, scenario -> acc.add(scenario, httpRequest) }
            return result.toFeatureContext(this, specmaticConfig)
        }
    }
}

internal sealed interface FeatureContext {
    val feature: Feature
    val firstMatching: Scenario
    val isStrictModeEnabled: Boolean

    data class Default(
        override val feature: Feature,
        override val firstMatching: Scenario,
        override val isStrictModeEnabled: Boolean = false,
    ) : FeatureContext

    data class Generative(
        override val feature: Feature,
        val firstMatchingInvalid: Scenario,
        override val firstMatching: Scenario,
        override val isStrictModeEnabled: Boolean = false
    ) : FeatureContext
}

private data class MatchingAccumulator(val isGenerativeEnabled: Boolean, val firstMatching: Scenario? = null, val firstMatchingInvalid: Scenario? = null) {
    private val isDone: Boolean get() = firstMatching != null && (!isGenerativeEnabled || firstMatchingInvalid != null)

    fun add(scenario: Scenario, httpRequest: HttpRequest): MatchingAccumulator {
        if (isDone || !scenario.matchesRequest(httpRequest)) return this
        return copy(
            firstMatching = firstMatching ?: scenario,
            firstMatchingInvalid = firstMatchingInvalid ?: scenario.takeIf { it.status in invalidRequestStatuses }
        )
    }

    private fun Scenario.matchesRequest(httpRequest: HttpRequest): Boolean {
        return httpRequestPattern
            .matchesPathStructureMethodAndContentType(httpRequest, resolver)
            .isSuccess()
    }

    fun toFeatureContext(feature: Feature, specmaticConfig: SpecmaticConfig): FeatureContext? {
        val firstMatching = firstMatching ?: return null
        val isStrictModeEnabled = specmaticConfig.getStubStrictMode(File(feature.path)) ?: false

        if (isGenerativeEnabled && firstMatchingInvalid != null) {
            return FeatureContext.Generative(
                feature = feature,
                firstMatching = firstMatching,
                isStrictModeEnabled = isStrictModeEnabled,
                firstMatchingInvalid = firstMatchingInvalid,
            )
        }

        return FeatureContext.Default(
            feature = feature,
            firstMatching = firstMatching,
            isStrictModeEnabled = isStrictModeEnabled,
        )
    }
}
