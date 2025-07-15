package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.ReportFormatterLayout
import io.specmatic.core.ReportFormatterType
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider.filesystem
import io.specmatic.core.SourceProvider.git
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.SpecmaticConfigVersion.Companion.convertToLatestVersionedConfig
import io.specmatic.core.config.v1.SpecmaticConfigV1
import io.specmatic.core.config.v2.ContractConfig
import io.specmatic.core.config.v2.ContractConfig.FileSystemContractSource
import io.specmatic.core.config.v2.ContractConfig.GitContractSource
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.config.v3.Consumes
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.utilities.ContractSourceEntry
import io.specmatic.core.utilities.GitRepo
import io.specmatic.core.utilities.LocalFileSystemSource
import io.specmatic.stub.captureStandardOutput
import io.specmatic.toContractSourceEntries
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

internal class SpecmaticConfigAllTest {
    private val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    @Test
    fun `should return the version from the specmatic config`() {
        val config = """
           {"version": 2}
        """.trimIndent()

        assertThat(config.getVersion()).isEqualTo(SpecmaticConfigVersion.VERSION_2)
    }

    @Test
    fun `should return the version as 1 if it is not present`() {
        assertThat("{}".getVersion()).isEqualTo(SpecmaticConfigVersion.VERSION_1)
    }

    @Test
    fun `should return the version as 1 if it is null in the config`() {
        val config = """
               {"version": null}
            """.trimIndent()

        assertThat(config.getVersion()).isEqualTo(SpecmaticConfigVersion.VERSION_1)
    }

    @Test
    fun `should do nothing for invalid versions`() {
        val config = """
           {"version": 666}
        """.trimIndent()

        assertThat(config.getVersion()).isNull()
    }

    @CsvSource(
        "VERSION_1, ./src/test/resources/specmaticConfigFiles/specmatic_without_version.yaml",
        "VERSION_1, ./src/test/resources/specmaticConfigFiles/specmatic_without_version.json",
        "VERSION_1, ./src/test/resources/specmaticConfigFiles/v1/specmatic_config_v1.yaml",
        "VERSION_1, ./src/test/resources/specmaticConfigFiles/v1/specmatic_config_v1.json",
        "VERSION_2, ./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2.yaml",
        "VERSION_2, ./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2.json"
    )
    @ParameterizedTest
    fun `should create SpecmaticConfig from the versioned specmatic configuration`(version: SpecmaticConfigVersion, configFile: String) {
        val config: SpecmaticConfig = loadSpecmaticConfig(configFile)
        assertThat(config.getVersion()).isEqualTo(version)
        val sources = SpecmaticConfig.getSources(config)
        assertThat(sources.size).isEqualTo(2)
        val expectedSources = listOf(
            Source(
                provider = git,
                repository = "https://contracts",
                branch = "1.0.1",
                test = listOf("com/petstore/1.yaml"),
                stub = listOf(Consumes.StringValue("com/petstore/payment.yaml"))
            ),
            Source(
                provider = filesystem,
                test = listOf("com/petstore/1.yaml"),
                stub = listOf(
                    Consumes.StringValue("com/petstore/payment.yaml"),
                    Consumes.StringValue("com/petstore/order.yaml")
                ),
                directory = "contracts"
            )
        )
        assertThat(sources).containsAll(expectedSources)
    }

    @CsvSource(
        "VERSION_2, ./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2_stub_port.yaml",
        "VERSION_2, ./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2_stub_port.json"
    )
    @ParameterizedTest
    fun `should create SpecmaticConfig from the v2 config with stub ports`(version: SpecmaticConfigVersion, configFile: String) {
        val config: SpecmaticConfig = loadSpecmaticConfig(configFile)
        assertThat(config.getVersion()).isEqualTo(version)
        val sources = SpecmaticConfig.getSources(config)
        assertThat(sources.size).isEqualTo(2)
        val expectedSources = listOf(
            Source(
                provider = git,
                repository = "https://contracts",
                branch = "1.0.1",
                test = listOf("com/petstore/1.yaml"),
                stub = listOf(Consumes.StringValue("com/petstore/payment.yaml"))
            ),
            Source(
                provider = filesystem,
                test = listOf("com/petstore/1.yaml"),
                stub = listOf(
                    Consumes.StringValue("com/petstore/payment.yaml"),
                    Consumes.ObjectValue.FullUrl(
                        specs = listOf("com/petstore/order1.yaml", "com/petstore/order2.yaml"),
                        baseUrl = "http://localhost:9001"
                    )
                ),
                directory = "contracts"
            )
        )
        assertThat(sources).containsAll(expectedSources)
    }

    @CsvSource(
        "./src/test/resources/specmaticConfigFiles/specmatic_config_with_unsupported_version.yaml",
        "./src/test/resources/specmaticConfigFiles/specmatic_config_with_unsupported_version.json"
    )
    @ParameterizedTest
    fun `should throw error when unsupported version is provided`(configFile: String) {
        val specmaticConfigWithInvalidVersion = File(configFile)
        val exception = assertThrows<ContractException> { specmaticConfigWithInvalidVersion.toSpecmaticConfig() }
        assertThat(exception.message).isEqualTo("Unsupported Specmatic config version")
    }

