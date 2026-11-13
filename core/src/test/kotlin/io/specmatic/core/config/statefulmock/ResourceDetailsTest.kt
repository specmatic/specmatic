package io.specmatic.core.config.statefulmock

import io.specmatic.core.pattern.ContractException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ResourceDetailsTest {
    @Test
    fun `resource declarations are converted to typed details`() {
        val details = resourceDetailsFrom(
            mapOf(
                "resources" to listOf(
                    resource(
                        name = "application",
                        basePath = "/api/applications",
                        common = mapOf("identifier" to "request.path.id", "idempotencyKey" to "request.header.Idempotency-Key"),
                        operations = listOf(
                            operation("initiateApplication", action = "create", identifier = "response.body#/responseData/applicationId"),
                            operation("confirmApplication", action = "update")
                        )
                    )
                )
            ),
            "application.yaml"
        )

        assertThat(details).containsExactly(
            ResourceDetails(
                name = "application",
                basePath = "/api/applications",
                commonOperationProperties = ResourceOperationProperties(
                    identifier = "request.path.id",
                    idempotencyKey = "request.header.Idempotency-Key"
                ),
                operations = listOf(
                    ResourceOperationDetails(
                        id = "initiateApplication",
                        action = StatefulMockAction.CREATE,
                        identifier = "response.body#/responseData/applicationId"
                    ),
                    ResourceOperationDetails(id = "confirmApplication", action = StatefulMockAction.UPDATE)
                )
            )
        )
    }

    @Test
    fun `missing required fields and unknown properties report spec and config location`() {
        assertInvalid(
            config = mapOf("resources" to listOf(resource(name = null, basePath = "/orders", operations = listOf(operation("getOrders"))))),
            expectedMessage = "spec 'orders.yaml'#/resources/0/resource/name"
        )
        assertInvalid(
            config = mapOf("resources" to listOf(mapOf("resource" to mapOf("name" to "orders", "operations" to listOf(operation("getOrders")))))),
            expectedMessage = "spec 'orders.yaml'#/resources/0/resource/basePath"
        )
        assertInvalid(
            config = mapOf("resources" to listOf(resource(name = "orders", basePath = "/orders", operations = listOf(operation(null))))),
            expectedMessage = "spec 'orders.yaml'#/resources/0/resource/operations/0/operation/id"
        )
        assertInvalid(
            config = mapOf("resources" to listOf(resource(name = "orders", basePath = "/orders", operations = listOf(operation("getOrders", extra = mapOf("unknown" to true)))))),
            expectedMessage = "spec 'orders.yaml'#/resources/0/resource/operations/0/operation/unknown"
        )
    }

    @Test
    fun `duplicate same-path declarations and operation bindings fail while same resource name may use another base path`() {
        assertInvalid(
            config = mapOf("resources" to listOf(
                resource(name = "orders", basePath = "/orders", operations = listOf(operation("getOrders"))),
                resource(name = "orders", basePath = "/orders", operations = listOf(operation("createOrder")))
            )),
            expectedMessage = "already declared for basePath '/orders'"
        )
        assertInvalid(
            config = mapOf("resources" to listOf(
                resource(name = "orders", basePath = "/orders", operations = listOf(operation("getOrders"))),
                resource(name = "legacyOrders", basePath = "/orders", operations = listOf(operation("getOrders")))
            )),
            expectedMessage = "operation 'getOrders' under basePath '/orders' is already assigned"
        )

        val details = resourceDetailsFrom(
            mapOf("resources" to listOf(
                resource(name = "orders", basePath = "/orders", operations = listOf(operation("getOrders"))),
                resource(name = "orders", basePath = "/legacy/orders", operations = listOf(operation("getLegacyOrders")))
            )),
            "orders.yaml"
        )
        assertThat(details.map { it.name }).containsExactly("orders", "orders")
    }

    @Test
    fun `unsupported action and malformed resource shape fail clearly`() {
        assertInvalid(
            config = mapOf("resources" to listOf(resource(name = "orders", basePath = "/orders", operations = listOf(operation("getOrders", action = "archive"))))),
            expectedMessage = "must be one of create, read, update, delete, upsert"
        )
        assertInvalid(mapOf("resources" to "orders"), "#/resources: must be a list")
    }

    private fun assertInvalid(config: Map<String, Any?>, expectedMessage: String) {
        assertThatThrownBy { resourceDetailsFrom(config, "orders.yaml") }
            .isInstanceOf(ContractException::class.java)
            .hasMessageContaining(expectedMessage)
    }

    private fun resource(
        name: String?,
        basePath: String,
        common: Map<String, Any?>? = null,
        operations: List<Map<String, Any?>>,
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = mapOf(
        "resource" to buildMap {
            name?.let { put("name", it) }
            put("basePath", basePath)
            common?.let { put("commonOperationProperties", it) }
            put("operations", operations)
            putAll(extra)
        }
    )

    private fun operation(
        id: String?,
        action: String? = null,
        identifier: String? = null,
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = mapOf(
        "operation" to buildMap {
            id?.let { put("id", it) }
            action?.let { put("action", it) }
            identifier?.let { put("identifier", it) }
            putAll(extra)
        }
    )
}
