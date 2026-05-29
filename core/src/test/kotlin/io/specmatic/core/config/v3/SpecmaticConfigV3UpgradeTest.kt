package io.specmatic.core.config.v3

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.flipkart.zjsonpatch.JsonDiff
import io.specmatic.core.config.resolveTemplates
import io.specmatic.core.config.v1.SpecmaticConfigV1
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.pattern.ContractException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.io.readText
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class SpecmaticConfigV3UpgradeTest {
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory()).apply {
        registerKotlinModule()
        setDefaultPropertyInclusion(
            JsonInclude.Value.construct(JsonInclude.Include.CUSTOM, JsonInclude.Include.CUSTOM)
            .withValueFilter(EmptyCollectionFilter::class.java)
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fileUpgradeCases")
    fun `should upgrade legacy file config to v3`(testCase: UpgradeTestCase) {
        assertUpgrade(testCase)
    }

    @Nested
    inner class TestSettings {
        @TestFactory
        fun `should upgrade test settings`() = dynamicCases(cases())

        private fun cases(): List<UpgradeTestCase> {
            val directCases = listOf(
                UpgradeTestCase(
                    name = "test settings from v1 upgrade to specmatic settings v3",
                    before = InputSource.RawInput("""
                    version: 1
                    test:
                      resiliencyTests:
                        enable: positiveOnly
                      validateResponseValues: false
                      allowExtensibleSchema: true
                      timeoutInMilliseconds: 4321
                      strictMode: false
                      lenientMode: true
                      parallelism: 4
                      maxTestRequestCombinations: 11
                      maxTestCount: 19
                      junitReportDir: reports/legacy-junit
                    """.trimIndent()),
                    after = InputSource.RawInput("""
                    version: 3
                    specmatic:
                      settings:
                        test:
                          schemaResiliencyTests: positiveOnly
                          validateResponseValues: false
                          timeoutInMilliseconds: 4321
                          strictMode: false
                          lenientMode: true
                          parallelism: 4
                          maxTestRequestCombinations: 11
                          maxTestCount: 19
                          junitReportDir: reports/legacy-junit
                    """.trimIndent())
                ),
                UpgradeTestCase(
                    name = "test settings from v2 upgrade to specmatic settings v3",
                    before = InputSource.RawInput("""
                    version: 2
                    test:
                      resiliencyTests:
                        enable: all
                      validateResponseValues: true
                      timeoutInMilliseconds: 1234
                      strictMode: true
                      lenientMode: true
                      parallelism: 8
                      maxTestRequestCombinations: 17
                      maxTestCount: 23
                      junitReportDir: reports/junit
                    """.trimIndent()),
                    after = InputSource.RawInput("""
                    version: 3
                    specmatic:
                      settings:
                        test:
                          schemaResiliencyTests: all
                          validateResponseValues: true
                          timeoutInMilliseconds: 1234
                          strictMode: true
                          lenientMode: true
                          parallelism: 8
                          maxTestRequestCombinations: 17
                          maxTestCount: 23
                          junitReportDir: reports/junit
                    """.trimIndent())
                ),
                UpgradeTestCase(
                    name = "test settings from provides object resiliencyTests upgrade to v3",
                    before = InputSource.RawInput("""
                    version: 2
                    contracts:
                      - filesystem:
                          directory: ./specs
                        provides:
                          - specs:
                              - resilient.yaml
                            baseUrl: http://resilient.example
                            resiliencyTests:
                              enable: all
                    """.trimIndent()),
                    after = InputSource.RawInput("""
                    version: 3
                    specmatic:
                      settings:
                        test:
                          schemaResiliencyTests: all
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: resilient
                                    path: resilient.yaml
                        runOptions:
                          openapi:
                            specs:
                              - spec:
                                  id: resilient
                                  baseUrl: http://resilient.example
                    """.trimIndent())
                ),
                UpgradeTestCase(
                    name = "test settings from provides config value openapi resiliencyTests upgrade to v3",
                    before = InputSource.RawInput("""
                    version: 2
                    contracts:
                      - filesystem:
                          directory: ./specs
                        provides:
                          - specs:
                              - resilient-config.yaml
                            specType: OPENAPI
                            config:
                              baseUrl: http://resilient.example
                              resiliencyTests:
                                enable: all
                    """.trimIndent()),
                    after = InputSource.RawInput("""
                    version: 3
                    specmatic:
                      settings:
                        test:
                          schemaResiliencyTests: all
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: resilient-config
                                    path: resilient-config.yaml
                        runOptions:
                          openapi:
                            specs:
                              - spec:
                                  id: resilient-config
                                  baseUrl: http://resilient.example
                    """.trimIndent())
                )
            )

            val pairCases = listOf(
                LegacyPairCase(
                    name = "dropped test fields",
                    beforeV1 = """
                    version: 1
                    test:
                      allowExtensibleSchema: true
                      testsDirectory: legacy-tests-v1
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    test:
                      allowExtensibleSchema: true
                      testsDirectory: legacy-tests
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    """.trimIndent()
                ),
            )

            return directCases + pairCases.flatMap(::expandPairCase)

        }
    }

    @Nested
    inner class SystemUnderTest {
        @TestFactory
        fun `should upgrade system under test`() = dynamicCases(cases())

        private fun cases(): List<UpgradeTestCase> {
            return listOf(
                UpgradeTestCase(
                    name = "system under test fields from v1 upgrade to v3",
                    before = InputSource.RawInput("""
                    version: 1
                    contract_repositories:
                      - type: filesystem
                        directory: ./src/test/resources/openapi
                        provides:
                          - hello.yaml
                    test:
                      baseUrl: http://localhost:8081
                      filter: PATH!='/health,/ready'
                      swaggerUrl: http://localhost:8081/swagger.yaml
                      swaggerUIBaseURL: http://localhost:8081/swagger-ui
                      actuatorUrl: http://localhost:8081/actuator/mappings
                      https:
                        keyStore:
                          file: ./client-cert-v1.jks
                        keyStorePassword: password
                    """.trimIndent()),
                    after = InputSource.RawInput("""
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./src/test/resources/openapi
                              specs:
                                - hello.yaml
                        runOptions:
                          openapi:
                            filter: PATH!='/health,/ready'
                            baseUrl: http://localhost:8081
                            swaggerUrl: http://localhost:8081/swagger.yaml
                            swaggerUiBaseUrl: http://localhost:8081/swagger-ui
                            actuatorUrl: http://localhost:8081/actuator/mappings
                            cert:
                              keyStore:
                                file: ./client-cert-v1.jks
                              keyStorePassword: password
                    """.trimIndent())
                ),
                UpgradeTestCase(
                    name = "system under test fields from v2 upgrade to v3",
                    before = InputSource.RawInput("""
                    version: 2
                    contracts:
                      - filesystem:
                          directory: ./src/test/resources/openapi
                        provides:
                          - hello.yaml
                    test:
                      baseUrl: http://localhost:8080
                      filter: PATH!='/health'
                      swaggerUrl: http://localhost:8080/swagger.yaml
                      swaggerUIBaseURL: http://localhost:8080/swagger-ui
                      actuatorUrl: http://localhost:8080/actuator/mappings
                      overlayFilePath: overlay-v2.yaml
                      https:
                        keyStore:
                          file: ./client-cert.jks
                        keyStorePassword: password
                    """.trimIndent()),
                    after = InputSource.RawInput("""
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./src/test/resources/openapi
                              specs:
                                - spec:
                                    id: hello
                                    path: hello.yaml
                        runOptions:
                          openapi:
                            filter: PATH!='/health'
                            baseUrl: http://localhost:8080
                            swaggerUrl: http://localhost:8080/swagger.yaml
                            swaggerUiBaseUrl: http://localhost:8080/swagger-ui
                            actuatorUrl: http://localhost:8080/actuator/mappings
                            cert:
                              keyStore:
                                file: ./client-cert.jks
                              keyStorePassword: password
                            specs:
                              - spec:
                                  id: hello
                                  overlayFilePath: overlay-v2.yaml
                    """.trimIndent())
                ),
            )

        }
    }

    @Nested
    inner class StubSettings {
        @TestFactory
        fun `should upgrade stub settings`() = dynamicCases(cases())

        private fun cases(): List<UpgradeTestCase> {
            return listOf(
                LegacyPairCase(
                    name = "stub settings to specmatic settings",
                    beforeV1 = """
                    version: 1
                    stub:
                      generative: true
                      delayInMilliseconds: 123
                      startTimeoutInMilliseconds: 456
                      hotReload: enabled
                      strictMode: true
                      gracefulRestartTimeoutInMilliseconds: 789
                      lenientMode: true
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    stub:
                      generative: true
                      delayInMilliseconds: 123
                      startTimeoutInMilliseconds: 456
                      hotReload: enabled
                      strictMode: true
                      gracefulRestartTimeoutInMilliseconds: 789
                      lenientMode: true
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    specmatic:
                      settings:
                        mock:
                          generative: true
                          delayInMilliseconds: 123
                          startTimeoutInMilliseconds: 456
                          hotReload: true
                          strictMode: true
                          gracefulRestartTimeoutInMilliseconds: 789
                          lenientMode: true
                    """.trimIndent()
                )
            ).flatMap(::expandPairCase)

        }
    }

    @Nested
    inner class Dependencies {
        @TestFactory
        fun `should upgrade dependencies`() = dynamicCases(cases())

        private fun cases(): List<UpgradeTestCase> {
            return listOf(
                UpgradeTestCase(
                    name = "dependency fields from v1 upgrade to v3",
                    before = InputSource.RawInput("""
                    version: 1
                    contract_repositories:
                      - type: filesystem
                        directory: ./src/test/resources/openapi
                        consumes:
                          - hello.yaml
                    stub:
                      filter: PATH!='/health'
                      https:
                        keyStore:
                          file: ./client-cert-v1.jks
                        keyStorePassword: password
                    """.trimIndent()),
                    after = InputSource.RawInput("""
                    version: 3
                    dependencies:
                      services:
                        - service:
                            definitions:
                              - definition:
                                  source:
                                    filesystem:
                                      directory: ./src/test/resources/openapi
                                  specs:
                                    - hello.yaml
                            runOptions:
                              openapi:
                                filter: PATH!='/health'
                                cert:
                                  keyStore:
                                    file: ./client-cert-v1.jks
                                  keyStorePassword: password
                    """.trimIndent())
                ),
                UpgradeTestCase(
                    name = "dependency fields from v2 upgrade to v3",
                    before = InputSource.RawInput("""
                    version: 2
                    contracts:
                      - filesystem:
                          directory: ./src/test/resources/openapi
                        consumes:
                          - hello.yaml
                    stub:
                      filter: PATH!='/health'
                      https:
                        keyStore:
                          file: ./client-cert.jks
                        keyStorePassword: password
                    """.trimIndent()),
                    after = InputSource.RawInput("""
                    version: 3
                    dependencies:
                      services:
                        - service:
                            definitions:
                              - definition:
                                  source:
                                    filesystem:
                                      directory: ./src/test/resources/openapi
                                  specs:
                                    - hello.yaml
                            runOptions:
                              openapi:
                                filter: PATH!='/health'
                                cert:
                                  keyStore:
                                    file: ./client-cert.jks
                                  keyStorePassword: password
                    """.trimIndent())
                ),
            )
        }
    }

    @Nested
    inner class TopLevel {
        @TestFactory
        fun `should upgrade top level`() = dynamicCases(cases())

        private fun cases(): List<UpgradeTestCase> {
            val directCases = listOf(
                UpgradeTestCase(
                    name = "top level fields from v1 upgrade to v3",
                    before = InputSource.RawInput("""
                    version: 1
                    ignoreInlineExamples: true
                    disableTelemetry: true
                    """.trimIndent()),
                    after = InputSource.RawInput("""
                    version: 3
                    specmatic:
                      settings:
                        general:
                          ignoreInlineExamples: true
                          disableTelemetry: true
                    """.trimIndent())
                ),
                UpgradeTestCase(
                    name = "top level fields from v2 upgrade to v3",
                    before = InputSource.RawInput("""
                    version: 2
                    prettyPrint: false
                    fuzzy: true
                    escapeSoapAction: true
                    schemaExampleDefault: true
                    disableTelemetry: true
                    ignoreInlineExamples: true
                    ignoreInlineExampleWarnings: true
                    license_path: ./license-v2.txt
                    report_dir_path: ./reports/v2
                    """.trimIndent()),
                    after = InputSource.RawInput("""
                    version: 3
                    specmatic:
                      license:
                        path: ./license-v2.txt
                      governance:
                        report:
                          outputDirectory: ./reports/v2
                      settings:
                        general:
                          featureFlags:
                            escapeSoapAction: true
                            schemaExampleDefault: true
                            fuzzyMatcherForPayloads: true
                          prettyPrint: false
                          disableTelemetry: true
                          ignoreInlineExamples: true
                          ignoreInlineExampleWarnings: true
                    """.trimIndent())
                )
            )

            val pairCases = listOf(
                LegacyPairCase(
                    name = "dropped top level fields",
                    beforeV1 = """
                    version: 1
                    additionalExampleParamsFilePath: ./params-v1.json
                    attribute_selection_pattern:
                      default_fields:
                        - id
                        - name
                      query_param_key: selection
                    all_patterns_mandatory: true
                    default_pattern_values:
                      foo: bar
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    extensibleQueryParams: true
                    additionalExampleParamsFilePath: ./params-v2.json
                    attribute_selection_pattern:
                      default_fields:
                        - id
                        - name
                      query_param_key: selection
                    all_patterns_mandatory: true
                    default_pattern_values:
                      foo: bar
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    """.trimIndent()
                ),
            )

            return directCases + pairCases.flatMap(::expandPairCase)

        }
    }

    @Nested
    inner class SpecExecutionConfig {
        @TestFactory
        fun `should upgrade spec execution config`() = dynamicCases(cases())

        private fun cases(): List<UpgradeTestCase> {
            return listOf(
                SpecExecutionScenario(
                    name = "string value openapi with global base url",
                    beforeSpecs = "- sample.yaml",
                    beforeSettings = "baseUrl: http://global.example",
                    expectedSystemUnderTestRunOptions = """
                    openapi:
                      baseUrl: http://global.example
                    """.trimIndent(),
                    expectedDependenciesRunOptions = """
                    openapi:
                      baseUrl: http://global.example
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "object fullUrl openapi",
                    beforeSpecs = """
                    - specs:
                        - petstore.yaml
                      baseUrl: http://full.example:8081
                    """.trimIndent(),
                    expectedRunOptions = """
                    openapi:
                      specs:
                        - spec:
                            id: petstore
                            baseUrl: http://full.example:8081
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "object partial host only openapi",
                    beforeSpecs = """
                    - specs:
                        - orders.yaml
                      host: host-only.example
                    """.trimIndent(),
                    expectedRunOptions = """
                    openapi:
                      specs:
                        - spec:
                            id: orders
                            host: host-only.example
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "object partial port only openapi",
                    beforeSpecs = """
                    - specs:
                        - payments.yaml
                      port: 9191
                    """.trimIndent(),
                    expectedRunOptions = """
                    openapi:
                      specs:
                        - spec:
                            id: payments
                            port: 9191
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "object partial host and port openapi",
                    beforeSpecs = """
                    - specs:
                        - users.yaml
                      host: hp.example
                      port: 8181
                    """.trimIndent(),
                    expectedRunOptions = """
                    openapi:
                      specs:
                        - spec:
                            id: users
                            host: hp.example
                            port: 8181
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "object partial basePath maps to urlPathPrefix",
                    versions = listOf(1),
                    beforeSpecs = """
                    - specs:
                        - basepath.yaml
                      host: basepath.example
                      port: 8088
                      basePath: /api/v1
                    """.trimIndent(),
                    expectedRunOptions = """
                    openapi:
                      specs:
                        - spec:
                            id: basepath
                            host: basepath.example
                            port: 8088
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "object fullUrl wsdl",
                    beforeSpecs = """
                    - specs:
                        - calculator.wsdl
                      baseUrl: http://wsdl.example:7070
                    """.trimIndent(),
                    expectedRunOptions = """
                    wsdl:
                      specs:
                        - spec:
                            id: calculator
                            baseUrl: http://wsdl.example:7070
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "object partial host and port wsdl",
                    beforeSpecs = """
                    - specs:
                        - inventory.wsdl
                      host: wsdl-host.example
                      port: 7171
                    """.trimIndent(),
                    expectedRunOptions = """
                    wsdl:
                      specs:
                        - spec:
                            id: inventory
                            host: wsdl-host.example
                            port: 7171
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "config value openapi baseUrl",
                    beforeSpecs = """
                    - specs:
                        - cfg-openapi.yaml
                      specType: OPENAPI
                      config:
                        baseUrl: http://cfg-openapi.example:9090
                    """.trimIndent(),
                    expectedRunOptions = """
                    openapi:
                      specs:
                        - spec:
                            id: cfg-openapi
                            baseUrl: http://cfg-openapi.example:9090
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "config value wsdl host and port",
                    beforeSpecs = """
                    - specs:
                        - cfg-wsdl.wsdl
                      specType: WSDL
                      config:
                        host: cfg-wsdl.example
                        port: 5252
                    """.trimIndent(),
                    expectedRunOptions = """
                    wsdl:
                      specs:
                        - spec:
                            id: cfg-wsdl
                            host: cfg-wsdl.example
                            port: 5252
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "config value graphql preserves config strips host port",
                    beforeSpecs = """
                    - specs:
                        - graph.graphql
                      specType: GRAPHQL
                      config:
                        host: graph.example
                        port: 4040
                        timeout: 50
                        authType: bearer
                    """.trimIndent(),
                    expectedRunOptions = """
                    graphqlsdl:
                      specs:
                        - spec:
                            id: graph
                            host: graph.example
                            port: 4040
                            timeout: 50
                            authType: bearer
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "config value graphql baseUrl maps to host and port",
                    beforeSpecs = """
                    - specs:
                        - graph-baseurl.graphql
                      specType: GRAPHQL
                      config:
                        baseUrl: http://graph-baseurl.example:5050/graphql
                        timeout: 50
                    """.trimIndent(),
                    expectedRunOptions = """
                    graphqlsdl:
                      specs:
                        - spec:
                            id: graph-baseurl
                            host: graph-baseurl.example
                            port: 5050
                            timeout: 50
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "config value graphql port only uses side default host",
                    beforeSpecs = """
                    - specs:
                        - graph-port.graphql
                      specType: GRAPHQL
                      config:
                        port: 5051
                        timeout: 99
                    """.trimIndent(),
                    expectedSystemUnderTestRunOptions = """
                    graphqlsdl:
                      specs:
                        - spec:
                            id: graph-port
                            host: localhost
                            port: 5051
                            timeout: 99
                    """.trimIndent(),
                    expectedDependenciesRunOptions = """
                    graphqlsdl:
                      specs:
                        - spec:
                            id: graph-port
                            host: 0.0.0.0
                            port: 5051
                            timeout: 99
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "config value asyncapi preserves config strips host port",
                    beforeSpecs = """
                    - specs:
                        - event-spec.yaml
                      specType: ASYNCAPI
                      config:
                        host: async.example
                        port: 6060
                        inMemoryBroker:
                          host: broker
                          port: 7071
                        mode: consumer
                    """.trimIndent(),
                    expectedRunOptions = """
                    asyncapi:
                      specs:
                        - spec:
                            id: event-spec
                            host: async.example
                            port: 6060
                            inMemoryBroker:
                              host: broker
                              port: 7071
                            mode: consumer
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "config value protobuf preserves config strips host port",
                    beforeSpecs = """
                    - specs:
                        - payment.proto
                      specType: PROTOBUF
                      config:
                        host: proto.example
                        port: 6161
                        package: payment
                        timeoutMs: 1000
                    """.trimIndent(),
                    expectedRunOptions = """
                    protobuf:
                      specs:
                        - spec:
                            id: payment
                            host: proto.example
                            port: 6161
                            package: payment
                            timeoutMs: 1000
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "url precedence global baseUrl overridden by object fullUrl",
                    beforeSpecs = """
                    - specs:
                        - precedence.yaml
                      baseUrl: http://object.example
                    """.trimIndent(),
                    beforeSettings = "baseUrl: http://global.example",
                    expectedRunOptions = """
                    openapi:
                      baseUrl: http://global.example
                      specs:
                        - spec:
                            id: precedence
                            baseUrl: http://object.example
                    """.trimIndent()
                ),
                SpecExecutionScenario(
                    name = "url precedence baseUrl clears host port",
                    beforeSpecs = """
                    - specs:
                        - precedence-clear.yaml
                      host: host-before.example
                      port: 9393
                    - specs:
                        - precedence-clear.yaml
                      baseUrl: http://final.example
                    """.trimIndent(),
                    expectedRunOptions = """
                    openapi:
                      specs:
                        - spec:
                            id: precedence-clear
                            baseUrl: http://final.example
                    """.trimIndent(),
                    sides = listOf(Side.SystemUnderTest)
                ),
                SpecExecutionScenario(
                    name = "url precedence host port retained when no baseUrl",
                    beforeSpecs = """
                    - specs:
                        - retained.yaml
                      host: retained.example
                      port: 9898
                    - retained.yaml
                    """.trimIndent(),
                    expectedRunOptions = """
                    openapi:
                      specs:
                        - spec:
                            id: retained
                            host: retained.example
                            port: 9898
                    """.trimIndent(),
                    sides = listOf(Side.SystemUnderTest)
                ),
                SpecExecutionScenario(
                    name = "repeated spec path uses last write",
                    beforeSpecs = """
                    - specs:
                        - repeated.yaml
                      host: first.example
                      port: 8080
                    - specs:
                        - repeated.yaml
                      host: final.example
                      port: 8181
                    """.trimIndent(),
                    expectedRunOptions = """
                    openapi:
                      specs:
                        - spec:
                            id: repeated
                            host: final.example
                            port: 8181
                    """.trimIndent(),
                    sides = listOf(Side.SystemUnderTest)
                )
            ).flatMap(::expandSpecExecutionScenario)
        }

        private fun expandSpecExecutionScenario(scenario: SpecExecutionScenario): List<UpgradeTestCase> {
            return scenario.versions.flatMap { version ->
                scenario.sides.map { side ->
                    specCase(
                        side = side,
                        version = version,
                        name = "v$version ${side.label} ${scenario.name}",
                        beforeSpecs = scenario.beforeSpecs,
                        expectedRunOptions = scenario.expectedRunOptionsFor(side),
                        beforeSettings = scenario.beforeSettings,
                    )
                }
            }
        }

        private fun specCase(
            name: String,
            version: Int,
            side: Side,
            beforeSpecs: String,
            expectedRunOptions: String,
            beforeSettings: String? = null,
        ): UpgradeTestCase {
            val beforeSourceKey = if (version == 1) side.sourceKeyV1 else side.sourceKeyV2
            val beforeContracts = buildString {
                if (version == 1) {
                    appendLine("contract_repositories:")
                    appendLine("  - type: filesystem")
                    appendLine("    directory: ./specs")
                    appendLine("    $beforeSourceKey:")
                    appendLine(indent(beforeSpecs, 6))
                } else {
                    appendLine("contracts:")
                    appendLine("  - filesystem:")
                    appendLine("      directory: ./specs")
                    appendLine("    $beforeSourceKey:")
                    appendLine(indent(beforeSpecs, 6))
                }
            }.trimEnd()

            val beforeSettings = when {
                beforeSettings == null -> ""
                side == Side.SystemUnderTest -> "\ntest:\n${indent(beforeSettings, 2)}"
                else -> "\nstub:\n${indent(beforeSettings, 2)}"
            }

            val primarySpecPath = primarySpecPath(beforeSpecs)
            val primarySpecId = primarySpecId(beforeSpecs)
            val urlPathPrefix = specUrlPathPrefix(beforeSpecs)
            val plainSpecDefinition = shouldUsePlainSpecDefinition(beforeSpecs)
            val afterRoot = buildString {
                if (side == Side.SystemUnderTest) {
                    appendLine("systemUnderTest:")
                    appendLine("  service:")
                    appendLine("    definitions:")
                    appendLine("      - definition:")
                    appendLine("          source:")
                    appendLine("            filesystem:")
                    appendLine("              directory: ./specs")
                    appendLine("          specs:")
                    if (plainSpecDefinition && urlPathPrefix == null) {
                        appendLine("            - $primarySpecPath")
                    } else {
                        appendLine("            - spec:")
                        appendLine("                id: $primarySpecId")
                        appendLine("                path: $primarySpecPath")
                        appendLine("                urlPathPrefix: $urlPathPrefix")
                    }
                    appendLine("    runOptions:")
                    appendLine(indent(expectedRunOptions, 6))
                } else {
                    appendLine("dependencies:")
                    appendLine("  services:")
                    appendLine("    - service:")
                    appendLine("        definitions:")
                    appendLine("          - definition:")
                    appendLine("              source:")
                    appendLine("                filesystem:")
                    appendLine("                  directory: ./specs")
                    appendLine("              specs:")
                    if (plainSpecDefinition && urlPathPrefix == null) {
                        appendLine("                - $primarySpecPath")
                    } else {
                        appendLine("                - spec:")
                        appendLine("                    id: $primarySpecId")
                        appendLine("                    path: $primarySpecPath")
                        appendLine("                    urlPathPrefix: $urlPathPrefix")
                    }
                    appendLine("        runOptions:")
                    appendLine(indent(expectedRunOptions, 10))
                }
            }.trimEnd()

            val before = listOfNotNull(
                "version: $version",
                beforeContracts,
                beforeSettings.takeIf { it.isNotBlank() }
            ).joinToString("\n")

            val after = listOf(
                "version: 3",
                afterRoot
            ).joinToString("\n")

            return UpgradeTestCase(
                name = name,
                before = InputSource.RawInput(before),
                after = InputSource.RawInput(after)
            )
        }

        private fun primarySpecPath(providesBlock: String): String {
            return Regex("""-\s+([A-Za-z0-9._/-]+\.(yaml|yml|wsdl|proto|graphql|graphqls))\b""")
                .findAll(providesBlock)
                .map { it.groupValues[1] }
                .toList()
                .ifEmpty {
                    Regex("""id:\s*([A-Za-z0-9._/-]+\.(yaml|yml|wsdl|proto|graphql|graphqls))\b""")
                        .findAll(providesBlock)
                        .map { it.groupValues[1] }
                        .toList()
                }
                .distinct()
                .first()
        }

        private fun primarySpecId(providesBlock: String): String {
            val ids = Regex("""-\s+([A-Za-z0-9._/-]+\.(yaml|yml|wsdl|proto|graphql|graphqls))\b""")
                .findAll(providesBlock)
                .map { it.groupValues[1].substringBeforeLast('.') }
                .toList()
            return ids.ifEmpty {
                Regex("""id:\s*([A-Za-z0-9._/-]+\.(yaml|yml|wsdl|proto|graphql|graphqls))\b""")
                    .findAll(providesBlock)
                    .map { it.groupValues[1].substringBeforeLast('.') }
                    .toList()
            }.distinct().first()
        }

        private fun indent(value: String, spaces: Int): String {
            val prefix = " ".repeat(spaces)
            return value.lines().joinToString("\n") { if (it.isBlank()) it else prefix + it }
        }

        private fun specUrlPathPrefix(beforeSpecs: String): String? {
            return Regex("""basePath:\s*([^\n]+)""").find(beforeSpecs)?.groupValues?.get(1)?.trim()
        }

        private fun shouldUsePlainSpecDefinition(beforeSpecs: String): Boolean {
            val hasScalarSpecEntry = Regex("""(?m)^\s*-\s+[A-Za-z0-9._/-]+\.(yaml|yml|wsdl|proto|graphql|graphqls)\s*$""")
                .containsMatchIn(beforeSpecs)
            val hasObjectStyleMarkers = listOf("specs:", "specType:", "config:", "host:", "port:", "baseUrl:", "basePath:", "id:")
                .any(beforeSpecs::contains)
            return hasScalarSpecEntry && !hasObjectStyleMarkers
        }
    }

    @Nested
    inner class CrossCuttingMapper {
        @TestFactory
        fun `should upgrade cross cutting mapper fields`() = dynamicCases(cases())

        private fun cases(): List<UpgradeTestCase> {
            val pairCases = listOf(
                LegacyPairCase(
                    name = "workflow maps to openapi test run options",
                    beforeV1 = """
                    version: 1
                    contract_repositories:
                      - type: filesystem
                        directory: ./specs
                        provides:
                          - workflow.yaml
                    workflow:
                      ids:
                        create:
                          extract: $.id
                        get:
                          use: $.id
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    contracts:
                      - filesystem:
                          directory: ./specs
                        provides:
                          - workflow.yaml
                    workflow:
                      ids:
                        create:
                          extract: $.id
                        get:
                          use: $.id
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - workflow.yaml
                        runOptions:
                          openapi:
                            workflow:
                              ids:
                                create:
                                  extract: $.id
                                get:
                                  use: $.id
                    """.trimIndent()
                ),
                LegacyPairCase(
                    name = "security schemes map to openapi specs",
                    beforeV1 = """
                    version: 1
                    contract_repositories:
                      - type: filesystem
                        directory: ./specs
                        provides:
                          - secure.yaml
                    security:
                      OpenAPI:
                        securitySchemes:
                          basicAuth:
                            type: basicAuth
                            token: basic-token
                          bearerAuth:
                            type: bearer
                            token: bearer-token
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    contracts:
                      - filesystem:
                          directory: ./specs
                        provides:
                          - secure.yaml
                    security:
                      OpenAPI:
                        securitySchemes:
                          basicAuth:
                            type: basicAuth
                            token: basic-token
                          bearerAuth:
                            type: bearer
                            token: bearer-token
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: secure
                                    path: secure.yaml
                        runOptions:
                          openapi:
                            specs:
                              - spec:
                                  id: secure
                                  securitySchemes:
                                    basicAuth:
                                      type: basicAuth
                                      token: basic-token
                                    bearerAuth:
                                      type: bearer
                                      token: bearer-token
                    """.trimIndent()
                ),
                LegacyPairCase(
                    name = "hooks map to dependencies adapters",
                    beforeV1 = """
                    version: 1
                    contract_repositories:
                      - type: filesystem
                        directory: ./specs
                        consumes:
                          - dep.yaml
                    hooks:
                      beforeRequest: ./scripts/before.sh
                      afterResponse: ./scripts/after.sh
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    contracts:
                      - filesystem:
                          directory: ./specs
                        consumes:
                          - dep.yaml
                    hooks:
                      beforeRequest: ./scripts/before.sh
                      afterResponse: ./scripts/after.sh
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    dependencies:
                      data:
                        adapters:
                          beforeRequest: ./scripts/before.sh
                          afterResponse: ./scripts/after.sh
                      services:
                        - service:
                            definitions:
                              - definition:
                                  source:
                                    filesystem:
                                      directory: ./specs
                                  specs:
                                    - dep.yaml
                    """.trimIndent()
                ),
                LegacyPairCase(
                    name = "examples map to both test and dependencies data examples",
                    beforeV1 = """
                    version: 1
                    contract_repositories:
                      - type: filesystem
                        directory: ./specs
                        provides:
                          - sut.yaml
                        consumes:
                          - dep.yaml
                    examples:
                      - ./examples/global
                      - ./examples/shared
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    contracts:
                      - filesystem:
                          directory: ./specs
                        provides:
                          - sut.yaml
                        consumes:
                          - dep.yaml
                    examples:
                      - ./examples/global
                      - ./examples/shared
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    dependencies:
                      services:
                        - service:
                            definitions:
                            - definition:
                                source:
                                  filesystem:
                                    directory: ./specs
                                specs:
                                - dep.yaml
                      data:
                        examples:
                        - directories:
                          - ./examples/global
                          - ./examples/shared
                    systemUnderTest:
                      service:
                        data:
                          examples:
                            - directories:
                                - ./examples/global
                                - ./examples/shared
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - sut.yaml
                    """.trimIndent()
                ),
                LegacyPairCase(
                    name = "openapi config value examples merge into respective service data examples",
                    beforeV1 = """
                    version: 1
                    contract_repositories:
                      - type: filesystem
                        directory: ./specs
                        provides:
                          - specs:
                              - sut.yaml
                            specType: OPENAPI
                            config:
                              baseUrl: https//localhost:8080
                              examples:
                                - ./examples/sut
                        consumes:
                          - specs:
                              - dep.yaml
                            specType: OPENAPI
                            config:
                              baseUrl: https//localhost:9090
                              examples:
                                - ./examples/dep
                    examples:
                      - ./examples/global
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    contracts:
                      - filesystem:
                          directory: ./specs
                        provides:
                          - specs:
                              - sut.yaml
                            specType: OPENAPI
                            config:
                              baseUrl: https//localhost:8080
                              examples:
                                - ./examples/sut
                        consumes:
                          - specs:
                              - dep.yaml
                            specType: OPENAPI
                            config:
                              baseUrl: https//localhost:9090
                              examples:
                                - ./examples/dep
                    examples:
                      - ./examples/global
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    dependencies:
                      services:
                        - service:
                            definitions:
                              - definition:
                                  source:
                                    filesystem:
                                      directory: ./specs
                                  specs:
                                    - spec:
                                        id: dep
                                        path: dep.yaml
                            runOptions:
                              openapi:
                                specs:
                                  - spec:
                                      id: dep
                                      baseUrl: https//localhost:9090
                            data:
                              examples:
                                - directories:
                                    - ./examples/dep
                      data:
                        examples:
                          - directories:
                              - ./examples/global
                    systemUnderTest:
                      service:
                        data:
                          examples:
                            - directories:
                                - ./examples/global
                                - ./examples/sut
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: sut
                                    path: sut.yaml
                        runOptions:
                          openapi:
                            specs:
                              - spec:
                                  id: sut
                                  baseUrl: https//localhost:8080
                    """.trimIndent()
                ),
                LegacyPairCase(
                    name = "dictionary maps to both test and dependency data",
                    beforeV1 = """
                    version: 1
                    contract_repositories:
                      - type: filesystem
                        directory: ./specs
                        provides:
                          - sut.yaml
                        consumes:
                          - dep.yaml
                    stub:
                      dictionary: ./dict/values.json
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    contracts:
                      - filesystem:
                          directory: ./specs
                        provides:
                          - sut.yaml
                        consumes:
                          - dep.yaml
                    stub:
                      dictionary: ./dict/values.json
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        data:
                          dictionary:
                            path: ./dict/values.json
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - sut.yaml
                    dependencies:
                      data:
                        dictionary:
                          path: ./dict/values.json
                      services:
                        - service:
                            definitions:
                              - definition:
                                  source:
                                    filesystem:
                                      directory: ./specs
                                  specs:
                                    - dep.yaml
                    """.trimIndent()
                ),
                LegacyPairCase(
                    name = "auth personal access token maps to git auth",
                    beforeV1 = """
                    version: 1
                    auth:
                      personal-access-token: token123
                    contract_repositories:
                      - type: git
                        repository: https://git.example/repo-a.git
                        branch: main
                        provides:
                          - git-a.yaml
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    auth:
                      personal-access-token: token123
                    contracts:
                      - git:
                          url: https://git.example/repo-a.git
                          branch: main
                        provides:
                          - git-a.yaml
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                git:
                                  url: https://git.example/repo-a.git
                                  branch: main
                                  auth:
                                    personalAccessToken: token123
                              specs:
                                - git-a.yaml
                    """.trimIndent()
                ),
                LegacyPairCase(
                    name = "auth bearer env maps to git auth",
                    beforeV1 = """
                    version: 1
                    auth:
                      bearer-environment-variable: TOKEN_ENV
                    contract_repositories:
                      - type: git
                        repository: https://git.example/repo-b.git
                        branch: main
                        provides:
                          - git-b.yaml
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    auth:
                      bearer-environment-variable: TOKEN_ENV
                    contracts:
                      - git:
                          url: https://git.example/repo-b.git
                          branch: main
                        provides:
                          - git-b.yaml
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                git:
                                  url: https://git.example/repo-b.git
                                  branch: main
                                  auth:
                                    bearerEnvironmentVariable: TOKEN_ENV
                              specs:
                                - git-b.yaml
                    """.trimIndent()
                ),
                LegacyPairCase(
                    name = "auth bearer file maps to git auth",
                    beforeV1 = """
                    version: 1
                    auth:
                      bearer-file: ./bearer.txt
                    contract_repositories:
                      - type: git
                        repository: https://git.example/repo-c.git
                        branch: main
                        provides:
                          - git-c.yaml
                    """.trimIndent(),
                    beforeV2 = """
                    version: 2
                    auth:
                      bearer-file: ./bearer.txt
                    contracts:
                      - git:
                          url: https://git.example/repo-c.git
                          branch: main
                        provides:
                          - git-c.yaml
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                git:
                                  url: https://git.example/repo-c.git
                                  branch: main
                                  auth:
                                    bearerFile: ./bearer.txt
                              specs:
                                - git-c.yaml
                    """.trimIndent()
                ),
                LegacyPairCase(
                    name = "proxy maps to proxies",
                    beforeV2 = """
                    version: 2
                    proxy:
                      consumes:
                        - users.yaml
                      baseUrl: http://localhost:9001
                      targetUrl: http://localhost:9000
                      outputDirectory: ./recordings
                      timeoutInMilliseconds: 7000
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    proxies:
                      - proxy:
                          mock:
                            - users.yaml
                          baseUrl: http://localhost:9001
                          target: http://localhost:9000
                          recordingsDirectory: ./recordings
                          timeoutInMilliseconds: 7000
                    """.trimIndent()
                ),
                LegacyPairCase(
                    name = "report success criteria report dir and license",
                    beforeV2 = """
                    version: 2
                    license_path: ./license.txt
                    report_dir_path: ./reports
                    report:
                      formatters:
                        - type: text
                          layout: table
                          output: stdout
                      types:
                        APICoverage:
                          OpenAPI:
                            successCriteria:
                              minThresholdPercentage: 95
                    """.trimIndent(),
                    afterV3 = """
                    version: 3
                    specmatic:
                      license:
                        path: ./license.txt
                      governance:
                        report:
                          outputDirectory: ./reports
                        successCriteria:
                          minCoveragePercentage: 95
                    """.trimIndent()
                )
            )
            return pairCases.flatMap(::expandPairCase)

        }
    }

    private fun dynamicCases(cases: List<UpgradeTestCase>): List<DynamicTest> {
        return cases.map { testCase ->
            DynamicTest.dynamicTest(testCase.name) {
                assertUpgrade(testCase)
            }
        }
    }

    private fun assertUpgrade(testCase: UpgradeTestCase) {
        val upgraded = mapper.valueToTree<JsonNode>(upgrade(testCase.before))
        val expected = mapper.valueToTree<JsonNode>(loadExpected(testCase.after))
        val diff = JsonDiff.asJson(expected, upgraded)
        assertThat(diff)
        .withFailMessage { "Upgraded config mismatch for '${testCase.name}':\n${mapper.writerWithDefaultPrettyPrinter().writeValueAsString(diff)}" }
        .isEmpty()
    }

    private fun upgrade(input: InputSource): SpecmaticConfigV3 {
        val legacyConfig = loadLegacyConfig(input)
        return SpecmaticConfigV3.loadFrom(legacyConfig)
    }

    private fun loadExpected(input: InputSource): SpecmaticConfigV3 {
        val tree = resolveTemplates(mapper.readTree(input.read()))
        return mapper.treeToValue(tree, SpecmaticConfigV3::class.java)
    }

    private fun loadLegacyConfig(input: InputSource): io.specmatic.core.SpecmaticConfig {
        val tree = resolveTemplates(mapper.readTree(input.read()))
        return when (tree["version"].asInt()) {
            1 -> {
                mapper.treeToValue(tree, SpecmaticConfigV1::class.java).transform(null)
            }
            2 -> {
                mapper.treeToValue(tree, SpecmaticConfigV2::class.java).transform(null)
            }
            3 -> {
                mapper.treeToValue(tree, SpecmaticConfigV3::class.java).transform(null)
            }
            else -> {
                throw ContractException("Unsupported Specmatic config version")
            }
        }
    }

    companion object {
        @Suppress("unused")
        private enum class Side(val label: String, val sourceKeyV1: String, val sourceKeyV2: String) {
            SystemUnderTest("systemUnderTest", "provides", "provides"),
            Dependencies("dependencies", "consumes", "consumes");
        }

        private data class SpecExecutionScenario(
            val name: String,
            val beforeSpecs: String,
            val expectedRunOptions: String? = null,
            val expectedSystemUnderTestRunOptions: String? = expectedRunOptions,
            val expectedDependenciesRunOptions: String? = expectedRunOptions,
            val beforeSettings: String? = null,
            val versions: List<Int> = listOf(1, 2),
            val sides: List<Side> = listOf(Side.SystemUnderTest, Side.Dependencies),
        ) {
            fun expectedRunOptionsFor(side: Side): String {
                return when (side) {
                    Side.SystemUnderTest -> expectedSystemUnderTestRunOptions
                    Side.Dependencies -> expectedDependenciesRunOptions
                } ?: error("Missing expected runOptions for ${side.label} in scenario '$name'")
            }
        }

        data class LegacyPairCase(val name: String, val beforeV1: String? = null, val beforeV2: String? = null, val afterV3: String)
        data class UpgradeTestCase(val name: String, val before: InputSource, val after: InputSource) {
            override fun toString(): String = name
        }

        sealed interface InputSource {
            fun read(): String

            data class FileInput(val file: File) : InputSource {
                constructor(path: String) : this(File(path))
                override fun read(): String = file.readText()
            }

            data class RawInput(val value: String) : InputSource {
                override fun read(): String = value
            }
        }

        @JvmStatic
        fun fileUpgradeCases(): List<UpgradeTestCase> {
            return listOf(
                UpgradeTestCase(
                    name = "openapi test with asyncapi and openapi mock with governance",
                    before = InputSource.FileInput("src/test/resources/specmaticConfigFiles/v3/v2ToV3/bff/before.yaml"),
                    after = InputSource.FileInput("src/test/resources/specmaticConfigFiles/v3/v2ToV3/bff/after.yaml")
                )
            )
        }

        private fun expandPairCase(pairCase: LegacyPairCase): List<UpgradeTestCase> {
            val after = InputSource.RawInput(pairCase.afterV3)
            return buildList {
                pairCase.beforeV1?.let { add(UpgradeTestCase(name = "v1 ${pairCase.name}", before = InputSource.RawInput(it), after = after)) }
                pairCase.beforeV2?.let { add(UpgradeTestCase(name = "v2 ${pairCase.name}", before = InputSource.RawInput(it), after = after)) }
            }
        }
    }
}

private class EmptyCollectionFilter {
    override fun equals(other: Any?): Boolean {
        if (other == null) return true
        if (other.javaClass == this.javaClass)
            return true

        return when (other) {
            is Map<*, *> -> other.all { it.key is String && equals(it.value) }
            is Collection<*> -> other.all { equals(it) }
            is Array<*> -> other.all { equals(it) }
            is String -> other.isBlank()
            else -> isEmptyDataClass(other)
        }
    }

    private fun isEmptyDataClass(obj: Any): Boolean {
        val kClass: KClass<*> = obj::class
        if (!kClass.isData) return false

        return kClass.memberProperties.all { prop ->
            prop.isAccessible = true
            val value = prop.call(obj)
            equals(value)
        }
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }
}