    @CsvSource(
        "./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2.yaml",
        "./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2.json"
    )
    @ParameterizedTest
    fun `should deserialize ContractConfig successfully`(configFile: String) {
        val specmaticConfigV2 = objectMapper.readValue(File(configFile).readText(), SpecmaticConfigV2::class.java)

        val contracts = specmaticConfigV2.contracts

        assertThat(contracts.size).isEqualTo(2)
        assertThat(contracts[0].contractSource).isInstanceOf(GitContractSource::class.java)
        val gitContractSource = contracts[0].contractSource as GitContractSource
        assertThat(gitContractSource.url).isEqualTo("https://contracts")
        assertThat(gitContractSource.branch).isEqualTo("1.0.1")
        assertThat(contracts[0].provides).containsOnly("com/petstore/1.yaml")
        assertThat(contracts[0].consumes).containsOnly(Consumes.StringValue("com/petstore/payment.yaml"))

        assertThat(contracts[1].contractSource).isInstanceOf(FileSystemContractSource::class.java)
        val fileSystemContractSource = contracts[1].contractSource as FileSystemContractSource
        assertThat(fileSystemContractSource.directory).isEqualTo("contracts")
        assertThat(contracts[1].provides).containsOnly("com/petstore/1.yaml")
        assertThat(contracts[1].consumes).containsOnly(
            Consumes.StringValue("com/petstore/payment.yaml"),
            Consumes.StringValue("com/petstore/order.yaml")
        )
    }

    @Test
    fun `should serialize ContractConfig successfully`() {
        val expectedContractsJson = """[
            {
                "git": {
                    "url": "https://contracts",
                    "branch": "1.0.1"
                },
                "provides": ["com/petstore/1.yaml"],
                "consumes": ["com/petstore/payment.yaml"]
            },
            {
                "filesystem": {
                    "directory": "contracts"
                },
                "provides": ["com/petstore/1.yaml"],
                "consumes": ["com/petstore/payment.yaml", "com/petstore/order.yaml"]
            }
        ]"""

        val contracts = listOf(
            ContractConfig(
                contractSource = GitContractSource(url = "https://contracts", branch = "1.0.1"),
                provides = listOf("com/petstore/1.yaml"),
                consumes = listOf(Consumes.StringValue("com/petstore/payment.yaml"))
            ),
            ContractConfig(
                contractSource = FileSystemContractSource(directory = "contracts"),
                provides = listOf("com/petstore/1.yaml"),
                consumes = listOf(
                    Consumes.StringValue("com/petstore/payment.yaml"),
                    Consumes.StringValue("com/petstore/order.yaml")
                )
            )
        )

        val objectMapper =
            ObjectMapper().registerKotlinModule().setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        val contractsJson = objectMapper.writeValueAsString(contracts)

        assertThat(parsedJSON(contractsJson)).isEqualTo(parsedJSON(expectedContractsJson))
    }

    @CsvSource(
        "./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2_stub_port.yaml",
        "./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2_stub_port.json"
    )
    @ParameterizedTest
    fun `should deserialize ContractConfig with stub port config successfully`(configFile: String) {
        val objectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        val SpecmaticConfigV2 = objectMapper.readValue(File(configFile).readText(), SpecmaticConfigV2::class.java)

        val contracts = SpecmaticConfigV2.contracts

        assertThat(contracts.size).isEqualTo(2)
        assertThat(contracts[0].contractSource).isInstanceOf(GitContractSource::class.java)
        val gitContractSource = contracts[0].contractSource as GitContractSource
        assertThat(gitContractSource.url).isEqualTo("https://contracts")
        assertThat(gitContractSource.branch).isEqualTo("1.0.1")
        assertThat(contracts[0].provides).containsOnly("com/petstore/1.yaml")
        assertThat(contracts[0].consumes).containsOnly(Consumes.StringValue("com/petstore/payment.yaml"))

        assertThat(contracts[1].contractSource).isInstanceOf(FileSystemContractSource::class.java)
        val fileSystemContractSource = contracts[1].contractSource as FileSystemContractSource
        assertThat(fileSystemContractSource.directory).isEqualTo("contracts")
        assertThat(contracts[1].provides).containsOnly("com/petstore/1.yaml")
        assertThat(contracts[1].consumes).containsOnly(
            Consumes.StringValue("com/petstore/payment.yaml"),
            Consumes.ObjectValue.FullUrl(
                specs = listOf("com/petstore/order1.yaml", "com/petstore/order2.yaml"),
                baseUrl = "http://localhost:9001"
            )
        )
    }

