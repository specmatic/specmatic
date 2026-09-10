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
                detail($$"/properties/contracts/items/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ContractConfig", "/contracts/0", "Specify zero or one contract source: git, filesystem, or web."),
                detail($$"/properties/contracts/items/$ref/oneOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ContractConfig/oneOf/0", "/contracts/0", "must not be valid to the schema {\"description\":\"No explicit source may be present in this branch.\",\"anyOf\":[{\"description\":\"An explicit Git source is present.\",\"required\":[\"git\"]},{\"description\":\"An explicit filesystem source is present.\",\"required\":[\"filesystem\"]},{\"description\":\"An explicit web source is present.\",\"required\":[\"web\"]}]}"),
                detail($$"/properties/contracts/items/$ref/oneOf/1", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ContractConfig/oneOf/1", "/contracts/0", "must not be valid to the schema {\"description\":\"No additional explicit source may be present in the Git branch.\",\"anyOf\":[{\"description\":\"An explicit filesystem source is present.\",\"required\":[\"filesystem\"]},{\"description\":\"An explicit web source is present.\",\"required\":[\"web\"]}]}"),
                detail($$"/properties/contracts/items/$ref/oneOf/2", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ContractConfig/oneOf/2", "/contracts/0", "must not be valid to the schema {\"description\":\"No additional explicit source may be present in the filesystem branch.\",\"anyOf\":[{\"description\":\"An explicit Git source is present.\",\"required\":[\"git\"]},{\"description\":\"An explicit web source is present.\",\"required\":[\"web\"]}]}"),
                detail($$"/properties/contracts/items/$ref/oneOf/3", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ContractConfig/oneOf/3", "/contracts/0", "required property 'web' not found; must not be valid to the schema {\"description\":\"No additional explicit source may be present in the web branch.\",\"anyOf\":[{\"description\":\"An explicit Git source is present.\",\"required\":[\"git\"]},{\"description\":\"An explicit filesystem source is present.\",\"required\":[\"filesystem\"]}]}"),
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
                detail($$"/properties/contracts/items/$ref/properties/consumes/items/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConsumesEntry", "/contracts/0/consumes/0", "must be valid to one and only one schema, but 0 are valid"),
                detail($$"/properties/contracts/items/$ref/properties/consumes/items/$ref/oneOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConsumesEntry/oneOf/0", "/contracts/0/consumes/0", "object found, string expected"),
                detail($$"/properties/contracts/items/$ref/properties/consumes/items/$ref/oneOf/1/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/FullUrlConsumes", "/contracts/0/consumes/0", "property 'resiliencyTests' is not defined in the schema and the schema does not allow additional properties"),
                detail($$"/properties/contracts/items/$ref/properties/consumes/items/$ref/oneOf/2/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlConsumes", "/contracts/0/consumes/0", "[property 'baseUrl' is not defined in the schema and the schema does not allow additional properties, property 'resiliencyTests' is not defined in the schema and the schema does not allow additional properties]"),
                detail($$"/properties/contracts/items/$ref/properties/consumes/items/$ref/oneOf/2/$ref/anyOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlConsumes/anyOf/0", "/contracts/0/consumes/0", "required property 'host' not found"),
                detail($$"/properties/contracts/items/$ref/properties/consumes/items/$ref/oneOf/2/$ref/anyOf/1", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlConsumes/anyOf/1", "/contracts/0/consumes/0", "required property 'port' not found"),
                detail($$"/properties/contracts/items/$ref/properties/consumes/items/$ref/oneOf/2/$ref/anyOf/2", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlConsumes/anyOf/2", "/contracts/0/consumes/0", "required property 'basePath' not found"),
                detail($$"/properties/contracts/items/$ref/properties/consumes/items/$ref/oneOf/3/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValue", "/contracts/0/consumes/0", "[property 'baseUrl' is not defined in the schema and the schema does not allow additional properties, property 'resiliencyTests' is not defined in the schema and the schema does not allow additional properties]; [required property 'specType' not found, required property 'config' not found]"),
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
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ProvidesEntry", "/contracts/0/provides/0", "must be valid to one and only one schema, but 0 are valid"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ProvidesEntry/oneOf/0", "/contracts/0/provides/0", "object found, string expected"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/1/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/FullUrlProvides", "/contracts/0/provides/0", "property 'basePath' is not defined in the schema and the schema does not allow additional properties; required property 'baseUrl' not found"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/2/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlProvides", "/contracts/0/provides/0", "property 'basePath' is not defined in the schema and the schema does not allow additional properties"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/2/$ref/anyOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlProvides/anyOf/0", "/contracts/0/provides/0", "required property 'host' not found"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/2/$ref/anyOf/1", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlProvides/anyOf/1", "/contracts/0/provides/0", "required property 'port' not found"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/3/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValue", "/contracts/0/provides/0", "property 'basePath' is not defined in the schema and the schema does not allow additional properties; [required property 'specType' not found, required property 'config' not found]"),
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
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ProvidesEntry", "/contracts/0/provides/0", "must be valid to one and only one schema, but 0 are valid"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ProvidesEntry/oneOf/0", "/contracts/0/provides/0", "object found, string expected"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/1/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/FullUrlProvides", "/contracts/0/provides/0", "required property 'baseUrl' not found"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/2/$ref/anyOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlProvides/anyOf/0", "/contracts/0/provides/0", "required property 'host' not found"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/2/$ref/anyOf/1", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlProvides/anyOf/1", "/contracts/0/provides/0", "required property 'port' not found"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/3/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValue", "/contracts/0/provides/0", "[required property 'specType' not found, required property 'config' not found]"),
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
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ProvidesEntry", "/contracts/0/provides/0", "must be valid to one and only one schema, but 0 are valid"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ProvidesEntry/oneOf/0", "/contracts/0/provides/0", "object found, string expected"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/1/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/FullUrlProvides", "/contracts/0/provides/0", "[property 'specType' is not defined in the schema and the schema does not allow additional properties, property 'config' is not defined in the schema and the schema does not allow additional properties]; required property 'baseUrl' not found"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/2/$ref", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlProvides", "/contracts/0/provides/0", "[property 'specType' is not defined in the schema and the schema does not allow additional properties, property 'config' is not defined in the schema and the schema does not allow additional properties]"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/2/$ref/anyOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlProvides/anyOf/0", "/contracts/0/provides/0", "required property 'host' not found"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/2/$ref/anyOf/1", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/PartialUrlProvides/anyOf/1", "/contracts/0/provides/0", "required property 'port' not found"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/3/$ref/properties/config/additionalProperties/$ref/anyOf/0", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValueNode/anyOf/0", "/contracts/0/provides/0/config/token", "null found, string expected"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/3/$ref/properties/config/additionalProperties/$ref/anyOf/1", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValueNode/anyOf/1", "/contracts/0/provides/0/config/token", "null found, integer expected"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/3/$ref/properties/config/additionalProperties/$ref/anyOf/2", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValueNode/anyOf/2", "/contracts/0/provides/0/config/token", "null found, number expected"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/3/$ref/properties/config/additionalProperties/$ref/anyOf/3", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValueNode/anyOf/3", "/contracts/0/provides/0/config/token", "null found, boolean expected"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/3/$ref/properties/config/additionalProperties/$ref/anyOf/4", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValueNode/anyOf/4", "/contracts/0/provides/0/config/token", "null found, array expected"),
                detail($$"/properties/contracts/items/$ref/properties/provides/items/$ref/oneOf/3/$ref/properties/config/additionalProperties/$ref/anyOf/5", "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ConfigValueNode/anyOf/5", "/contracts/0/provides/0/config/token", "null found, object expected"),
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
                detail($$"/properties/components/$ref/properties/sources/additionalProperties/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/SourceSchema", "/components/sources/bad", "Choose exactly one source provider: git, filesystem, or web."),
                detail($$"/properties/components/$ref/properties/sources/additionalProperties/$ref/oneOf/0/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/GitSourceSchema", "/components/sources/bad", "property 'filesystem' is not defined in the schema and the schema does not allow additional properties"),
                detail($$"/properties/components/$ref/properties/sources/additionalProperties/$ref/oneOf/1/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/FileSystemSourceSchema", "/components/sources/bad", "property 'git' is not defined in the schema and the schema does not allow additional properties"),
                detail($$"/properties/components/$ref/properties/sources/additionalProperties/$ref/oneOf/2/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/WebSourceSchema", "/components/sources/bad", "[property 'git' is not defined in the schema and the schema does not allow additional properties, property 'filesystem' is not defined in the schema and the schema does not allow additional properties]; required property 'web' not found"),
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
                detail($$"/properties/components/$ref/properties/certificates/additionalProperties/$ref/properties/keyStore/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/KeyStoreConfiguration", "/components/certificates/bad/keyStore", "Specify exactly one key-store form: file or directory."),
                detail($$"/properties/components/$ref/properties/certificates/additionalProperties/$ref/properties/keyStore/$ref/oneOf/0/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/KeyStoreFileConfiguration", "/components/certificates/bad/keyStore", "property 'directory' is not defined in the schema and the schema does not allow additional properties"),
                detail($$"/properties/components/$ref/properties/certificates/additionalProperties/$ref/properties/keyStore/$ref/oneOf/1/$ref", "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/KeyStoreDirectoryConfiguration", "/components/certificates/bad/keyStore", "property 'file' is not defined in the schema and the schema does not allow additional properties"),
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
    }

    companion object {
        data class ValidSchemaCase(val name: String, val json: String) {
            override fun toString(): String = name
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
        )
    }

    private fun invalidOutput(vararg details: ConfigValidationOutput) = details.toList()
    private fun schema(version: SpecmaticConfigVersion, json: String) = validator.validate(version, objectMapper.readTree(json))
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
