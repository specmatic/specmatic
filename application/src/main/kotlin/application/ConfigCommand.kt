package application

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.config.EmptyConfigCollectionFilter
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticConfigVersion.Companion.convertToLatestVersionedConfig
import io.specmatic.core.config.SpecmaticConfigVersion.Companion.getLatestVersion
import io.specmatic.core.config.SpecmaticConfigVersion.Companion.isValidVersion
import io.specmatic.core.config.getVersion
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.validation.ConfigValidationResult
import io.specmatic.core.config.validation.ConfigValidationSeverity
import io.specmatic.core.config.validation.ConfigSchemaOutputFormat
import io.specmatic.core.config.validation.SpecmaticConfigValidator
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exitWithMessage
import io.specmatic.license.core.cli.Category
import kotlinx.serialization.json.Json
import picocli.CommandLine.*
import java.io.File
import java.util.concurrent.Callable
import kotlin.system.exitProcess

private const val SUCCESS_EXIT_CODE = 0

private const val SPECMATIC_CONFIGURATION = "Specmatic Configuration"

@Command(
    name = "config",
    mixinStandardHelpOptions = true,
    description = ["Manage and configure $SPECMATIC_CONFIGURATION."],
    subcommands = [
        ConfigCommand.Upgrade::class,
        ConfigCommand.Validate::class,
    ]
)
@Category("Specmatic core")
class ConfigCommand : Callable<Int> {
    override fun call(): Int {
        println("Use a subcommand. Use --help for more details.")
        return SUCCESS_EXIT_CODE
    }

    @Command(
        name = "upgrade",
        mixinStandardHelpOptions = true,
        description = ["Upgrade $SPECMATIC_CONFIGURATION to the latest version."]
    )
    class Upgrade : Callable<Int> {
        @Option(names = ["--input"], description = ["Path to $SPECMATIC_CONFIGURATION file that needs to updated."])
        var inputFile: File? = null

        @Option(
            names = ["--output"], description = ["File to write the updated $SPECMATIC_CONFIGURATION to. " +
                    "If not provided, the configuration will be logged in the console."]
        )
        val outputFile: File? = null

        override fun call(): Int {
            try {
                val configFile = getConfigFile()
                configFile.readText().getVersion().let { existingVersion ->
                    exitIfAlreadyUpToDate(existingVersion)
                    exitIfInvalidVersion(existingVersion)
                }

                upgrade(configFile)
                return SUCCESS_EXIT_CODE
            } catch (e: Exception) {
                exitWithMessage(e.message.orEmpty())
            }
        }

        private fun upgrade(configFile: File) {
            val upgradedConfigYaml =
                getObjectMapper().writeValueAsString(convertToLatestVersionedConfig(configFile.toSpecmaticConfig()))

            if(outputFile == null) {
                logger.log(upgradedConfigYaml)
                return
            }

            logger.log("Writing upgraded $SPECMATIC_CONFIGURATION to ${outputFile.path}")
            outputFile.writeText(upgradedConfigYaml)
            logger.log("The upgraded $SPECMATIC_CONFIGURATION is written successfully to ${outputFile.path}")
        }

        private fun getObjectMapper(): ObjectMapper {
            val objectMapper = ObjectMapper(YAMLFactory()).apply {
                registerKotlinModule()
                setDefaultPropertyInclusion(
                    JsonInclude.Value.construct(
                        JsonInclude.Include.CUSTOM,
                        JsonInclude.Include.CUSTOM
                    ).withValueFilter(EmptyConfigCollectionFilter::class.java)
                )
            }
            return objectMapper
        }

        private fun exitIfAlreadyUpToDate(existingVersion: SpecmaticConfigVersion?) {
            if (existingVersion == getLatestVersion()) {
                logger.log("The provided $SPECMATIC_CONFIGURATION file is already up-to-date")
                exitProcess(SUCCESS_EXIT_CODE)
            }
        }

        private fun exitIfInvalidVersion(existingVersion: SpecmaticConfigVersion?) {
            if (existingVersion == null || isValidVersion(existingVersion).not()) {
                exitWithMessage("The provided $SPECMATIC_CONFIGURATION file does not have a valid version. Please provide a valid $SPECMATIC_CONFIGURATION file.")
            }
        }