    @Test
    fun `should serialize ContractConfig with stub port config successfully`() {
        val expectedContractsJson = """[
            {
                "git": {
                    "url": "https://contracts",
                    "branch": "1.0.1"
                },
                "provides": ["com/petstore/1.yaml"],
                "consumes": ["com/petstore/payment.yaml"]
            },
            {
                "filesystem": {
                    "directory": "contracts"
                },
                "provides": ["com/petstore/1.yaml"],
                "consumes": [
                    "com/petstore/payment.yaml",
                    {
                        "specs": ["com/petstore/order.yaml"],
                        "baseUrl": "http://localhost:9001"
                    }
                ]
            }
        ]"""

        val contracts = listOf(
            ContractConfig(
                contractSource = GitContractSource(url = "https://contracts", branch = "1.0.1"),
                provides = listOf("com/petstore/1.yaml"),
                consumes = listOf(
                    Consumes.StringValue("com/petstore/payment.yaml")
                )
            ),
            ContractConfig(
                contractSource = FileSystemContractSource(directory = "contracts"),
                provides = listOf("com/petstore/1.yaml"),
                consumes = listOf(
                    Consumes.StringValue("com/petstore/payment.yaml"),
                    Consumes.ObjectValue.FullUrl(
                        specs = listOf("com/petstore/order.yaml"),
                        baseUrl = "http://localhost:9001"
                    )
                )
            )
        )

        val objectMapper =
            ObjectMapper().registerKotlinModule().setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        val contractsJson = objectMapper.writeValueAsString(contracts)

        assertThat(parsedJSON(contractsJson)).isEqualTo(parsedJSON(expectedContractsJson))
    }

    @Test
    fun `should deserialize ContractConfig successfully when branch field is absent`() {
        val contractConfigYaml = """
            git:
              url: https://contracts
            provides:
              - com/petstore/1.yaml
            consumes:
              - com/petstore/payment.yaml
        """.trimIndent()

        val contractConfig = objectMapper.readValue(contractConfigYaml, ContractConfig::class.java)

        assertThat(contractConfig.contractSource).isInstanceOf(GitContractSource::class.java)
        assertThat((contractConfig.contractSource as GitContractSource).url).isEqualTo("https://contracts")
        assertThat(contractConfig.provides).containsOnly("com/petstore/1.yaml")
        assertThat(contractConfig.consumes).containsOnly(Consumes.StringValue("com/petstore/payment.yaml"))
    }

    @Test
    fun `should deserialize ContractConfigV2 successfully when branch field is absent`() {
        val contractConfigYaml = """
            git:
              url: https://contracts
            provides:
              - com/petstore/1.yaml
            consumes:
              - com/petstore/payment.yaml
              - baseUrl: http://localhost:9001
                specs:
                - com/petstore/order.yaml
        """.trimIndent()

        val contractConfig = objectMapper.readValue(contractConfigYaml, ContractConfig::class.java)

        assertThat(contractConfig.contractSource).isInstanceOf(GitContractSource::class.java)
        assertThat((contractConfig.contractSource as GitContractSource).url).isEqualTo("https://contracts")
        assertThat(contractConfig.provides).containsOnly("com/petstore/1.yaml")
        assertThat((contractConfig.consumes?.get(0) as Consumes.StringValue).value)
            .isEqualTo("com/petstore/payment.yaml")
        val consumesObjectValue = contractConfig.consumes?.get(1) as Consumes.ObjectValue
        assertThat(consumesObjectValue.toBaseUrl()).isEqualTo("http://localhost:9001")
        assertThat(consumesObjectValue.specs).containsOnly("com/petstore/order.yaml")
    }

    @Test
    fun `should deserialize ContractConfig successfully when provides is absent`() {
        val contractConfigYaml = """
            git:
              url: https://contracts
            consumes:
              - com/petstore/payment.yaml
        """.trimIndent()

        val contractConfig = objectMapper.readValue(contractConfigYaml, ContractConfig::class.java)

        assertThat(contractConfig.contractSource).isInstanceOf(GitContractSource::class.java)
        assertThat((contractConfig.contractSource as GitContractSource).url).isEqualTo("https://contracts")
        assertThat(contractConfig.provides).isNull()
        assertThat(contractConfig.consumes).containsOnly(Consumes.StringValue("com/petstore/payment.yaml"))
    }

    @Test
    fun `should deserialize ContractConfigV2 successfully when provides is absent`() {
        val contractConfigYaml = """
            git:
              url: https://contracts
            consumes:
              - com/petstore/payment.yaml
              - baseUrl: http://localhost:9001
                specs:
                - com/petstore/order.yaml
        """.trimIndent()

        val contractConfig = objectMapper.readValue(contractConfigYaml, ContractConfig::class.java)

        assertThat(contractConfig.contractSource).isInstanceOf(GitContractSource::class.java)
        assertThat((contractConfig.contractSource as GitContractSource).url).isEqualTo("https://contracts")
        assertThat(contractConfig.provides).isNull()
        assertThat((contractConfig.consumes?.get(0) as Consumes.StringValue).value)
            .isEqualTo("com/petstore/payment.yaml")
        val consumesObjectValue = contractConfig.consumes?.get(1) as Consumes.ObjectValue
        assertThat(consumesObjectValue.toBaseUrl()).isEqualTo("http://localhost:9001")
        assertThat(consumesObjectValue.specs).containsOnly("com/petstore/order.yaml")
    }

