package application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigUpgradeTemplatePreserverTest {
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    @Test
    fun `preserves scalar template expressions across migrated config sections`() {
        val maxCombinations = "\${MAX_COMBOS:1}"
        val timeout = "\${TEST_TIMEOUT:2000}"
        val appUrl = "\${APP_URL:http://localhost:8080}"
        val stubDelay = "\${STUB_DELAY:250}"
        val stubBaseUrl = "\${STUB_BASE_URL:http://localhost:9000}"
        val dictionary = "\${DICTIONARY:dictionary.yaml}"
        val contractDirectory = "\${CONTRACT_DIR:./specs}"
        val sutSpec = "\${SUT_SPEC:sut.yaml}"
        val dependencySpec = "\${DEP_SPEC:dep.yaml}"
        val examplesDirectory = "\${EXAMPLES_DIR:templated_examples}"
        val bearerEnvironmentVariable = "\${BEARER_ENV:SPECMATIC_BEARER}"
        val reportDirectory = "\${REPORT_DIR:build/reports/specmatic}"
        val minCoverage = "\${MIN_COVERAGE:80}"
        val maxMissedOperations = "\${MAX_MISSED:0}"
        val loggingLevel = "\${LOG_LEVEL:INFO}"
        val logDirectory = "\${LOG_DIR:build/logs}"

        val upgraded = preserveTemplates(
            originalConfigYaml =
            """
            version: 2
            auth:
              bearer-environment-variable: '$bearerEnvironmentVariable'
            contracts:
              - filesystem:
                  directory: '$contractDirectory'
                provides:
                  - '$sutSpec'
                consumes:
                  - '$dependencySpec'
            test:
              maxTestRequestCombinations: $maxCombinations
              timeoutInMilliseconds: $timeout
              baseUrl: '$appUrl'
            stub:
              delayInMilliseconds: $stubDelay
              baseUrl: '$stubBaseUrl'
              dictionary: '$dictionary'
            report_dir_path: '$reportDirectory'
            report:
              types:
                APICoverage:
                  OpenAPI:
                    successCriteria:
                      minThresholdPercentage: $minCoverage
                      maxMissedEndpointsInSpec: $maxMissedOperations
            logging:
              level: '$loggingLevel'
              json:
                directory: '$logDirectory'
            examples:
              - fixed_examples
              - '$examplesDirectory'
            """.trimIndent(),
            upgradedConfigYaml =
            """
            version: 3
            specmatic:
              governance:
                report:
                  outputDirectory: build/reports/specmatic
                successCriteria:
                  minCoveragePercentage: 80
                  maxMissedOperationsInSpec: 0
              settings:
                general:
                  logging:
                    level: INFO
                    json:
                      directory: build/logs
                test:
                  maxTestRequestCombinations: 1
                  timeoutInMilliseconds: 2000
                mock:
                  delayInMilliseconds: 250
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ./specs
                      specs:
                        - spec:
                            path: sut.yaml
                  - definition:
                      source:
                        git:
                          url: https://example.com/specs.git
                          auth:
                            bearerEnvironmentVariable: SPECMATIC_BEARER
                      specs:
                        - spec:
                            path: git-sut.yaml
                runOptions:
                  openapi:
                    baseUrl: http://localhost:8080
                data:
                  dictionary:
                    path: dictionary.yaml
                  examples:
                    - directories:
                        - fixed_examples
                        - templated_examples
            dependencies:
              settings:
                delayInMilliseconds: 250
              services:
                - service:
                    definitions:
                      - definition:
                          specs:
                            - spec:
                                path: dep.yaml
                    runOptions:
                      openapi:
                        baseUrl: http://localhost:9000
              data:
                dictionary:
                  path: dictionary.yaml
                examples:
                  - directories:
                      - fixed_examples
                      - templated_examples
            """.trimIndent(),
        )

        assertThat(upgraded.allTextValues()).contains(
            maxCombinations,
            timeout,
            appUrl,
            stubDelay,
            stubBaseUrl,
            dictionary,
            contractDirectory,
            sutSpec,
            dependencySpec,
            examplesDirectory,
            bearerEnvironmentVariable,
            reportDirectory,
            minCoverage,
            maxMissedOperations,
            loggingLevel,
            logDirectory,
        )
    }

    @Test
    fun `does not preserve template expressions that resolve to structured values`() {
        val stubConfig = """${'$'}{STUB_CONFIG:{"delayInMilliseconds":250}}"""
        val examples = """${'$'}{EXAMPLE_DIRS:["examples/a","examples/b"]}"""

        val upgraded = preserveTemplates(
            originalConfigYaml =
            """
            version: 2
            stub: '$stubConfig'
            examples: '$examples'
            """.trimIndent(),
            upgradedConfigYaml =
            """
            version: 3
            specmatic:
              settings:
                mock:
                  delayInMilliseconds: 250
            dependencies:
              data:
                examples:
                  - directories:
                      - examples/a
                      - examples/b
            """.trimIndent(),
        )

        assertThat(upgraded.allTextValues()).doesNotContain(stubConfig, examples)
    }

    private fun preserveTemplates(originalConfigYaml: String, upgradedConfigYaml: String): JsonNode {
        val templatePreservedYaml = ConfigUpgradeTemplatePreserver(yamlMapper)
            .preserveTemplates(originalConfigYaml, upgradedConfigYaml)
        return yamlMapper.readTree(templatePreservedYaml)
    }

    private fun JsonNode.allTextValues(): List<String> {
        return when {
            isTextual -> listOf(asText())
            isObject -> properties().asSequence().flatMap { it.value.allTextValues().asSequence() }.toList()
            isArray -> elements().asSequence().flatMap { it.allTextValues().asSequence() }.toList()
            else -> emptyList()
        }
    }
}
