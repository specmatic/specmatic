package io.specmatic.core.config.validation

import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.objectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ConfigSchemaValidationTest {
    private val validator = ConfigSchemaValidator()
    private val validOutput = emptyList<ConfigValidationOutput>()

    @Nested
    inner class V2 {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.config.validation.ConfigSchemaValidationTest#v2ValidCases")
        fun `accepts valid V2 shapes`(testCase: ValidSchemaCase) {
            assertThat(schema(SpecmaticConfigVersion.VERSION_2, testCase.json))
                .isEqualTo(validOutput)
        }

        @Test
        fun `accepts report security test stub virtual service and workflow shapes`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_2, $$"""
            {
              "version": 2,
              "auth": {
                "bearer-file": "token.txt",
                "bearer-environment-variable": "TOKEN",
                "personal-access-token": "token"
              },
              "report": {
                "types": {
                  "APICoverage": {
                    "OpenAPI": {
                      "successCriteria": {
                        "minThresholdPercentage": 80,
                        "maxMissedEndpointsInSpec": 2,
                        "enforce": true
                      }
                    }
                  }
                }
              },
              "security": {
                "OpenAPI": {
                  "securitySchemes": {
                    "bearer": {
                      "type": "bearer",
                      "token": "token"
                    }
                  }
                }
              },
              "test": {
                "resiliencyTests": { "enable": "all" },
                "timeoutInMilliseconds": 5000,
                "https": { "mtlsEnabled": true }
              },
              "stub": {
                "generative": true,
                "delayInMilliseconds": 100,
                "hotReload": "true",
                "https": { "mtlsEnabled": true }
              },
              "virtualService": {
                "host": "localhost",
                "port": 9000,
                "specs": ["orders.yaml"],
                "logMode": "ALL",
                "nonPatchableKeys": ["id"]
              },
              "workflow": {
                "ids": {
                  "createOrder": {
                    "extract": "$.id",
                    "use": "$orderId"
                  }
                }
              }
            }
            """.trimIndent())).isEqualTo(validOutput)
        }

        @Test
        fun `rejects excludedEndpoints as a V2 property`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_2, """
            version: 2
            report:
              types:
                APICoverage:
                  OpenAPI:
                    excludedEndpoints: [GET /health]
            """.trimIndent())).isEqualTo(invalidOutput(detail(
                $$"/properties/report/$ref/properties/types/$ref/properties/APICoverage/$ref/properties/OpenAPI/$ref",
                "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/APICoverageConfiguration",
                "/report/types/APICoverage/OpenAPI",
                "property 'excludedEndpoints' is not defined in the schema and the schema does not allow additional properties",
            )))
        }

        @Test
        fun `rejects more than one explicit contract source`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_2, """
            version: 2
            contracts:
              - git: {}
                filesystem: {}
            """.trimIndent())).isEqualTo(invalidOutput(
                detail($$"/properties/contracts/items/$ref/allOf/0/then/allOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ContractConfig/allOf/0/then/allOf/0", "/contracts/0", "Specify zero or one contract source: git, filesystem, or web."),
                detail($$"/properties/contracts/items/$ref/allOf/1/then/allOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ContractConfig/allOf/1/then/allOf/0", "/contracts/0", "Specify zero or one contract source: git, filesystem, or web."),
            ))
        }

        @Test
        fun `rejects resiliencyTests on consumes`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_2, """
            version: 2
            contracts:
              - consumes:
                  - baseUrl: http://localhost:8080
                    specs: [orders.yaml]
                    resiliencyTests:
                      enable: all
            """.trimIndent())).isEqualTo(invalidOutput(
                detail($$"/properties/contracts/items/$ref/properties/consumes/items/$ref/else/then/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/FullUrlConsumes", "/contracts/0/consumes/0", "property 'resiliencyTests' is not defined in the schema and the schema does not allow additional properties"),
            ))
        }

        @Test
        fun `rejects basePath on provides`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_2, """
            version: 2
            contracts:
              - provides:
                  - basePath: /orders
                    specs: [orders.yaml]
            """.trimIndent())).isEqualTo(invalidOutput(
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/else/else/else/else/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValue", "/contracts/0/provides/0", "property 'basePath' is not defined in the schema and the schema does not allow additional properties; [required property 'specType' not found, required property 'config' not found]"),
            ))
        }

        @Test
        fun `rejects missing ConfigValue fields`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_2, """
            version: 2
            contracts:
              - provides:
                  - specs: [orders.yaml]
            """.trimIndent())).isEqualTo(invalidOutput(
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/else/else/else/else/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValue", "/contracts/0/provides/0", "[required property 'specType' not found, required property 'config' not found]"),
            ))
        }

        @Test
        fun `rejects null ConfigValue nodes`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_2, """
            version: 2
            contracts:
              - provides:
                  - specs: [orders.yaml]
                    specType: openapi
                    config:
                      token: null
            """.trimIndent())).isEqualTo(invalidOutput(
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/else/else/else/else/$ref/properties/config/additionalProperties/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValueNode", "/contracts/0/provides/0/config/token", "null found, [string, integer, number, boolean, array, object] expected"),
            ))
        }

        @Test
        fun `rejects a web source without a URL`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_2, """
            version: 2
            contracts:
              - web: {}
            """.trimIndent())).isEqualTo(invalidOutput(detail(
                instanceLocation = "/contracts/0/web",
                error = "required property 'url' not found",
                keywordLocation = $$"/properties/contracts/items/$ref/properties/web/$ref",
                absoluteKeywordLocation = "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/WebContractSource",
            )))
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.config.validation.ConfigSchemaValidationTest#v2ProtocolOptionCases")
        fun `rejects invalid known V2 protocol options`(testCase: InvalidSchemaCase) {
            assertThat(schema(testCase.version, testCase.json))
                .isEqualTo(invalidOutput(detail(testCase)))
        }
    }

    @Nested
    inner class V3 {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.config.validation.ConfigSchemaValidationTest#v3ValidCases")
        fun `accepts valid V3 shapes`(testCase: ValidSchemaCase) {
            assertThat(schema(SpecmaticConfigVersion.VERSION_3, testCase.json))
                .isEqualTo(validOutput)
        }

        @Test
        fun `accepts source data settings and every protocol component branch`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_3, """
            {
              "version": 3,
              "components": {
                "sources": {
                  "gitSource": { "git": { "branch": "main" } },
                  "webSource": { "web": { "url": "https://contracts.example.test/openapi.yaml" } }
                },
                "services": {
                  "api": {
                    "definitions": [
                      {
                        "definition": {
                          "source": { "filesystem": {} },
                          "specs": [
                            { "spec": { "id": "orders", "path": "orders.yaml", "x-team": "payments" } }
                          ]
                        }
                      }
                    ],
                    "runOptions": { "openapi": { "baseUrl": "http://localhost:8080" } },
                    "settings": { "timeoutInMilliseconds": 5000 },
                    "data": {
                      "examples": [{ "directories": ["examples"] }],
                      "dictionary": { "path": "data/dictionary.json" },
                      "adapters": { "preSpecmaticRequestProcessor": "hooks/pre.sh" }
                    }
                  }
                },
                "runOptions": {
                  "api": { "openapi": { "type": "test", "cert": { "mtlsEnabled": true } } },
                  "asyncapi": { "asyncapi": { "type": "test" } },
                  "graphql": { "graphqlsdl": { "type": "mock" } },
                  "protobuf": { "protobuf": { "type": "test" } }
                },
                "examples": {
                  "testExamples": [{ "directories": ["test-examples"] }],
                  "mockExamples": [{ "directories": ["mock-examples"] }],
                  "commonExamples": { "directories": ["examples"] }
                },
                "dictionaries": {
                  "default": { "path": "data/dictionary.json" }
                },
                "adapters": {
                  "hooks": { "preSpecmaticRequestProcessor": "hooks/pre.sh" }
                },
                "certificates": {
                  "mtls": { "keyStore": { "file": "server.jks" }, "mtlsEnabled": true }
                },
                "settings": {
                  "global": { "general": { "disableTelemetry": true } }
                }
              }
            }
            """.trimIndent())).isEqualTo(validOutput)
        }

        @Test
        fun `rejects an adapter hook outside the closed adapter shape`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_3, """
            version: 3
            components:
              adapters:
                hooks:
                  notARealHook: hooks/custom.sh
            """.trimIndent())).isEqualTo(invalidOutput(detail(
                instanceLocation = "/components/adapters/hooks",
                keywordLocation = $$"/properties/components/$ref/properties/adapters/additionalProperties/$ref",
                error = "property 'notARealHook' is not defined in the schema and the schema does not allow additional properties",
                absoluteKeywordLocation = "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/AdapterConfiguration",
            )))
        }

        @Test
        fun `rejects missing root wrapper fields`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_3, """
            version: 3
            systemUnderTest: {}
            dependencies: {}
            """.trimIndent())).isEqualTo(invalidOutput(
                detail($$"/properties/systemUnderTest/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/SystemUnderTestServiceDefinition", "/systemUnderTest", "required property 'service' not found"),
                detail($$"/properties/dependencies/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/DependenciesDefinition", "/dependencies", "required property 'services' not found"),
            ))
        }

        @Test
        fun `rejects source provider cardinality in components`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_3, """
            version: 3
            components:
              sources:
                bad:
                  git: {}
                  filesystem: {}
            """.trimIndent())).isEqualTo(invalidOutput(
                detail($$"/properties/components/$ref/properties/sources/additionalProperties/$ref/allOf/0/then/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/GitSourceSchema", "/components/sources/bad", "property 'filesystem' is not defined in the schema and the schema does not allow additional properties"),
                detail($$"/properties/components/$ref/properties/sources/additionalProperties/$ref/allOf/1/then/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/FileSystemSourceSchema", "/components/sources/bad", "property 'git' is not defined in the schema and the schema does not allow additional properties"),
            ))
        }

        @Test
        fun `rejects both key store forms`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_3, """
            version: 3
            components:
              certificates:
                bad:
                  keyStore:
                    file: server.jks
                    directory: certs
            """.trimIndent())).isEqualTo(invalidOutput(
                detail($$"/properties/components/$ref/properties/certificates/additionalProperties/$ref/properties/keyStore/$ref/allOf/0/then/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/KeyStoreFileConfiguration", "/components/certificates/bad/keyStore", "property 'directory' is not defined in the schema and the schema does not allow additional properties"),
            ))
        }

        @Test
        fun `rejects a protocol enum outside its resolved branch`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_3, """
            version: 3
            mcp:
              test:
                baseUrl: http://localhost:9100
                transportKind: streamable_http
            """.trimIndent())).isEqualTo(invalidOutput(detail(
                instanceLocation = "/mcp/test/transportKind",
                error = "does not have a value in the enumeration [\"STREAMABLE_HTTP\"]",
                keywordLocation = $$"/properties/mcp/$ref/properties/test/$ref/properties/transportKind",
                absoluteKeywordLocation = "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/McpTestRunOptions/properties/transportKind",
            )))
        }

        @Test
        fun `selects the test branch before validating component run options`() {
            assertThat(schema(SpecmaticConfigVersion.VERSION_3, """
            version: 3
            components:
              runOptions:
                api:
                  openapi:
                    type: test
                    logMode: ALL
            """.trimIndent())).isEqualTo(invalidOutput(detail(
                instanceLocation = "/components/runOptions/api/openapi",
                error = "property 'logMode' is not defined in the schema and the schema does not allow additional properties",
                keywordLocation = $$"/properties/components/$ref/properties/runOptions/additionalProperties/$ref/properties/openapi/$ref/allOf/0/then/$ref",
                absoluteKeywordLocation = "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/OpenApiTestRunOptions",
            )))
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.config.validation.ConfigSchemaValidationTest#v3ProtocolOptionCases")
        fun `rejects invalid known V3 protocol options`(testCase: InvalidSchemaCase) {
            assertThat(schema(testCase.version, testCase.json))
                .isEqualTo(invalidOutput(detail(testCase)))
        }
    }

    companion object {
        private const val REF = $$"$ref"
        data class ValidSchemaCase(val name: String, val json: String) {
            override fun toString(): String = name
        }

        data class InvalidSchemaCase(
            val name: String,
            val json: String,
            val error: String,
            val keywordLocation: String,
            val instanceLocation: String,
            val version: SpecmaticConfigVersion,
            val absoluteKeywordLocation: String,
        ) {
            override fun toString(): String = name
        }

        @JvmStatic
        fun v2ProtocolOptionCases() = listOf(
            v2OptionCase(
                name = "AsyncAPI replyTimeout must be an integer",
                option = "replyTimeout: slow",
                optionPath = "replyTimeout",
                expectedType = "integer",
            ),
            v2OptionCase(
                name = "AsyncAPI subscriberReadinessWaitTime must be an integer",
                option = "subscriberReadinessWaitTime: soon",
                optionPath = "subscriberReadinessWaitTime",
                expectedType = "integer",
            ),
            v2OptionCase(
                name = "AsyncAPI inMemoryBroker.port must be an integer",
                option = "inMemoryBroker: { port: nope }",
                optionPath = "inMemoryBroker/$REF/properties/port",
                expectedType = "integer",
            ),
            v2OptionCase(
                name = "AsyncAPI servers must be an array",
                option = "servers: invalid",
                optionPath = "servers",
                expectedType = "array",
            ),
            v2OptionCase(
                name = "AsyncAPI server host must be a string",
                option = "servers: [{ host: 123, protocol: kafka }]",
                optionPath = "servers/items/$REF/properties/host",
                instancePath = "servers/0/host",
                expectedType = "string",
                errorMessage = "integer found, string expected",
            ),
            v2OptionCase(
                name = "AsyncAPI schema registry kind must be supported",
                option = "schemaRegistry: { kind: unsupported }",
                optionPath = "schemaRegistry/$REF/properties/kind",
                errorMessage = "does not have a value in the enumeration [\"CONFLUENT\", \"DEFAULT\"]",
            ),
            v2OptionCase(
                name = "GraphQL host must be a string",
                specType = "graphqlsdl",
                option = "host: 123",
                optionPath = "host",
                expectedType = "string",
                errorMessage = "integer found, string expected",
            ),
            v2OptionCase(
                name = "GraphQL port must be an integer",
                specType = "graphqlsdl",
                option = "port: nope",
                optionPath = "port",
                expectedType = "integer",
            ),
            v2OptionCase(
                name = "gRPC host must be a string",
                specType = "protobuf",
                option = "host: 123",
                optionPath = "host",
                expectedType = "string",
                errorMessage = "integer found, string expected",
            ),
            v2OptionCase(
                name = "gRPC port must be an integer",
                specType = "protobuf",
                option = "port: nope",
                optionPath = "port",
                expectedType = "integer",
            ),
            v2OptionCase(
                name = "gRPC importPaths must be an array",
                specType = "protobuf",
                option = "importPaths: invalid",
                optionPath = "importPaths",
                expectedType = "array",
            ),
            v2OptionCase(
                name = "gRPC protocVersion must be a string",
                specType = "protobuf",
                option = "protocVersion: 3",
                optionPath = "protocVersion",
                expectedType = "string",
                errorMessage = "integer found, string expected",
            ),
            v2OptionCase(
                name = "gRPC requestTimeout must be an integer",
                specType = "protobuf",
                option = "requestTimeout: slow",
                optionPath = "requestTimeout",
                expectedType = "integer",
            ),
        )

        @JvmStatic
        fun v3ProtocolOptionCases() = listOf(
            v3OptionCase(
                name = "AsyncAPI test replyTimeout must be an integer",
                branch = "asyncTest",
                option = "replyTimeout: slow",
                optionPath = "replyTimeout",
                expectedType = "integer",
                schema = "AsyncApiTestRunOptions",
            ),
            v3OptionCase(
                name = "AsyncAPI test subscriberReadinessWaitTime must be an integer",
                branch = "asyncTest",
                option = "subscriberReadinessWaitTime: soon",
                optionPath = "subscriberReadinessWaitTime",
                expectedType = "integer",
                schema = "AsyncApiTestRunOptions",
            ),
            v3OptionCase(
                name = "AsyncAPI test servers must be an array",
                branch = "asyncTest",
                option = "servers: invalid",
                optionPath = "servers",
                expectedType = "array",
                schema = "AsyncApiTestRunOptions",
            ),
            v3OptionCase(
                name = "AsyncAPI server client consumer must be an object",
                branch = "asyncTest",
                option = """
                servers:
                  - host: localhost:9092
                    protocol: kafka
                    client:
                      consumer: invalid
                """.trimIndent(),
                optionPath = "servers/items/$REF/properties/client/$REF/properties/consumer",
                instancePath = "servers/0/client/consumer",
                expectedType = "object",
                schema = "AsyncClientProperties",
            ),
            v3OptionCase(
                name = "AsyncAPI test schema registry must be an object",
                branch = "asyncTest",
                option = "schemaRegistry: invalid",
                optionPath = "schemaRegistry/$REF",
                expectedType = "object",
                schema = "SchemaRegistryProperties",
            ),
            v3OptionCase(
                name = "AsyncAPI mock inMemoryBroker.port must be an integer",
                branch = "asyncMock",
                option = "inMemoryBroker: { port: nope }",
                optionPath = "inMemoryBroker/$REF/properties/port",
                expectedType = "integer",
                schema = "InMemoryBrokerConfiguration",
            ),
            v3OptionCase(
                name = "AsyncAPI mock servers must be an array",
                branch = "asyncMock",
                option = "servers: invalid",
                optionPath = "servers",
                expectedType = "array",
                schema = "AsyncApiMockRunOptions",
            ),
            v3OptionCase(
                name = "AsyncAPI mock schema registry must be an object",
                branch = "asyncMock",
                option = "schemaRegistry: invalid",
                optionPath = "schemaRegistry/$REF",
                expectedType = "object",
                schema = "SchemaRegistryProperties",
            ),
            v3OptionCase(
                name = "GraphQL test host must be a string",
                branch = "graphqlTest",
                protocol = "graphqlsdl",
                option = "host: 123",
                optionPath = "host",
                expectedType = "string",
                errorMessage = "integer found, string expected",
                schema = "GraphqlSdlTestRunOptions",
            ),
            v3OptionCase(
                name = "GraphQL test port must be an integer",
                branch = "graphqlTest",
                protocol = "graphqlsdl",
                option = "port: nope",
                optionPath = "port",
                expectedType = "integer",
                schema = "GraphqlSdlTestRunOptions",
            ),
            v3OptionCase(
                name = "GraphQL mock host must be a string",
                branch = "graphqlMock",
                protocol = "graphqlsdl",
                option = "host: 123",
                optionPath = "host",
                expectedType = "string",
                errorMessage = "integer found, string expected",
                schema = "GraphqlSdlMockRunOptions",
            ),
            v3OptionCase(
                name = "GraphQL mock port must be an integer",
                branch = "graphqlMock",
                protocol = "graphqlsdl",
                option = "port: nope",
                optionPath = "port",
                expectedType = "integer",
                schema = "GraphqlSdlMockRunOptions",
            ),
            v3OptionCase(
                name = "gRPC test host must be a string",
                branch = "grpcTest",
                protocol = "protobuf",
                option = "host: 123",
                optionPath = "host",
                expectedType = "string",
                errorMessage = "integer found, string expected",
                schema = "ProtobufTestRunOptions",
            ),
            v3OptionCase(
                name = "gRPC test port must be an integer",
                branch = "grpcTest",
                protocol = "protobuf",
                option = "port: nope",
                optionPath = "port",
                expectedType = "integer",
                schema = "ProtobufTestRunOptions",
            ),
            v3OptionCase(
                name = "gRPC test importPaths must be an array",
                branch = "grpcTest",
                protocol = "protobuf",
                option = "importPaths: invalid",
                optionPath = "importPaths",
                expectedType = "array",
                schema = "ProtobufTestRunOptions",
            ),
            v3OptionCase(
                name = "gRPC test protocVersion must be a string",
                branch = "grpcTest",
                protocol = "protobuf",
                option = "protocVersion: 3",
                optionPath = "protocVersion",
                expectedType = "string",
                errorMessage = "integer found, string expected",
                schema = "ProtobufTestRunOptions",
            ),
            v3OptionCase(
                name = "gRPC test requestTimeout must be an integer",
                branch = "grpcTest",
                protocol = "protobuf",
                option = "requestTimeout: slow",
                optionPath = "requestTimeout",
                expectedType = "integer",
                schema = "ProtobufTestRunOptions",
            ),
            v3OptionCase(
                name = "gRPC mock port must be an integer",
                branch = "grpcMock",
                protocol = "protobuf",
                option = "port: nope",
                optionPath = "port",
                expectedType = "integer",
                schema = "ProtobufMockRunOptions",
            ),
            v3OptionCase(
                name = "gRPC mock importPaths must be an array",
                branch = "grpcMock",
                protocol = "protobuf",
                option = "importPaths: invalid",
                optionPath = "importPaths",
                expectedType = "array",
                schema = "ProtobufMockRunOptions",
            ),
            v3OptionCase(
                name = "gRPC mock protocVersion must be a string",
                branch = "grpcMock",
                protocol = "protobuf",
                option = "protocVersion: 3",
                optionPath = "protocVersion",
                expectedType = "string",
                errorMessage = "integer found, string expected",
                schema = "ProtobufMockRunOptions",
            ),
        )

        private fun v2OptionCase(
            name: String,
            option: String,
            optionPath: String,
            instancePath: String? = null,
            expectedType: String? = null,
            errorMessage: String? = null,
            specType: String = "asyncapi",
        ): InvalidSchemaCase {
            val branch = when (specType) {
                "asyncapi" -> 0
                "graphqlsdl" -> 1
                "protobuf" -> 2
                else -> error("Unsupported V2 protocol: $specType")
            }

            val schemaName = when (specType) {
                "asyncapi" -> "AsyncConfiguration"
                "graphqlsdl" -> "GraphqlConfiguration"
                "protobuf" -> "GrpcConfiguration"
                else -> error("Unsupported V2 protocol: $specType")
            }

            val schemaPath = "/properties/contracts/items/$REF/properties/provides/items/$REF/else/else/else/else/$REF"
            val optionSchemaPath = "$schemaPath/allOf/$branch/then/properties/config/$REF/properties/$optionPath"
            val config = buildList {
                add("version: 2")
                add("contracts:")
                add("  - provides:")
                add("      - specs: [events.yaml]")
                add("        specType: $specType")
                add("        config:")
                option.lines().forEach { add("          $it") }
            }.joinToString("\n")

            return InvalidSchemaCase(
                name = name,
                version = SpecmaticConfigVersion.VERSION_2,
                json = config,
                keywordLocation = optionSchemaPath,
                absoluteKeywordLocation = absoluteSchemaLocation(
                    schema = when {
                        optionPath.startsWith("inMemoryBroker/") -> "InMemoryBrokerConfiguration"
                        optionPath.startsWith("schemaRegistry/") -> "SchemaRegistryProperties"
                        optionPath.startsWith("servers/") -> "AsyncClientServerConfig"
                        else -> schemaName
                    },
                    optionPath = optionPath,
                    version = 2,
                ),
                instanceLocation = "/contracts/0/provides/0/config/${instancePath ?: instanceOptionPath(optionPath)}",
                error = errorMessage ?: "string found, $expectedType expected",
            )
        }

        private fun v3OptionCase(
            name: String,
            branch: String,
            option: String,
            optionPath: String,
            instancePath: String? = null,
            expectedType: String? = null,
            errorMessage: String? = null,
            schema: String,
            protocol: String = "asyncapi",
        ): InvalidSchemaCase {
            val mode = if (branch.endsWith("Mock")) "mock" else "test"
            val branchIndex = if (mode == "test") 0 else 1
            val schemaPath = "/properties/components/$REF/properties/runOptions/additionalProperties/$REF/properties/$protocol/$REF/allOf/$branchIndex/then/$REF"
            val optionSchemaPath = "$schemaPath/properties/$optionPath"
            val config = buildList {
                add("version: 3")
                add("components:")
                add("  runOptions:")
                add("    $branch:")
                add("      $protocol:")
                add("        type: $mode")
                option.lines().forEach { add("        $it") }
            }.joinToString("\n")

            return InvalidSchemaCase(
                name = name,
                version = SpecmaticConfigVersion.VERSION_3,
                json = config,
                keywordLocation = optionSchemaPath,
                absoluteKeywordLocation = absoluteSchemaLocation(schema, optionPath, version = 3),
                instanceLocation = "/components/runOptions/$branch/$protocol/${instancePath ?: instanceOptionPath(optionPath)}",
                error = errorMessage ?: if (optionPath.endsWith("/$REF")) "string found, object expected" else "string found, $expectedType expected",
            )
        }

        private fun absoluteSchemaLocation(schema: String, optionPath: String, version: Int): String {
            val path = when {
                optionPath.endsWith("/$REF") -> ""
                optionPath.contains("/$REF/properties/") -> "/properties/${optionPath.substringAfterLast("/properties/")}"
                else -> "/properties/$optionPath"
            }

            return "https://specmatic.io/internal-schema/config-v$version-resolved.schema.json#/definitions/$schema$path"
        }

        private fun instanceOptionPath(optionPath: String): String = when {
            optionPath.endsWith("/$REF") -> optionPath.removeSuffix("/$REF")
            optionPath.contains("/$REF/properties/") -> optionPath.substringBefore("/$REF") + "/" + optionPath.substringAfterLast("/properties/")
            else -> optionPath
        }

        @JvmStatic
        fun v2ValidCases() = listOf(
            ValidSchemaCase(
                name = "source-less and each single source form",
                json = """
                version: 2
                contracts:
                  - provides: [orders.spec]
                  - git:
                      branch: main
                  - filesystem: {}
                  - web:
                      url: https://contracts.example.test
                """.trimIndent(),
            ),
            ValidSchemaCase(
                name = "provides and consumes URL forms, including basePath-only consumes",
                json = """
                version: 2
                contracts:
                  - provides:
                      - baseUrl: http://localhost:8080
                        specs: [orders.yaml]
                        resiliencyTests:
                          enable: all
                      - host: localhost
                        port: 8080
                        specs: [orders.yaml]
                    consumes:
                      - baseUrl: http://localhost:8081
                        specs: [orders.yaml]
                      - basePath: /orders
                        specs: [orders.yaml]
                """.trimIndent(),
            ),
            ValidSchemaCase(
                name = "aliases and recursive ConfigValue JSON",
                json = """
                version: 2
                auth:
                  personalAccessToken: token
                virtual_service:
                  nonPatchableKeys: [id]
                attribute_selection_pattern:
                  default_fields: [id]
                  query_param_key: fields
                contracts:
                  - filesystem:
                      directory: contracts
                    provides:
                      - specs: [orders.yaml]
                        specType: openapi
                        config:
                          retries: 3
                          enabled: true
                          extra:
                            region: test
                """.trimIndent(),
            ),
            ValidSchemaCase(
                name = "known AsyncAPI, GraphQL and gRPC options with extensions",
                json = """
                version: 2
                contracts:
                  - provides:
                      - specs: [events.yaml]
                        specType: asyncapi
                        config:
                          replyTimeout: 10000
                          subscriberReadinessWaitTime: 100
                          inMemoryBroker:
                            logDir: broker-logs
                            host: localhost
                            port: 9092
                          servers:
                            - host: localhost:9092
                              protocol: kafka
                              adminCredentials:
                                username: admin
                              client:
                                consumer:
                                  group: tests
                                producer:
                                  acks: all
                          schemaRegistry:
                            kind: DEFAULT
                          extension:
                            enabled: true
                  - provides:
                      - specs: [schema.graphql]
                        specType: graphqlsdl
                        config:
                          host: localhost
                          port: 9001
                          extension: enabled
                  - provides:
                      - specs: [service.proto]
                        specType: protobuf
                        config:
                          host: localhost
                          port: 9002
                          importPaths: [proto]
                          protocVersion: 3.25.0
                          requestTimeout: 5000
                          extension: enabled
                """.trimIndent(),
            ),
        )

        @JvmStatic
        fun v3ValidCases() = listOf(
            ValidSchemaCase(
                name = "contextual services and reusable protocol components",
                json = """
                version: 3
                components:
                  services:
                    api:
                      definitions:
                        - definition:
                            source:
                              filesystem: {}
                            specs: [orders.yaml]
                      runOptions:
                        openapi:
                          baseUrl: http://localhost:8080
                      settings:
                        timeoutInMilliseconds: 5000
                  sources:
                    repo:
                      git:
                        branch: main
                  runOptions:
                    soap:
                      wsdl:
                        type: mock
                        baseUrl: http://localhost:8090
                        vendorOption:
                          enabled: true
                  certificates:
                    mtls:
                      mtlsEnabled: true
                proxies:
                  - proxy:
                      target: https://one.example.test
                  - proxy:
                      target: https://two.example.test
                mcp:
                  test:
                    baseUrl: http://localhost:9100
                    transportKind: STREAMABLE_HTTP
                specmatic:
                  governance:
                    report:
                      formats: [html, ctrf]
                """.trimIndent(),
            ),
            ValidSchemaCase(
                name = "test and mock contextual branches",
                json = """
                version: 3
                systemUnderTest:
                  service:
                    definitions: []
                dependencies:
                  services: []
                """.trimIndent(),
            ),
            ValidSchemaCase(
                name = "known AsyncAPI, GraphQL and gRPC run options with extensions",
                json = """
                version: 3
                components:
                  runOptions:
                    asyncTest:
                      asyncapi:
                        type: test
                        replyTimeout: 10000
                        subscriberReadinessWaitTime: 100
                        servers:
                          - host: localhost:9092
                            protocol: kafka
                            adminCredentials:
                              username: admin
                            client:
                              consumer:
                                group: tests
                              producer:
                                acks: all
                        schemaRegistry:
                          kind: DEFAULT
                        extension:
                          enabled: true
                    asyncMock:
                      asyncapi:
                        type: mock
                        inMemoryBroker:
                          logDir: broker-logs
                          host: localhost
                          port: 9093
                        servers:
                          - host: localhost:9093
                            protocol: kafka
                        schemaRegistry:
                          kind: DEFAULT
                    graphqlTest:
                      graphqlsdl:
                        type: test
                        host: localhost
                        port: 9001
                    graphqlMock:
                      graphqlsdl:
                        type: mock
                        host: localhost
                        port: 9002
                    grpcTest:
                      protobuf:
                        type: test
                        host: localhost
                        port: 9003
                        importPaths: [proto]
                        protocVersion: 3.25.0
                        requestTimeout: 5000
                    grpcMock:
                      protobuf:
                        type: mock
                        port: 9004
                        importPaths: [proto]
                        protocVersion: 3.25.0
                """.trimIndent(),
            ),
        )
    }

    private fun invalidOutput(vararg details: ConfigValidationOutput) = details.toList()
    private fun schema(version: SpecmaticConfigVersion, json: String) = validator.validate(version, objectMapper.readTree(json))
    private fun detail(testCase: InvalidSchemaCase) = detail(
        error = testCase.error,
        keywordLocation = testCase.keywordLocation,
        instanceLocation = testCase.instanceLocation,
        absoluteKeywordLocation = testCase.absoluteKeywordLocation,
    )

    private fun detail(
        keywordLocation: String,
        absoluteKeywordLocation: String,
        instanceLocation: String,
        error: String,
    ) = ConfigValidationOutput(
        valid = false,
        error = error,
        keywordLocation = keywordLocation,
        instanceLocation = instanceLocation,
        absoluteKeywordLocation = absoluteKeywordLocation,
        metadata = schemaMetadata(keywordLocation, absoluteKeywordLocation),
    )

    private fun schemaMetadata(keywordLocation: String, absoluteKeywordLocation: String): ConfigValidationMetadata {
        val resource = if (absoluteKeywordLocation.contains("config-v2")) {
            "/config-validation/config-v2-resolved.schema.json"
        } else {
            "/config-validation/config-v3-resolved.schema.json"
        }

        val schema = ConfigSchemaValidationTest::class.java.getResourceAsStream(resource)
            ?.use(objectMapper::readTree)
            ?: error("Unable to load schema fixture: $resource")

        val schemaNode = schema.at(absoluteKeywordLocation.substringAfter('#', ""))
        return ConfigValidationMetadata(
            title = schemaNode["title"]?.asText(),
            description = schemaNode["description"]?.asText(),
            deprecated = schemaNode["deprecated"]?.asBoolean(),
            deprecationMessage = schemaNode["deprecationMessage"]?.asText(),
            keyword = keywordLocation.split('/').asReversed().firstOrNull { it.isNotEmpty() && it.toIntOrNull() == null }?.replace("~1", "/")?.replace("~0", "~"),
        )
    }
}