    @Test
    fun `should deserialize ContractConfig successfully when consumes is absent`() {
        val contractConfigYaml = """
            git:
              url: https://contracts
            provides:
              - com/petstore/1.yaml
        """.trimIndent()

        val contractConfig = objectMapper.readValue(contractConfigYaml, ContractConfig::class.java)

        assertThat(contractConfig.contractSource).isInstanceOf(GitContractSource::class.java)
        assertThat((contractConfig.contractSource as GitContractSource).url).isEqualTo("https://contracts")
        assertThat(contractConfig.provides).containsOnly("com/petstore/1.yaml")
        assertThat(contractConfig.consumes).isNull()
    }

    @Test
    fun `should deserialize ContractConfigV2 successfully when consumes is absent`() {
        val contractConfigYaml = """
            git:
              url: https://contracts
            provides:
              - com/petstore/1.yaml
        """.trimIndent()

        val contractConfig = objectMapper.readValue(contractConfigYaml, ContractConfig::class.java)

        assertThat(contractConfig.contractSource).isInstanceOf(GitContractSource::class.java)
        assertThat((contractConfig.contractSource as GitContractSource).url).isEqualTo("https://contracts")
        assertThat(contractConfig.provides).containsOnly("com/petstore/1.yaml")
        assertThat(contractConfig.consumes).isNull()
    }

    @Test
    fun `should deserialize SpecmaticConfig successfully when VirtualService key is present`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml")
        val configYaml = """
            virtualService:
                nonPatchableKeys:
                    - description
                    - url
        """.trimIndent()
        configFile.writeText(configYaml)

        val specmaticConfig = configFile.toSpecmaticConfig()

