package io.specmatic.stub

import io.specmatic.core.HttpRequest

internal class TransientStubCoordinator(
    private val transactionLock: Any,
    private val canonicalRoot: ThreadSafeListOfStubs,
    private val freshCandidateView: () -> ThreadSafeListOfStubs,
) {
    constructor(canonicalRoot: ThreadSafeListOfStubs) : this(
        transactionLock = Any(),
        canonicalRoot = canonicalRoot,
        freshCandidateView = { canonicalRoot },
    )

    fun associatedTo(baseUrl: String, defaultBaseUrl: String, urlPath: String): TransientStubCoordinator {
        return TransientStubCoordinator(
            canonicalRoot = canonicalRoot,
            transactionLock = transactionLock,
            freshCandidateView = { canonicalRoot.stubAssociatedTo(baseUrl, defaultBaseUrl, urlPath) },
        )
    }

    fun remove(httpStubData: HttpStubData) {
        canonicalRoot.remove(httpStubData)
    }

    fun <T> withMatchingTransientStub(
        httpRequest: HttpRequest,
        initialCandidateView: ThreadSafeListOfStubs,
        onMatch: (HttpStubData) -> T
    ): T? {
        // Perf: Reuse the initial view instead of calling freshCandidateView() for the pre-check; non-candidates then skip the transaction lock.
        if (!initialCandidateView.hasPotentialTransientMatch(httpRequest)) return null
        synchronized(transactionLock) {
            val authoritativeCandidateView = freshCandidateView()
            val match = authoritativeCandidateView.matchingTransientStub(httpRequest) ?: return null
            return onMatch(match.first)
        }
    }
}
