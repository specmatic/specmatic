package io.specmatic.stub

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.ContractException
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

class TransientStubCoordinatorTest {
    private val request = HttpRequest("GET", "/orders")

    @Nested
    inner class TransactionBoundary {
        @Test
        fun `an unrelated static request proceeds while a transient response is being built`() {
            val transientRequest = HttpRequest("POST", "/orders")
            val staticRequest = HttpRequest("GET", "/health")

            val expectations = HttpExpectations(
                transient = mutableListOf(transientStub(request = transientRequest)),
                static = mutableListOf(transientStub(request = staticRequest, token = null)),
            )

            val firstEntered = CountDownLatch(1)
            val allowFirstToFinish = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit(Callable {
                    expectations.withMatchingStub(transientRequest) {
                        firstEntered.countDown()
                        allowFirstToFinish.await(5, SECONDS)
                        "first"
                    }.first
                })

                assertThat(firstEntered.await(5, SECONDS)).isTrue()
                val unrelated = executor.submit(Callable {
                    getHttpResponse(
                        strictMode = false,
                        features = emptyList(),
                        httpRequest = staticRequest,
                        httpExpectations = expectations,
                    )
                })

                assertThat(unrelated.get(200, MILLISECONDS)).isInstanceOf(FoundStubbedResponse::class.java)
                allowFirstToFinish.countDown()
                assertThat(first.get(5, SECONDS)).isEqualTo("first")
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `the response builder consumes a transient mock after construction`() {
            val stub = transientStub()
            val expectations = HttpExpectations(
                static = mutableListOf(),
                transient = mutableListOf(stub),
            )

            val result = getHttpResponse(
                strictMode = false,
                httpRequest = request,
                features = emptyList(),
                httpExpectations = expectations,
            )

            assertThat(result).isInstanceOf(FoundStubbedResponse::class.java)
            assertThat(expectations.transientStubCount).isZero()
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        fun `an external response failure does not utilize a transient mock`() {
            val response = HttpResponse(status = 200, body = "ok").copy(externalisedResponseCommand = "echo invalid")
            val expectations = HttpExpectations(static = mutableListOf(), transient = mutableListOf(transientStub(response)))
            assertThatThrownBy {
                getHttpResponse(
                    httpRequest = request,
                    features = emptyList(),
                    httpExpectations = expectations,
                    strictMode = false,
                )
            }

            assertThat(expectations.transientStubCount).isEqualTo(1)
        }

        @Test
        fun `the selected static mock is utilized once after response construction`() {
            val expectations = spyk(HttpExpectations(static = mutableListOf(transientStub(token = null))))
            val result = getHttpResponse(
                strictMode = false,
                httpRequest = request,
                features = emptyList(),
                httpExpectations = expectations,
            )

            assertThat(result).isInstanceOf(FoundStubbedResponse::class.java)
            verify(exactly = 1) { expectations.utilizeMock(any()) }
        }

        @Test
        fun `the selected dynamic mock is utilized once after response construction`() {
            val registeredStub = spyk(transientStub(token = null))
            val expectations = spyk(HttpExpectations(static = mutableListOf()))

            expectations.addDynamic(
                stub = registeredStub.scenarioStub!!,
                expectation = Result.Success() to registeredStub,
            )

            val result = getHttpResponse(
                strictMode = false,
                httpRequest = request,
                features = emptyList(),
                httpExpectations = expectations,
            )

            assertThat(result).isInstanceOf(FoundStubbedResponse::class.java)
            verify(exactly = 1) { expectations.utilizeMock(any()) }
        }
    }

    @Nested
    inner class StatefulMatcherProgression {
        @Test
        fun `a committed one-shot stateful transient cannot be matched by a waiting request`() {
            val matchEnteredBySecondRequest = CountDownLatch(1)
            val allowFirstResponse = CountDownLatch(1)
            val matcher = fakeStatefulMatcher(
                initialCount = 1,
                onMatch = { count ->
                    if (count == 0) matchEnteredBySecondRequest.countDown()
                },
            )

            val expectations = HttpExpectations(mutableListOf(), mutableListOf(matcher.stub))
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit(Callable {
                    expectations.withMatchingStub(
                        httpRequest = request,
                        onMatch = {
                            allowFirstResponse.await(5, SECONDS)
                            "first"
                        },
                    ).first
                })

                matcher.firstMatchEntered.await(5, SECONDS)
                val second = executor.submit(Callable {
                    expectations.withMatchingStub(request) { "second" }.first
                })

                assertThat(matchEnteredBySecondRequest.await(200, MILLISECONDS)).isFalse()
                allowFirstResponse.countDown()

                assertThat(first.get(5, SECONDS)).isEqualTo("first")
                assertThat(second.get(5, SECONDS)).isNull()
                assertThat(matcher.committedCount()).isZero()
                assertThat(matchEnteredBySecondRequest.await(5, SECONDS)).isTrue()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `an abandoned staged match is rolled back by the next request`() {
            val matcher = fakeStatefulMatcher(initialCount = 1)
            val expectations = HttpExpectations(mutableListOf(), mutableListOf(matcher.stub))

            assertThatThrownBy {
                expectations.withMatchingStub(request) { throw ContractException("abandoned response") }
            }.isInstanceOf(ContractException::class.java)

            assertThat(
                expectations.withMatchingStub(httpRequest = request, onMatch = { "response" }).first,
            ).isEqualTo("response")
            assertThat(matcher.committedCount()).isZero()
        }

        @Test
        fun `successive requests consume a stateful transient countdown in order`() {
            val matcher = fakeStatefulMatcher(initialCount = 2)
            val expectations = HttpExpectations(mutableListOf(), mutableListOf(matcher.stub))

            assertThat(
                expectations.withMatchingStub(httpRequest = request, onMatch = { "first" }).first
            ).isEqualTo("first")

            assertThat(matcher.committedCount()).isEqualTo(1)
            assertThat(
                expectations.withMatchingStub(httpRequest = request, onMatch = { "second" }).first
            ).isEqualTo("second")

            assertThat(matcher.committedCount()).isZero()
            assertThat(
                expectations.withMatchingStub(httpRequest = request, onMatch = { "third" }).first
            ).isNull()
        }
    }

    @Nested
    inner class CanonicalRegistrationState {
        @Test
        fun `a waiting one-shot request cannot serve a stale transient registration`() {
            val expectations = HttpExpectations(static = mutableListOf(), transient = mutableListOf(transientStub()))

            val firstEntered = CountDownLatch(1)
            val allowFirstToFinish = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit(Callable {
                    expectations.withMatchingStub(
                        httpRequest = request,
                        onMatch = {
                            firstEntered.countDown()
                            allowFirstToFinish.await(5, SECONDS)
                            "first"
                        },
                    ).first
                })

                assertThat(firstEntered.await(5, SECONDS)).isTrue()
                val second = executor.submit(Callable {
                    expectations.withMatchingStub(request) {
                        secondEntered.countDown()
                        "second"
                    }.first
                })

                assertThat(secondEntered.await(200, MILLISECONDS)).isFalse()
                allowFirstToFinish.countDown()

                assertThat(first.get(5, SECONDS)).isEqualTo("first")
                assertThat(second.get(5, SECONDS)).isNull()
                assertThat(expectations.transientStubCount).isZero()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `waiting matching re-reads canonical registrations instead of a stale view`() {
            val stub = transientStub()
            val canonical = ThreadSafeListOfStubs(mutableListOf(stub), emptyMap())
            val staleView = canonical.stubAssociatedTo(
                urlPath = "",
                baseUrl = "http://localhost:8080",
                defaultBaseUrl = "http://localhost:8080",
            )

            canonical.remove(stub)
            val result = TransientStubCoordinator(
                root = canonical,
                freshView = {
                    canonical.stubAssociatedTo(
                        urlPath = "",
                        baseUrl = "http://localhost:8080",
                        defaultBaseUrl = "http://localhost:8080",
                    )
                },
            ).withMatchingTransientStub<String>(request, staleView) {
                error("the consumed registration must not be matched")
            }

            assertThat(result).isNull()
        }

        @Test
        fun `independent associated views serialize consumption of one transient registration`() {
            val stub = transientStub()
            val expectations = HttpExpectations(static = mutableListOf(), transient = mutableListOf(stub))
            val associatedExpectations = List(2) {
                expectations.associatedTo(
                    urlPath = "/orders",
                    baseUrl = "http://localhost:8080",
                    defaultBaseUrl = "http://localhost:8080",
                )
            }

            val bothRequestsAreReady = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            val selections = try {
                associatedExpectations.map { expectationsForRequest ->
                    executor.submit(Callable {
                        bothRequestsAreReady.await(5, SECONDS)
                        expectationsForRequest.withMatchingStub(request) { it }.first
                    })
                }.map { it.get(5, SECONDS) }
            } finally {
                executor.shutdownNow()
            }

            assertThat(selections.filterNotNull()).hasSize(1)
            assertThat(selections.filterNotNull().single()).isSameAs(stub)
            assertThat(expectations.transientStubCount).isZero
        }

        @Test
        fun `removing a selected equal registration removes that registration by identity`() {
            val first = transientStub()
            val second = first.copy()
            val expectations = HttpExpectations(
                static = mutableListOf(),
                transient = mutableListOf(first, second),
            )

            val selected = expectations.withMatchingStub(httpRequest = request, onMatch = { it }).first
            assertThat(selected).isSameAs(second)
            assertThat(expectations.transientStubCount).isEqualTo(1)

            val nextSelected = expectations.withMatchingStub(request, onMatch = { it }).first
            assertThat(nextSelected).isSameAs(first)
            assertThat(expectations.transientStubCount).isZero()
        }

        @Test
        fun `utilizing a registration appearing twice consumes one occurrence at a time`() {
            val stub = transientStub()
            val expectations = HttpExpectations(
                static = mutableListOf(),
                transient = mutableListOf(stub, stub),
            )

            assertThat(expectations.withMatchingStub(request) { it }.first).isSameAs(stub)
            assertThat(expectations.transientStubCount).isEqualTo(1)

            assertThat(expectations.withMatchingStub(request) { it }.first).isSameAs(stub)
            assertThat(expectations.transientStubCount).isZero()
        }
    }

    private fun transientStub(
        response: HttpResponse = HttpResponse(status = 200, body = "ok"),
        token: String? = "transient",
        request: HttpRequest = this@TransientStubCoordinatorTest.request
    ): HttpStubData {
        return HttpStubData(
            response = response,
            resolver = Resolver(),
            requestType = request.toPattern(),
            responsePattern = HttpResponsePattern(response),
            scenarioStub = ScenarioStub(request = request, response = response, stubToken = token),
        )
    }

    private fun fakeStatefulMatcher(
        initialCount: Int,
        onMatch: (Int) -> Unit = {},
    ): FakeStatefulMatcher {
        return FakeStatefulMatcher(initialCount, onMatch)
    }

    private class FakeStatefulMatcher(initialCount: Int, onMatch: (Int) -> Unit) {
        private val stateLock = Any()
        private var committed = initialCount
        private var staged = initialCount
        val firstMatchEntered = CountDownLatch(1)
        val stub = mockk<HttpStubData>()

        init {
            every { stub.matchesRequestPattern(any()) } returns Result.Success()
            every { stub.responsePattern } returns HttpResponsePattern(HttpResponse.OK)
            every { stub.hasCompleteAuthoredSecurityRequirement() } returns false
            every { stub.matches(any()) } answers {
                synchronized(stateLock) {
                    firstMatchEntered.countDown()
                    staged = committed
                    onMatch(committed)
                    if (committed == 0) {
                        Result.Failure("exhausted")
                    } else {
                        staged = committed - 1
                        Result.Success()
                    }
                }
            }

            every { stub.utilize() } answers {
                synchronized(stateLock) {
                    committed = staged
                    false
                }
            }
        }

        fun committedCount(): Int = synchronized(stateLock) { committed }
    }
}