        val nonPatchableKeys = specmaticConfig.getVirtualServiceNonPatchableKeys()
        assertThat(nonPatchableKeys.size).isEqualTo(2)
        assertThat(nonPatchableKeys).containsExactly("description", "url")
    }

    @Test
    fun `should convert config with VirtualService from v1 to v2`() {
        val configYaml = """
            virtualService:
                nonPatchableKeys:
                    - description
                    - url
        """.trimIndent()

        val config = objectMapper.readValue(configYaml, SpecmaticConfigV1::class.java).transform()
        val configV2 = SpecmaticConfigV2.loadFrom(config) as SpecmaticConfigV2

        assertThat(configV2.virtualService.getNonPatchableKeys()).containsExactly("description", "url")
    }

    @Test
    fun `should convert config with VirtualService from v2 to v3`() {
        val configYaml = """
            version: 2
            virtualService:
                nonPatchableKeys:
                    - description
                    - url
        """.trimIndent()

        val config = objectMapper.readValue(configYaml, SpecmaticConfigV2::class.java).transform()
        val configV3 = SpecmaticConfigV2.loadFrom(config) as SpecmaticConfigV2

        assertThat(configV3.virtualService.getNonPatchableKeys()).containsExactly("description", "url")
    }

    @Test
    fun `should deserialize SpecmaticConfig successfully when AttributeSelectionPattern key is present`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml")
        val configYaml = """
            attributeSelectionPattern:
                default_fields:
                    - description
                    - url
                query_param_key: web
        """.trimIndent()
        configFile.writeText(configYaml)

        val specmaticConfig = configFile.toSpecmaticConfig()

        assertThat(specmaticConfig.getAttributeSelectionPattern().getDefaultFields()).containsExactly(
            "description",
            "url"
        )
        assertThat(specmaticConfig.getAttributeSelectionPattern().getQueryParamKey()).isEqualTo("web")
    }

    @Test
    fun `should convert config with AttributeSelectionPattern from v1 to v2`() {
        val configYaml = """
            attributeSelectionPattern:
                default_fields:
                    - description
                    - url
                query_param_key: web
        """.trimIndent()

        val config = objectMapper.readValue(configYaml, SpecmaticConfigV1::class.java).transform()
        val configV2 = SpecmaticConfigV2.loadFrom(config) as SpecmaticConfigV2

        assertThat(configV2.attributeSelectionPattern.getDefaultFields()).containsExactly("description", "url")
        assertThat(configV2.attributeSelectionPattern.getQueryParamKey()).isEqualTo("web")
    }

    @Test
    fun `should convert config with AttributeSelectionPattern from v2 to v3`() {
        val configYaml = """
            version: 2
            attributeSelectionPattern:
                default_fields:
                    - description
                    - url
                query_param_key: web
        """.trimIndent()

        val config = objectMapper.readValue(configYaml, SpecmaticConfigV2::class.java).transform()
        val configV3 = SpecmaticConfigV2.loadFrom(config) as SpecmaticConfigV2

        assertThat(configV3.attributeSelectionPattern.getDefaultFields()).containsExactly("description", "url")
        assertThat(configV3.attributeSelectionPattern.getQueryParamKey()).isEqualTo("web")
    }

    @Test
    fun `should deserialize SpecmaticConfig successfully when AllPatternsMandatory is present`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml")
        val configYaml = """
            allPatternsMandatory: true
        """.trimIndent()
        configFile.writeText(configYaml)

        val specmaticConfig = configFile.toSpecmaticConfig()

        assertThat(specmaticConfig.getAllPatternsMandatory()).isEqualTo(true)
    }

    @Test
    fun `should convert config from v1 to v2 when AllPatternsMandatory key is present`() {
        val configYaml = """
            allPatternsMandatory: true
        """.trimIndent()

        val config = objectMapper.readValue(configYaml, SpecmaticConfigV1::class.java).transform()
        val configV2 = SpecmaticConfigV2.loadFrom(config) as SpecmaticConfigV2

        assertThat(configV2.allPatternsMandatory).isTrue()
    }

    @Test
    fun `should convert config from v2 to v3 when AllPatternsMandatory key is present`() {
        val configYaml = """
            version: 2
            allPatternsMandatory: true
        """.trimIndent()

        val config = objectMapper.readValue(configYaml, SpecmaticConfigV2::class.java).transform()
        val configV3 = SpecmaticConfigV2.loadFrom(config) as SpecmaticConfigV2

        assertThat(configV3.allPatternsMandatory).isTrue()
    }

    @Test
    fun `should deserialize SpecmaticConfig successfully when IgnoreInlineExamples key is present`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml")
        val configYaml = """
            ignoreInlineExamples: true
        """.trimIndent()
        configFile.writeText(configYaml)

        val specmaticConfig = configFile.toSpecmaticConfig()

        assertThat(specmaticConfig.getIgnoreInlineExamples()).isTrue()
    }

    @Test
    fun `should convert config from v1 to v2 when IgnoreInlineExamples key is present`() {
        val configYaml = """
            ignoreInlineExamples: true
        """.trimIndent()

        val config = objectMapper.readValue(configYaml, SpecmaticConfigV1::class.java).transform()
        val configV2 = SpecmaticConfigV2.loadFrom(config) as SpecmaticConfigV2

        assertThat(configV2.ignoreInlineExamples).isTrue()
    }

    @Test
    fun `should convert config from v2 to v3 when IgnoreInlineExamples key is present`() {
        val configYaml = """
            version: 2
            ignoreInlineExamples: true
        """.trimIndent()

        val config = objectMapper.readValue(configYaml, SpecmaticConfigV2::class.java).transform()
        val configV3 = SpecmaticConfigV2.loadFrom(config) as SpecmaticConfigV2

        assertThat(configV3.ignoreInlineExamples).isTrue()
    }

    @Test
    fun `should deserialize test configuration in SpecmaticConfig successfully`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml")
        val configYaml = """
            test:
                resiliencyTests:
                    enable: all
                validateResponseValues: true
                allowExtensibleSchema: true
                timeoutInMilliseconds: 10
        """.trimIndent()
        configFile.writeText(configYaml)

        val specmaticConfig = configFile.toSpecmaticConfig()

        specmaticConfig.apply {
            assertThat(isResiliencyTestingEnabled()).isTrue()
            assertThat(isResponseValueValidationEnabled()).isTrue()
            assertThat(isExtensibleSchemaEnabled()).isTrue()
            assertThat(getTestTimeoutInMilliseconds()).isEqualTo(10)
        }
    }

    @Test
    fun `should convert config from v1 to v2 when test configuration is present`() {
        val configYaml = """
            test:
                resiliencyTests:
                    enable: all
                validateResponseValues: true
                allowExtensibleSchema: true
                timeoutInMilliseconds: 10
        """.trimIndent()

        val configFromV1 = objectMapper.readValue(configYaml, SpecmaticConfigV1::class.java).transform()
        val configV2 = SpecmaticConfigV2.loadFrom(configFromV1) as SpecmaticConfigV2

        configV2.test!!.apply {
            assertThat(resiliencyTests?.enable).isEqualTo(ResiliencyTestSuite.all)
            assertThat(validateResponseValues).isTrue()
            assertThat(allowExtensibleSchema).isTrue()
            assertThat(timeoutInMilliseconds).isEqualTo(10)
        }
    }

    @Test
    fun `should convert config from v2 to v3 when test configuration is present`() {
        val configYaml = """
            version: 2
            test:
                resiliencyTests:
                    enable: all
                validateResponseValues: true
                allowExtensibleSchema: true
                timeoutInMilliseconds: 10
        """.trimIndent()

        val configFromV2 = objectMapper.readValue(configYaml, SpecmaticConfigV2::class.java).transform()
        val configV3 = SpecmaticConfigV2.loadFrom(configFromV2) as SpecmaticConfigV2

        configV3.test!!.apply {
            assertThat(resiliencyTests?.enable).isEqualTo(ResiliencyTestSuite.all)
            assertThat(validateResponseValues).isTrue()
            assertThat(allowExtensibleSchema).isTrue()
            assertThat(timeoutInMilliseconds).isEqualTo(10)
        }
    }

    @Test
    fun `should deserialize stub configuration in SpecmaticConfig successfully`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml")
        val configYaml = """
            stub:
                generative: true
                delayInMilliseconds: 1000
                dictionary: stubDictionary
                includeMandatoryAndRequestedKeysInResponse: true
        """.trimIndent()
        configFile.writeText(configYaml)

        val specmaticConfig = configFile.toSpecmaticConfig()

        specmaticConfig.apply {
            assertThat(getStubGenerative()).isTrue()
            assertThat(getStubDelayInMilliseconds()).isEqualTo(1000L)
            assertThat(getStubDictionary()).isEqualTo("stubDictionary")
            assertThat(getStubIncludeMandatoryAndRequestedKeysInResponse()).isTrue()
        }
    }

    @Test
    fun `should convert config from v1 to v2 when stub configuration is present`() {
        val configYaml = """
            stub:
                generative: true
                delayInMilliseconds: 1000
                dictionary: stubDictionary
                includeMandatoryAndRequestedKeysInResponse: true
        """.trimIndent()

        val configFromV1 = objectMapper.readValue(configYaml, SpecmaticConfigV1::class.java).transform()
        val configV2 = SpecmaticConfigV2.loadFrom(configFromV1) as SpecmaticConfigV2

        configV2.stub?.apply {
            assertThat(generative).isTrue()
            assertThat(delayInMilliseconds).isEqualTo(1000L)
            assertThat(dictionary).isEqualTo("stubDictionary")
            assertThat(includeMandatoryAndRequestedKeysInResponse).isTrue()
        }
    }

    @Test
    fun `should convert config from v2 to v3 when stub configuration is present`() {
        val configYaml = """
            version: 2
            stub:
                generative: true
                delayInMilliseconds: 1000
                dictionary: stubDictionary
                includeMandatoryAndRequestedKeysInResponse: true
        """.trimIndent()

        val configFromV2 = objectMapper.readValue(configYaml, SpecmaticConfigV2::class.java).transform()
        val configV3 = SpecmaticConfigV2.loadFrom(configFromV2) as SpecmaticConfigV2

        configV3.stub?.apply {
            assertThat(generative).isTrue()
            assertThat(delayInMilliseconds).isEqualTo(1000L)
            assertThat(dictionary).isEqualTo("stubDictionary")
            assertThat(includeMandatoryAndRequestedKeysInResponse).isTrue()
        }
    }

    @Test
    fun `should deserialize workflow configuration in SpecmaticConfig successfully`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml")
        val configYaml = """
            workflow:
              ids:
                "POST / -> 201":
                  extract: "BODY.id"
                "*":
                  use: "PATH.id"
        """.trimIndent()
        configFile.writeText(configYaml)

        val specmaticConfig = configFile.toSpecmaticConfig()

        specmaticConfig.apply {
            assertThat(specmaticConfig.getWorkflowDetails()?.getUseForAPI("*")).isEqualTo("PATH.id")
            assertThat(specmaticConfig.getWorkflowDetails()?.getExtractForAPI("POST / -> 201")).isEqualTo("BODY.id")
        }
    }

    @Test
    fun `should convert config from v1 to v2 when workflow configuration is present`() {
        val configYaml = """
            workflow:
              ids:
                "POST / -> 201":
                  extract: "BODY.id"
                "*":
                  use: "PATH.id"
        """.trimIndent()

        val configFromV1 = objectMapper.readValue(configYaml, SpecmaticConfigV1::class.java).transform()
        val configV2 = SpecmaticConfigV2.loadFrom(configFromV1) as SpecmaticConfigV2

        configV2.workflow!!.apply {
            assertThat(getUseForAPI("*")).isEqualTo("PATH.id")
            assertThat(getExtractForAPI("POST / -> 201")).isEqualTo("BODY.id")
        }
    }

    @Test
    fun `should convert config from v2 to v3 when workflow configuration is present`() {
        val configYaml = """
            version: 2
            workflow:
              ids:
                "POST / -> 201":
                  extract: "BODY.id"
                "*":
                  use: "PATH.id"
        """.trimIndent()

        val configFromV2 = objectMapper.readValue(configYaml, SpecmaticConfigV2::class.java).transform()
        val configV3 = SpecmaticConfigV2.loadFrom(configFromV2) as SpecmaticConfigV2

        configV3.workflow!!.apply {
            assertThat(getUseForAPI("*")).isEqualTo("PATH.id")
            assertThat(getExtractForAPI("POST / -> 201")).isEqualTo("BODY.id")
        }
    }

    @Test
    fun `when the source v1 config has a source of filesystem type with no directory it gets converted to v2 with no source` () {
        val contractConfigYaml = """
            sources:
              - provides:
                - com/petstore/1.yaml
        """.trimIndent()

        val configV1 = objectMapper.readValue(contractConfigYaml, SpecmaticConfigV1::class.java)

        val dslConfig = configV1.transform()

        val configV2 = SpecmaticConfigV2.loadFrom(dslConfig) as SpecmaticConfigV2

        assertThat(configV2.contracts.first().contractSource).isNull()
    }

    @Test
    fun `when the source v1 config has a source of git type with no directory it gets converted to v2 with git source written out` () {
        val contractConfigYaml = """
            sources:
              - provider: git
                repository: http://source.in
                provides:
                - com/petstore/1.yaml
        """.trimIndent()

        val configV1 = objectMapper.readValue(contractConfigYaml, SpecmaticConfigV1::class.java)

        val dslConfig = configV1.transform()

        val configV2 = SpecmaticConfigV2.loadFrom(dslConfig) as SpecmaticConfigV2

        val contractSource = configV2.contracts.first().contractSource

        assertThat(contractSource).isNotNull()
        assertThat(contractSource).isInstanceOf(GitContractSource::class.java)
    }

    @Test
    fun `v2 with report gets deserialized` () {
        val contractConfigYaml = """
            version: 2
            contracts:
              - git:
                  url: http://source.in
                provides:
                - com/petstore/1.yaml
            report:
              formatters:
                - type: text
                  layout: table
              types:
                APICoverage:
                  OpenAPI:
                    successCriteria:
                      minThresholdPercentage: 70
                      maxMissedEndpointsInSpec: 0
                      enforce: true
                    excludedEndpoints:
                      - /health
        """.trimIndent()

        val configV2 = objectMapper.readValue(contractConfigYaml, SpecmaticConfigV2::class.java)

        val objectMapper =
            ObjectMapper(YAMLFactory()).registerKotlinModule().setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        val configText = objectMapper.writeValueAsString(configV2)
        println(configText)

        assertThat(configText)
            .contains("OpenAPI")
            .contains("APICoverage")

        assertThat(configV2.report?.formatters?.get(0)?.type).isEqualTo(ReportFormatterType.TEXT)
        assertThat(configV2.report?.formatters?.get(0)?.layout).isEqualTo(ReportFormatterLayout.TABLE)

        assertThat(configV2.report?.types?.apiCoverage?.openAPI?.successCriteria?.minThresholdPercentage).isEqualTo(70)
        assertThat(configV2.report?.types?.apiCoverage?.openAPI?.successCriteria?.maxMissedEndpointsInSpec).isEqualTo(0)
        assertThat(configV2.report?.types?.apiCoverage?.openAPI?.successCriteria?.enforce).isEqualTo(true)
    }

    @Test
    fun `v1 config with report gets converted to v2` () {
        val contractConfigYaml = """
            sources:
              - provider: git
                repository: http://source.in
                provides:
                - com/petstore/1.yaml
            report:
              formatters:
                - type: text
                  layout: table
              types:
                APICoverage:
                  OpenAPI:
                    successCriteria:
                      minThresholdPercentage: 70
                      maxMissedEndpointsInSpec: 0
                      enforce: true
                    excludedEndpoints:
                      - /health
        """.trimIndent()
        val expectedReportConfigurationJson = parsedJSON(
            """
            {
              "formatters": [
                {
                  "type": "text",
                  "layout": "table"
                }
              ],
              "types": {
                "APICoverage": {
                  "OpenAPI": {
                    "successCriteria": {
                      "minThresholdPercentage": 70,
                      "maxMissedEndpointsInSpec": 0,
                      "enforce": true
                    }
                  }
                }
              }
            }
        """.trimIndent()
        )

        val configV1 = objectMapper.readValue(contractConfigYaml, SpecmaticConfigV1::class.java)

        val dslConfig = configV1.transform()

        val (output, configV2) = captureStandardOutput {
            convertToLatestVersionedConfig(dslConfig) as SpecmaticConfigV2
        }

        assertThat(output).contains("WARNING")

        val jsonObjectMapper =
            ObjectMapper().registerKotlinModule().setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        val actualReportConfigurationJson = parsedJSON(jsonObjectMapper.writeValueAsString(configV2.report))

        assertThat(actualReportConfigurationJson).isEqualTo(expectedReportConfigurationJson)

        assertThat(configV2.report?.formatters?.get(0)?.type).isEqualTo(ReportFormatterType.TEXT)
        assertThat(configV2.report?.formatters?.get(0)?.layout).isEqualTo(ReportFormatterLayout.TABLE)

        assertThat(configV2.report?.types?.apiCoverage?.openAPI?.successCriteria?.minThresholdPercentage).isEqualTo(70)
        assertThat(configV2.report?.types?.apiCoverage?.openAPI?.successCriteria?.maxMissedEndpointsInSpec).isEqualTo(0)
        assertThat(configV2.report?.types?.apiCoverage?.openAPI?.successCriteria?.enforce).isEqualTo(true)
    }

    @Test
    fun `v2 config with auth gets deserialized` () {
        val contractConfigYaml = """
            version: 2
            contracts:
              - git:
                  url: http://source.in
                provides:
                  - com/petstore/1.yaml
            auth:
              bearer-file: bearer.txt
        """.trimIndent()

        val configV2 = objectMapper.readValue(contractConfigYaml, SpecmaticConfigV2::class.java)

        val objectMapper =
            ObjectMapper(YAMLFactory()).registerKotlinModule().setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        val configText = objectMapper.writeValueAsString(configV2)
        println(configText)

        assertThat(configText)
            .contains("bearer-file")

        assertThat(configV2.auth?.bearerFile).isEqualTo("bearer.txt")
    }

    @Test
    fun `v1 config with auth gets converted to v2` () {
        val contractConfigYaml = """
            sources:
              - provider: git
                repository: http://source.in
                provides:
                - com/petstore/1.yaml
            auth:
              bearer-file: bearer.txt
        """.trimIndent()

        val configV1 = objectMapper.readValue(contractConfigYaml, SpecmaticConfigV1::class.java)

        val dslConfig = configV1.transform()

        val configV2 = SpecmaticConfigV2.loadFrom(dslConfig) as SpecmaticConfigV2

        val objectMapper =
            ObjectMapper(YAMLFactory()).registerKotlinModule().setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        val configText = objectMapper.writeValueAsString(configV2)
        println(configText)

        assertThat(configText)
            .contains("bearer-file")

        assertThat(configV2.auth?.bearerFile).isEqualTo("bearer.txt")
    }

    @CsvSource(
        "VERSION_1, ./src/test/resources/specmaticConfigFiles/specmatic_without_version.yaml",
        "VERSION_1, ./src/test/resources/specmaticConfigFiles/specmatic_without_version.json",
        "VERSION_1, ./src/test/resources/specmaticConfigFiles/v1/specmatic_config_v1.yaml",
        "VERSION_1, ./src/test/resources/specmaticConfigFiles/v1/specmatic_config_v1.json",
        "VERSION_2, ./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2.yaml",
        "VERSION_2, ./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2.json",
    )
    @ParameterizedTest
    fun `given specmatic config less than v3 it should load sources`(version: SpecmaticConfigVersion, configFile: String) {
        val config: SpecmaticConfig = loadSpecmaticConfig(configFile)
        assertThat(config.getVersion()).isEqualTo(version)
        val contractSources = config.loadSources()

        val expectedContractSources = listOf(
            GitRepo(
                gitRepositoryURL = "https://contracts",
                branchName = "1.0.1",
                testContracts = listOf("com/petstore/1.yaml").toContractSourceEntries(),
                stubContracts = listOf("com/petstore/payment.yaml").toContractSourceEntries(),
                type = git.name
            ),
            LocalFileSystemSource(
                directory = "contracts",
                testContracts = listOf("com/petstore/1.yaml").toContractSourceEntries(),
                stubContracts = listOf("com/petstore/payment.yaml", "com/petstore/order.yaml").toContractSourceEntries()
            )
        )

        assertThat(contractSources[0]).isEqualTo(expectedContractSources[0])
        assertThat(contractSources[1]).isEqualTo(expectedContractSources[1])
    }

    @CsvSource(
        "VERSION_2, ./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2_stub_port.yaml",
        "VERSION_2, ./src/test/resources/specmaticConfigFiles/v2/specmatic_config_v2_stub_port.json"
    )
    @ParameterizedTest
    fun `given specmatic config v2 with stub port config, it should load sources`(version: SpecmaticConfigVersion, configFile: String) {
        val config: SpecmaticConfig = loadSpecmaticConfig(configFile)
        assertThat(config.getVersion()).isEqualTo(version)
        val contractSources = config.loadSources()

        val expectedContractSources = listOf(
            GitRepo(
                gitRepositoryURL = "https://contracts",
                branchName = "1.0.1",
                testContracts = listOf("com/petstore/1.yaml").toContractSourceEntries(),
                stubContracts = listOf("com/petstore/payment.yaml").toContractSourceEntries(),
                type = git.name
            ),
            LocalFileSystemSource(
                directory = "contracts",
                testContracts = listOf("com/petstore/1.yaml").toContractSourceEntries(),
                stubContracts = listOf(
                    ContractSourceEntry("com/petstore/payment.yaml"),
                    ContractSourceEntry("com/petstore/order1.yaml", "http://localhost:9001"),
                    ContractSourceEntry("com/petstore/order2.yaml", "http://localhost:9001")
                )
            )
        )

        assertThat(contractSources[0]).isEqualTo(expectedContractSources[0])
        assertThat(contractSources[1]).isEqualTo(expectedContractSources[1])
    }
}