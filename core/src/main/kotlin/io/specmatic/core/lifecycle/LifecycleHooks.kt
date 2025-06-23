package io.specmatic.core.lifecycle

object LifecycleHooks {
    val afterLoadingStaticExamples: AfterLoadingStaticExamplesHooks = AfterLoadingStaticExamplesHooks()
    val requestResponseMatchingScenarioHooks: RequestResponseMatchingScenarioHooks = RequestResponseMatchingScenarioHooks()
}