        private fun getConfigFile(): File {
            val configFile = inputFile ?: File(getConfigFilePath()).takeIf { it.exists() }
            if(configFile == null) {
                exitWithMessage(
                    "Default $SPECMATIC_CONFIGURATION file named " +
                            "specmatic.yaml/specmatic.yml/specmatic.json not found. " +
                            "Please provide the valid configuration file path using --input option."
                )
            }
            return configFile
        }
    }

    @Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = ["Validate $SPECMATIC_CONFIGURATION against its schema and semantic rules."],
    )
    class Validate : Callable<Int> {
        private val validator = SpecmaticConfigValidator()
        private val renderer = ConfigValidationTextRenderer()
        @Option(
            names = ["--input"],
            description = ["Path to the $SPECMATIC_CONFIGURATION file. Defaults to the normal config path."],
        )
        var inputFile: File? = null

        @Option(
            names = ["--format"],
            defaultValue = "text",
            description = ["Output format: text or json."],
            converter = [OutputFormatConverter::class],
        )
        var format: OutputFormat = OutputFormat.TEXT

        @Option(
            names = ["--schema-output"],
            converter = [SchemaOutputFormatConverter::class],
            description = ["Schema validation output: list or hierarchical. Defaults to list for text and hierarchical for JSON."],
        )
        var schemaOutput: ConfigSchemaOutputFormat? = null

        override fun call(): Int {
            val configFile = inputFile ?: File(getConfigFilePath())
            val content = try {
                configFile.readText()
            } catch (e: Exception) {
                val result = unreadableFile(configFile, e)
                print(result, configFile, "")
                return 1
            }

            val result = validator.validate(content, configFile.toPath(), schemaOutput ?: defaultSchemaOutput())
            print(result, configFile, content)
            return if (result is ConfigValidationResult.Valid) 0 else 1
        }

        private fun print(result: ConfigValidationResult, configFile: File, content: String) {
            when (format) {
                OutputFormat.JSON -> println(serializeOutputs(result))
                OutputFormat.TEXT -> println(renderer.render(configFile.name, content, result))
            }
        }

        private fun serializeOutputs(result: ConfigValidationResult): String {
            return json.encodeToString(
                value = when (result) {
                    is ConfigValidationResult.Invalid -> result.output
                    is ConfigValidationResult.Valid -> listOf(validOutput())
                }
            )
        }

        private fun validOutput() = ConfigValidationOutput(
            valid = true,
            keywordLocation = "",
            instanceLocation = "",
            severity = ConfigValidationSeverity.INFO,
        )

        private fun defaultSchemaOutput(): ConfigSchemaOutputFormat = when (format) {
            OutputFormat.TEXT -> ConfigSchemaOutputFormat.LIST
            OutputFormat.JSON -> ConfigSchemaOutputFormat.HIERARCHICAL
        }

        private fun unreadableFile(file: File, exception: Exception): ConfigValidationResult.Invalid {
            val message = exception.message?.takeIf { it.isNotBlank() } ?: "The file could not be read."
            return ConfigValidationResult.Invalid(
                version = null,
                output = listOf(
                    element = ConfigValidationOutput(
                        valid = false,
                        keywordLocation = "",
                        instanceLocation = "",
                        error = "Could not read ${file.path}: $message",
                    )
                ),
            )
        }

        private companion object {
            val json = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = true }
        }
    }

    class OutputFormatConverter : ITypeConverter<OutputFormat> {
        override fun convert(value: String): OutputFormat = when (value.lowercase()) {
            "text" -> OutputFormat.TEXT
            "json" -> OutputFormat.JSON
            else -> throw IllegalArgumentException("expected text or json")
        }
    }

    class SchemaOutputFormatConverter : ITypeConverter<ConfigSchemaOutputFormat> {
        override fun convert(value: String): ConfigSchemaOutputFormat = when (value.lowercase()) {
            "list" -> ConfigSchemaOutputFormat.LIST
            "hierarchical", "hierarchy" -> ConfigSchemaOutputFormat.HIERARCHICAL
            else -> throw IllegalArgumentException("expected list or hierarchical")
        }
    }

    enum class OutputFormat {
        TEXT,
        JSON,
    }
}
