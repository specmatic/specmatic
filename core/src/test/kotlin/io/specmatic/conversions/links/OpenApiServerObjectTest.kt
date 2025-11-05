package io.specmatic.conversions.links

import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnFailure
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.servers.ServerVariable
import io.swagger.v3.oas.models.servers.ServerVariables
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class OpenApiServerObjectTest {
    @MethodSource("validServerObjects")
    @ParameterizedTest
    fun `should be able to parse openapi server object to an internal representation`(server: Server) {
        val openApiServer = OpenApiServerObject.from(server)
        assertThat(openApiServer)
            .withFailMessage { (openApiServer as? ReturnFailure)?.toFailure()?.reportString().orEmpty() }
            .isInstanceOf(HasValue::class.java)
    }

    @MethodSource("invalidServerObjects")
    @ParameterizedTest
    fun `should return failure when openapi server object is invalid and cannot be parsed`(serverToErrorMessage: Pair<Server, String>) {
        val (server, errorMessage) = serverToErrorMessage
        val openApiServer = OpenApiServerObject.from(server)
        assertThat(openApiServer).isInstanceOf(HasFailure::class.java); openApiServer as HasFailure
        assertThat(openApiServer.toFailure().reportString()).isEqualToIgnoringWhitespace(errorMessage)
    }

    companion object {
        @JvmStatic
        fun validServerObjects(): List<Server> {
            return listOf(
                Server().apply { url = "/test/specmatic" },
                Server().apply { url = "http://localhost:9000/test/specmatic"; description = "Test" },
                Server().apply {
                    url = "http://localhost:9000/test/specmatic/{role}"
                    description = "Test"
                    variables = ServerVariables().apply {
                        addServerVariable(
                            "role",
                            ServerVariable().apply {
                                default = "User"
                                enum = listOf("Admin", "User", "SuperAdmin")
                                description = "Specmatic User"
                            },
                        )
                    }
                },
            )
        }

        @JvmStatic
        fun invalidServerObjects(): List<Pair<Server, String>> {
            return listOf(
                Pair(
                    Server(),
                    """
                    >> server.url
                    Mandatory field 'url' is missing from server object
                    """.trimIndent(),
                ),
                Pair(
                    Server().apply {
                        url = "http://localhost:9000/test/specmatic/{role}"
                        description = "Test"
                        variables = ServerVariables().apply {
                            addServerVariable(
                                "role",
                                ServerVariable().apply {
                                    default = "User"
                                    description = "Specmatic User"
                                },
                            )
                        }
                    },
                    """
                    >> server.variables.role.enum
                    Invalid Server variable 'role'
                    Property 'enum' in Server variable object can't be null or empty
                    """.trimIndent(),
                ),
                Pair(
                    Server().apply {
                        url = "http://localhost:9000/test/specmatic/{role}"
                        description = "Test"
                        variables = ServerVariables().apply {
                            addServerVariable(
                                "role",
                                ServerVariable().apply {
                                    enum = listOf("Admin", "User")
                                    description = "Specmatic User"
                                },
                            )
                        }
                    },
                    """
                    >> server.variables.role.default
                    Invalid Server variable 'role'
                    Property `default` in Server variable object cannot be null, must be one of Admin, User
                    """.trimIndent(),
                ),
                Pair(
                    Server().apply {
                        url = "http://localhost:9000/test/specmatic/{role}"
                        description = "Test"
                        variables = ServerVariables().apply {
                            addServerVariable(
                                "role",
                                ServerVariable().apply {
                                    default = "Unknown"
                                    enum = listOf("Admin", "User")
                                    description = "Specmatic User"
                                },
                            )
                        }
                    },
                    """
                    >> server.variables.role.default
                    Invalid Server variable 'role'
                    Property `default` in Server variable object cannot be null, must be one of Admin, User
                    """.trimIndent(),
                ),
            )
        }
    }
}
