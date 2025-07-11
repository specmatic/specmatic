package application

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticConfigVersion.Companion.convertToLatestVersionedConfig
import io.specmatic.core.config.SpecmaticConfigVersion.Companion.getLatestVersion
import io.specmatic.core.config.SpecmaticConfigVersion.Companion.isValidVersion
import io.specmatic.core.config.getVersion
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exitWithMessage
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.system.exitProcess

private const val SUCCESS_EXIT_CODE = 0

private const val SPECMATIC_CONFIGURATION = "Specmatic Configuration"

@Command(
    name = "config",
    mixinStandardHelpOptions = true,
    description = ["Manage and configure $SPECMATIC_CONFIGURATION."],
    subcommands = [
        ConfigCommand.Upgrade::class
    ]
)
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
            val objectMapper = ObjectMapper(YAMLFactory().apply {
                disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            }).apply {
                registerKotlinModule()
                setDefaultPropertyInclusion(
                    JsonInclude.Value.construct(
                        JsonInclude.Include.CUSTOM,
                        JsonInclude.Include.CUSTOM
                    ).withValueFilter(EmptyCollectionFilter::class.java)
                )
            }
            return objectMapper
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
}