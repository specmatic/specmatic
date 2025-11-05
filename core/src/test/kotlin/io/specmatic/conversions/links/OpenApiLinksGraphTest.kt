package io.specmatic.conversions.links

import io.specmatic.conversions.OperationMetadata
import io.specmatic.core.DEFAULT_RESPONSE_CODE
import io.specmatic.core.HttpPathPattern
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Scenario
import io.specmatic.core.ScenarioInfo
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenApiLinksGraphTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("validGraphScenarios")
    fun `should sort scenarios based on dependency graph`(description: String, links: List<OpenApiLink>, scenarios: List<Scenario>, expectedOrder: List<String>) {
        val graph = OpenApiLinksGraph.from(links.shuffled())
        assertThat(graph).isInstanceOf(HasValue::class.java)

        val result = (graph as HasValue).value.sortScenariosBasedOnDependency(scenarios)
        assertThat(result).isInstanceOf(HasValue::class.java)

        val sorted = (result as HasValue).value
        val actualOrder = sorted.map { it.operationMetadata?.operationId ?: "${it.path}:${it.method}" }
        assertThat(actualOrder).isEqualTo(expectedOrder)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cyclicGraphScenarios")
    fun `should detect cycles during graph construction`(description: String, links: List<OpenApiLink>, expectedCycleCount: Int) {
        val result = OpenApiLinksGraph.from(links.shuffled())
        assertThat(result).isInstanceOf(HasFailure::class.java)

        val error = (result as HasFailure).toFailure().reportString()
        assertThat(error).contains("circular dependencies")
        assertThat(error).contains("$expectedCycleCount")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("multipleStatusCodeScenarios")
    fun `should sort scenarios with multiple status codes correctly`(description: String, links: List<OpenApiLink>, scenarios: List<Scenario>, expectedGroups: List<List<String>>) {
        val graph = OpenApiLinksGraph.from(links.shuffled())
        val result = (graph as HasValue).value.sortScenariosBasedOnDependency(scenarios)

        val sorted = (result as HasValue).value
        val actualOrder = sorted.map {
            it.operationMetadata?.operationId?.let { id -> "$id:${it.status}"} ?: "${it.path}:${it.method}:${it.status}"
        }

        var currentIndex = 0
        expectedGroups.forEach { group ->
            val groupInResult = actualOrder.subList(currentIndex, currentIndex + group.size)
            assertThat(groupInResult).containsExactlyInAnyOrderElementsOf(group)
            currentIndex += group.size
        }
    }

    @Test
    fun `should handle empty links and scenarios`() {
        val graph = OpenApiLinksGraph.from(emptyList())
        assertThat(graph).isInstanceOf(HasValue::class.java)

        val result = (graph as HasValue).value.sortScenariosBasedOnDependency(emptyList())
        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEmpty()
    }

    @Test
    fun `should handle scenarios with no matching links`() {
        val scenarios = listOf(
            scenarioWith("/api/users", "GET", "200", "getUsers"),
            scenarioWith("/api/posts", "GET", "200", "getPosts")
        )

        val graph = OpenApiLinksGraph.from(emptyList())
        val result = (graph as HasValue).value.sortScenariosBasedOnDependency(scenarios)

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).hasSize(2)
    }

    @Test
    fun `should handle disconnected scenario groups`() {
        val links = listOf(
            link(ref("/users", "POST", "201", "createUser"), ref("/users/{id}", "GET", "200", "getUser")),
            link(ref("/posts", "POST", "201", "createPost"), ref("/posts/{id}", "GET", "200", "getPost"))
        )

        val scenarios = listOf(
            scenarioWith("/users", "POST", "201", "createUser"),
            scenarioWith("/users/(id:string)", "GET", "200", "getUser"),
            scenarioWith("/posts", "POST", "201", "createPost"),
            scenarioWith("/posts/(id:string)", "GET", "200", "getPost"),
        )

        val graph = OpenApiLinksGraph.from(links.shuffled())
        val result = (graph as HasValue).value.sortScenariosBasedOnDependency(scenarios)
        assertThat(result).isInstanceOf(HasValue::class.java)

        val sorted = (result as HasValue).value
        val userCreateIndex = sorted.indexOfFirst { it.operationMetadata?.operationId == "createUser" }
        val userGetIndex = sorted.indexOfFirst { it.operationMetadata?.operationId == "getUser" }
        val postCreateIndex = sorted.indexOfFirst { it.operationMetadata?.operationId == "createPost" }
        val postGetIndex = sorted.indexOfFirst { it.operationMetadata?.operationId == "getPost" }

        assertThat(userCreateIndex).isLessThan(userGetIndex)
        assertThat(postCreateIndex).isLessThan(postGetIndex)
    }

    @Test
    fun `should maintain stable sort order across multiple invocations`() {
        val scenarios = listOf(
            scenarioWith("/z", "GET", "200", "opZ"),
            scenarioWith("/a", "GET", "200", "opA"),
            scenarioWith("/m", "GET", "200", "opM")
        )

        val graph = OpenApiLinksGraph.from(emptyList())
        val results = (1..10).map {
            val r = (graph as HasValue).value.sortScenariosBasedOnDependency(scenarios)
            (r as HasValue).value.map { it.operationMetadata?.operationId }
        }

        results.forEach { assertThat(it).isEqualTo(results[0]) }
    }

    @Test
    fun `should distinguish case-sensitive path variations`() {
        val links = listOf(
            link(ref("/API/Users", "POST", "201", "createUpper"), ref("/API/Users/{id}", "GET", "200", "getUpper"))
        )

        val scenarios = listOf(
            scenarioWith("/API/Users", "POST", "201", "createUpper"),
            scenarioWith("/API/Users/(id:string)", "GET", "200", "getUpper"),
            scenarioWith("/api/users", "POST", "201", "createLower")
        )

        val graph = OpenApiLinksGraph.from(links)
        val result = (graph as HasValue).value.sortScenariosBasedOnDependency(scenarios)

        val sorted = (result as HasValue).value
        val upperCreateIdx = sorted.indexOfFirst { it.operationMetadata?.operationId == "createUpper" }
        val upperGetIdx = sorted.indexOfFirst { it.operationMetadata?.operationId == "getUpper" }

        assertThat(upperCreateIdx).isLessThan(upperGetIdx)
    }

    @Timeout(2, unit = TimeUnit.SECONDS)
    @Test
    fun `should handle chain of large operations efficiently`() {
        val length = 500
        val links = (0 until length - 1).map { i ->
            link(ref("/op$i", "POST", "200", "op$i"), ref("/op${i+1}", "POST", "200", "op${i+1}"))
        }

        val scenarios = (0 until length).map { i -> scenarioWith("/op$i", "POST", "200", "op$i") }.shuffled()
        val graph = OpenApiLinksGraph.from(links.shuffled())
        val result = (graph as HasValue).value.sortScenariosBasedOnDependency(scenarios)

        val sorted = (result as HasValue).value
        val order = sorted.map { it.operationMetadata?.operationId }
        assertThat(order).isEqualTo((0 until length).map { "op$it" })
    }

    @Test
    fun `should mention specific operations in cycle error message`() {
        val links = listOf(
            link(ref("/alpha", "POST", "201", "alphaOp"), ref("/beta", "PUT", "200", "betaOp")),
            link(ref("/beta", "PUT", "200", "betaOp"), ref("/alpha", "POST", "201", "alphaOp"))
        )

        val result = OpenApiLinksGraph.from(links)
        assertThat(result).isInstanceOf(HasFailure::class.java)

        val error = (result as HasFailure).toFailure().reportString()
        assertThat(error).contains("circular dependencies")
        assertThat(error.lowercase()).containsAnyOf("alpha", "beta")
    }

    @Test
    fun `should group scenarios by operation and maintain status code order within groups`() {
        val links = listOf(
            link(ref("/products", "POST", "201", "createProduct"), ref("/products", "GET", "200", "listProducts")),

            link(ref("/products", "GET", "200", "listProducts"), ref("/products/{id}", "GET", "200", "getProduct")),
            link(ref("/products", "GET", "200", "listProducts"), ref("/products/{id}", "GET", "404", "getProduct")),

            link(ref("/products/{id}", "GET", "200", "getProduct"), ref("/products/{id}", "PATCH", "200", "updateProduct")),
            link(ref("/products/{id}", "GET", "200", "getProduct"), ref("/products/{id}", "PATCH", "404", "updateProduct")),

            link(ref("/products/{id}", "PATCH", "200", "updateProduct"), ref("/products/{id}", "DELETE", "200", "deleteProduct")),
            link(ref("/products/{id}", "PATCH", "200", "updateProduct"), ref("/products/{id}", "DELETE", "404", "deleteProduct"))
        )

        val scenarios = listOf(
            scenarioWith("/products/(id:number)", "GET", "200", "getProduct"),
            scenarioWith("/products/(id:number)", "GET", "404", "getProduct"),
            scenarioWith("/products/(id:number)", "GET", "400", "getProduct"),
            scenarioWith("/products/(id:number)", "PATCH", "200", "updateProduct"),
            scenarioWith("/products/(id:number)", "PATCH", "404", "updateProduct"),
            scenarioWith("/products/(id:number)", "PATCH", "400", "updateProduct"),
            scenarioWith("/products/(id:number)", "DELETE", "200", "deleteProduct"),
            scenarioWith("/products/(id:number)", "DELETE", "404", "deleteProduct"),
            scenarioWith("/products/(id:number)", "DELETE", "400", "deleteProduct"),
            scenarioWith("/products", "POST", "201", "createProduct"),
            scenarioWith("/products", "POST", "400", "createProduct"),
            scenarioWith("/products", "GET", "200", "listProducts"),
            scenarioWith("/products", "GET", "400", "listProducts")
        )

        val graph = OpenApiLinksGraph.from(links)
        val result = (graph as HasValue).value.sortScenariosBasedOnDependency(scenarios)
        val sorted = (result as HasValue).value
        val operations = sorted.map { "${it.operationMetadata?.operationId} -> ${it.status}" }

        assertThat(operations).isEqualTo(listOf(
            "createProduct -> 201", "createProduct -> 400",
            "listProducts -> 200", "listProducts -> 400",
            "getProduct -> 200", "getProduct -> 400", "getProduct -> 404",
            "updateProduct -> 200", "updateProduct -> 400", "updateProduct -> 404",
            "deleteProduct -> 200", "deleteProduct -> 400", "deleteProduct -> 404"
        ))

        assertThat(sorted[0].status).isEqualTo(201)
        assertThat(sorted[1].status).isEqualTo(400)
    }

    companion object {
        @JvmStatic
        fun validGraphScenarios() = listOf(
            Arguments.of(
                "linear chain: A→B→C",
                listOf(
                    link(ref("/a", "GET", "200", "opA"), ref("/b", "GET", "200", "opB")),
                    link(ref("/b", "GET", "200", "opB"), ref("/c", "GET", "200", "opC"))
                ),
                listOf(
                    scenarioWith("/a", "GET", "200", "opA"),
                    scenarioWith("/b", "GET", "200", "opB"),
                    scenarioWith("/c", "GET", "200", "opC")
                ),
                listOf("opA", "opB", "opC")
            ),
            Arguments.of(
                "diamond dependency: A→B,C then B,C→D",
                listOf(
                    link(ref("/a", "GET", "200", "opA"), ref("/b", "GET", "200", "opB")),
                    link(ref("/a", "GET", "200", "opA"), ref("/c", "GET", "200", "opC")),
                    link(ref("/b", "GET", "200", "opB"), ref("/d", "GET", "200", "opD")),
                    link(ref("/c", "GET", "200", "opC"), ref("/d", "GET", "200", "opD"))
                ),
                listOf(
                    scenarioWith("/a", "GET", "200", "opA"),
                    scenarioWith("/b", "GET", "200", "opB"),
                    scenarioWith("/c", "GET", "200", "opC"),
                    scenarioWith("/d", "GET", "200", "opD")
                ),
                listOf("opA", "opB", "opC", "opD")
            ),
            Arguments.of(
                "tree structure: A→B,C then B→D,E",
                listOf(
                    link(ref("/a", "GET", "200", "opA"), ref("/b", "GET", "200", "opB")),
                    link(ref("/a", "GET", "200", "opA"), ref("/c", "GET", "200", "opC")),
                    link(ref("/b", "GET", "200", "opB"), ref("/d", "GET", "200", "opD")),
                    link(ref("/b", "GET", "200", "opB"), ref("/e", "GET", "200", "opE"))
                ),
                listOf(
                    scenarioWith("/a", "GET", "200", "opA"),
                    scenarioWith("/b", "GET", "200", "opB"),
                    scenarioWith("/c", "GET", "200", "opC"),
                    scenarioWith("/d", "GET", "200", "opD"),
                    scenarioWith("/e", "GET", "200", "opE")
                ),
                listOf("opA", "opB", "opC", "opD", "opE")
            ),
            Arguments.of(
                "single dependency",
                listOf(
                    link(ref("/users", "POST", "201", "createUser"), ref("/users/{id}", "GET", "200", "getUser"))
                ),
                listOf(
                    scenarioWith("/users", "POST", "201", "createUser"),
                    scenarioWith("/users/(id:string)", "GET", "200", "getUser")
                ),
                listOf("createUser", "getUser")
            )
        )

        @JvmStatic
        fun cyclicGraphScenarios() = listOf(
            Arguments.of(
                "two-node cycle",
                listOf(
                    link(ref("/a", "GET", "200", "opA"), ref("/b", "GET", "200", "opB")),
                    link(ref("/b", "GET", "200", "opB"), ref("/a", "GET", "200", "opA"))
                ),
                1
            ),
            Arguments.of(
                "three-node cycle",
                listOf(
                    link(ref("/a", "GET", "200", "opA"), ref("/b", "GET", "200", "opB")),
                    link(ref("/b", "GET", "200", "opB"), ref("/c", "GET", "200", "opC")),
                    link(ref("/c", "GET", "200", "opC"), ref("/a", "GET", "200", "opA"))
                ),
                1
            ),
            Arguments.of(
                "self-referencing cycle",
                listOf(
                    link(ref("/a", "GET", "200", "opA"), ref("/a", "GET", "200", "opA"))
                ),
                1
            ),
            Arguments.of(
                "nested cycles",
                listOf(
                    link(ref("/a", "GET", "200", "opA"), ref("/b", "GET", "200", "opB")),
                    link(ref("/b", "GET", "200", "opB"), ref("/c", "GET", "200", "opC")),
                    link(ref("/c", "GET", "200", "opC"), ref("/a", "GET", "200", "opA")),
                    link(ref("/b", "GET", "200", "opB"), ref("/d", "GET", "200", "opD")),
                    link(ref("/d", "GET", "200", "opD"), ref("/b", "GET", "200", "opB"))
                ),
                2
            )
        )

        @JvmStatic
        fun multipleStatusCodeScenarios() = listOf(
            Arguments.of(
                "same operation with different status codes sorted by status",
                listOf(
                    link(ref("/users", "POST", "201", "createUser"), ref("/users/{id}", "GET", "200", "getUser")),
                    link(ref("/users", "POST", "400", "createUser"), ref("/errors", "POST", "200", "handleError"))
                ),
                listOf(
                    scenarioWith("/users", "POST", "201", "createUser"),
                    scenarioWith("/users", "POST", "400", "createUser"),
                    scenarioWith("/users/(id:string)", "GET", "200", "getUser"),
                    scenarioWith("/errors", "POST", "200", "handleError")
                ),
                listOf(
                    listOf("createUser:201", "createUser:400"),
                    listOf("getUser:200", "handleError:200")
                )
            ),
            Arguments.of(
                "same operation with different status codes sorted by status with no operation-id",
                listOf(
                    link(ref("/users", "POST", "201", null), ref("/users/{id}", "GET", "200", null)),
                    link(ref("/users", "POST", "400", null), ref("/errors", "POST", "200", null))
                ),
                listOf(
                    scenarioWith("/users", "POST", "201", null),
                    scenarioWith("/users", "POST", "400", null),
                    scenarioWith("/users/(id:string)", "GET", "200", null),
                    scenarioWith("/errors", "POST", "200", null)
                ),
                listOf(
                    listOf("/users:POST:201", "/users:POST:400"),
                    listOf("/users/(id:string):GET:200", "/errors:POST:200")
                )
            ),
            Arguments.of(
                "status code null sorted last within operation group",
                listOf(
                    link(ref("/a", "GET", "200", "opA"), ref("/b", "GET", "200", "opB"))
                ),
                listOf(
                    scenarioWith("/a", "GET", "200", "opA"),
                    scenarioWith("/a", "GET", "500", "opA"),
                    scenarioWith("/b", "GET", "200", "opB")
                ),
                listOf(
                    listOf("opA:200", "opA:500"),
                    listOf("opB:200")
                )
            ),
            Arguments.of(
                "same operation with different status codes sorted by status with mixed identifiers",
                listOf(
                    link(ref("/users", "POST", "201", "createUser"), ref("/users/{id}", "GET", "200", null)),
                    link(ref("/users", "POST", "400", null), ref("/errors", "POST", "200", "handleError"))
                ),
                listOf(
                    scenarioWith("/users", "POST", "201", "createUser"),
                    scenarioWith("/users", "POST", "400", null),
                    scenarioWith("/users/(id:string)", "GET", "200", null),
                    scenarioWith("/errors", "POST", "200", "handleError")
                ),
                listOf(
                    listOf("createUser:201", "/users:POST:400"),
                    listOf("/users/(id:string):GET:200", "handleError:200")
                )
            ),
        )

        private fun link(byOperation: OpenApiOperationReference, forOperation: OpenApiOperationReference): OpenApiLink {
            return OpenApiLink(
                name = "TEST-${UUID.randomUUID()}",
                byOperation = byOperation,
                forOperation = forOperation
            )
        }

        private fun ref(path: String, method: String, status: String, operationId: String?): OpenApiOperationReference {
            return OpenApiOperationReference(
                path = path,
                method = method,
                status = status.toIntOrNull() ?: DEFAULT_RESPONSE_CODE,
                operationId = operationId
            )
        }

        private fun scenarioWith(path: String, method: String, status: String, operationId: String?): Scenario {
            return Scenario(ScenarioInfo(
                httpRequestPattern = HttpRequestPattern(httpPathPattern = HttpPathPattern.from(path), method = method),
                httpResponsePattern = HttpResponsePattern(status = status.toIntOrNull() ?: DEFAULT_RESPONSE_CODE),
                operationMetadata = operationId?.let { OperationMetadata(operationId = it) },
            ))
        }
    }
}
