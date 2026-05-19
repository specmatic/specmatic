package io.specmatic.core.config.v3.upgrade

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.resolveTemplates
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.utilities.Flags
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class TemplatePreservingConfigUpgradeTest {
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory()).apply {
        registerKotlinModule()
        setDefaultPropertyInclusion(
            JsonInclude.Value.construct(JsonInclude.Include.CUSTOM, JsonInclude.Include.CUSTOM)
                .withValueFilter(EmptyCollectionFilter::class.java)
        )
    }

    @Test
    fun `preserves substitution expressions when upgraded values resolve from system properties`() {
        val upgraded = Flags.using(
            "APP_URL" to "http://env.example:9000",
            "BROKER_URL" to "kafka://env.example:19092",
            "TIMEOUT" to "9000",
            "ENFORCE" to "false",
            "TOKEN" to "env-token",
            "LICENSE_PATH" to "/env/license.txt",
        ) {
            upgradePreservingTemplates(
                """
                version: 2
                contracts:
                  - filesystem:
                      directory: ./specs
                    provides:
                      - specs:
                          - api.yaml
                        specType: openapi
                        config:
                          baseUrl: "{APP_URL:http://localhost:8080}"
                    consumes:
                      - specs:
                          - events.yaml
                        specType: asyncapi
                        config:
                          baseUrl: "{BROKER_URL:kafka://localhost:9092}"
                test:
                  actuatorUrl: "{APP_URL:http://localhost:8080}/actuator"
                  timeoutInMilliseconds: "{TIMEOUT:5000}"
                security:
                  OpenAPI:
                    securitySchemes:
                      bearerAuth:
                        type: bearer
                        token: "{TOKEN:default-token}"
                stub:
                  dictionary: "{DICTIONARY_PATH:./dictionary.yaml}"
                examples:
                  - "{GLOBAL_EXAMPLES:./examples}"
                license_path: "{LICENSE_PATH:./license.txt}"
                report:
                  types:
                    APICoverage:
                      OpenAPI:
                        successCriteria:
                          enforce: "{ENFORCE:true}"
                """.trimIndent()
            )
        }

        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/actuatorUrl").asText())
            .isEqualTo("{APP_URL:http://localhost:8080}/actuator")
        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/specs/0/spec/baseUrl").asText())
            .isEqualTo("{APP_URL:http://localhost:8080}")
        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/specs/0/spec/securitySchemes/bearerAuth/token").asText())
            .isEqualTo("{TOKEN:default-token}")
        assertThat(upgraded.at("/dependencies/services/0/service/runOptions/asyncapi/specs/0/spec/baseUrl").asText())
            .isEqualTo("{BROKER_URL:kafka://localhost:9092}")
        assertThat(upgraded.at("/dependencies/services/0/service/runOptions/asyncapi/specs/0/spec/host").isMissingNode).isTrue
        assertThat(upgraded.at("/dependencies/services/0/service/runOptions/asyncapi/specs/0/spec/port").isMissingNode).isTrue
        assertThat(upgraded.at("/systemUnderTest/service/data/examples/0/directories/0").asText())
            .isEqualTo("{GLOBAL_EXAMPLES:./examples}")
        assertThat(upgraded.at("/dependencies/data/dictionary/path").asText())
            .isEqualTo("{DICTIONARY_PATH:./dictionary.yaml}")
        assertThat(upgraded.at("/specmatic/settings/test/timeoutInMilliseconds").asText()).isEqualTo("{TIMEOUT:5000}")
        assertThat(upgraded.at("/specmatic/license/path").asText()).isEqualTo("{LICENSE_PATH:./license.txt}")
        assertThat(upgraded.at("/specmatic/governance/successCriteria/enforce").asText()).isEqualTo("{ENFORCE:true}")
    }

    @Test
    fun `does not preserve a template into an unrelated field with the same resolved value`() {
        val upgraded = upgradePreservingTemplates(
            """
            version: 2
            contracts:
              - filesystem:
                  directory: ./specs
                provides:
                  - specs:
                      - api.yaml
                    specType: openapi
                    config:
                      baseUrl: http://localhost:8080
            report:
              types:
                APICoverage:
                  OpenAPI:
                    successCriteria:
                      minThresholdPercentage: "{COVERAGE:0}"
                      maxMissedEndpointsInSpec: 0
            """.trimIndent()
        )

        assertThat(upgraded.at("/specmatic/governance/successCriteria/minCoveragePercentage").asText())
            .isEqualTo("{COVERAGE:0}")
        assertThat(upgraded.at("/specmatic/governance/successCriteria/maxMissedOperationsInSpec").isInt).isTrue
        assertThat(upgraded.at("/specmatic/governance/successCriteria/maxMissedOperationsInSpec").asInt()).isEqualTo(0)
    }

    @Test
    fun `preserves bff fixture substitution expressions`() {
        val upgraded = upgradePreservingTemplates(
            File("src/test/resources/specmaticConfigFiles/v3/v2ToV3/bff/before.yaml").readText()
        )

        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/actuatorUrl").asText())
            .isEqualTo("{APP_URL:http://localhost:8080}/actuator/mappings")
        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/specs/0/spec/baseUrl").asText())
            .isEqualTo("{APP_URL:http://localhost:8080}")
    }

    @Test
    fun `preserves generic spec config substitution expressions`() {
        val upgraded = upgradePreservingTemplates(
            """
            version: 2
            contracts:
              - git:
                  url: https://github.com/specmatic/specmatic-order-contracts.git
                consumes:
                  - specs:
                      - io/specmatic/examples/store/asyncapi/kafka.yaml
                    specType: asyncapi
                    config:
                      inMemoryBroker:
                        host: localhost
                        port: "{KAFKA_PORT:9092}"
                      servers:
                        - host: "{KAFKA_SERVER:localhost:9092}"
                          protocol: kafka
            """.trimIndent()
        )

        assertThat(upgraded.at("/dependencies/services/0/service/runOptions/asyncapi/specs/0/spec/inMemoryBroker/port").asText())
            .isEqualTo("{KAFKA_PORT:9092}")
        assertThat(upgraded.at("/dependencies/services/0/service/runOptions/asyncapi/specs/0/spec/servers/0/host").asText())
            .isEqualTo("{KAFKA_SERVER:localhost:9092}")
    }

    @Test
    fun `preserves provider base url template only for the matching contract entry`() {
        val upgraded = upgradePreservingTemplates(
            """
            version: 2
            contracts:
              - provides:
                  - specs:
                      - spec1.yaml
                    specType: openapi
                    config:
                      baseUrl: "{BASE_URL:http://localhost:8080}"
                  - specs:
                      - spec2.yaml
                    specType: openapi
                    config:
                      baseUrl: http://localhost:8080
            """.trimIndent()
        )

        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/specs/0/spec/baseUrl").asText())
            .isEqualTo("{BASE_URL:http://localhost:8080}")
        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/specs/1/spec/baseUrl").asText())
            .isEqualTo("http://localhost:8080")
    }

    @Test
    fun `preserves provider base url template only for matching entry when raw contracts is an object`() {
        val upgraded = TemplatePreservingConfigUpgrade.preserveTemplates(
            rawLegacyConfig = mapper.readTree(
                """
                version: 2
                contracts:
                  provides:
                  - specs:
                    - spec1.yaml
                    config:
                      baseUrl: ${'$'}{BASE_URL:http://localhost:8080}
                  - specs:
                    - spec2.yaml
                    config:
                      baseUrl: http://localhost:8080
                """.trimIndent()
            ),
            upgradedConfig = mapper.readTree(
                """
                version: 3
                systemUnderTest:
                  service:
                    runOptions:
                      openapi:
                        specs:
                          - spec:
                              baseUrl: http://localhost:8080
                          - spec:
                              baseUrl: http://localhost:8080
                """.trimIndent()
            )
        )

        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/specs/0/spec/baseUrl").asText())
            .isEqualTo("${'$'}{BASE_URL:http://localhost:8080}")
        assertThat(upgraded.at("/systemUnderTest/service/runOptions/openapi/specs/1/spec/baseUrl").asText())
            .isEqualTo("http://localhost:8080")
    }

    private fun upgradePreservingTemplates(yaml: String): JsonNode {
        val rawTree = mapper.readTree(yaml)
        val resolvedTree = resolveTemplates(mapper.readTree(yaml))
        val legacyConfig = mapper.treeToValue(resolvedTree, SpecmaticConfigV2::class.java).transform(null)
        val upgradedConfig = SpecmaticConfigVersion.convertToLatestVersionedConfig(legacyConfig)
        val upgradedTree = mapper.valueToTree<JsonNode>(upgradedConfig)
        return TemplatePreservingConfigUpgrade.preserveTemplates(rawTree, upgradedTree)
    }
}

private class EmptyCollectionFilter {
    override fun equals(other: Any?): Boolean {
        if (other == null) return true
        if (other.javaClass == this.javaClass) return true

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
