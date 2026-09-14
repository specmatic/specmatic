package io.specmatic.stub

import io.specmatic.core.HttpRequest

internal class TransientStubCoordinator(
    private val root: ThreadSafeListOfStubs,
    private val transactionLock: Any = Any(),
    private val freshView: () -> ThreadSafeListOfStubs = { root },
) {
    fun associatedTo(baseUrl: String, defaultBaseUrl: String, urlPath: String): TransientStubCoordinator {
        return TransientStubCoordinator(
            root = root,
            transactionLock = transactionLock,
            freshView = { root.stubAssociatedTo(baseUrl, defaultBaseUrl, urlPath) },
        )
    }

    fun remove(httpStubData: HttpStubData) {
        root.remove(httpStubData)
    }

    fun <T> withMatchingTransientStub(
        httpRequest: HttpRequest,
        initialTransientView: ThreadSafeListOfStubs,
        onMatch: (HttpStubData) -> T
    ): T? {
        if (!initialTransientView.hasPotentialTransientMatch(httpRequest)) return null
        synchronized(transactionLock) {
            val authoritativeTransientView = freshView()
            val match = authoritativeTransientView.matchingTransientStub(httpRequest) ?: return null
            return onMatch(match.first)
        }
    }
}
