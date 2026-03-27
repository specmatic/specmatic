package application.validate

import io.specmatic.core.CONTRACT_EXTENSIONS
import io.specmatic.core.OPENAPI_FILE_EXTENSIONS
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault
import io.specmatic.core.log.configureLogging
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.ContractsSelectorPredicate
import io.specmatic.core.utilities.GitRepo
import io.specmatic.loader.OpenApiSpecCompatibilityChecker
import io.specmatic.loader.RecursiveSpecificationAndExampleClassifier
import io.specmatic.loader.SpecCompatibilityChecker
import io.specmatic.loader.SpecificationWithExamples
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine
import picocli.CommandLine.Command
import java.io.File
import java.util.concurrent.Callable

@Command(name = "validate", hidden = true, mixinStandardHelpOptions = true, description = ["Lint & Validate specification and external examples"])
class ValidateCommand(
    private val validator: Validator<out Any?> = OpenApiValidator(),
    specCompatibilityChecker: SpecCompatibilityChecker = OpenApiSpecCompatibilityChecker(),
    private val specmaticConfig: io.specmatic.core.SpecmaticConfig = loadSpecmaticConfigIfAvailableElseDefault(),
    private val configBackedSpecificationLoader: ConfigBackedSpecificationLoader? = null,
    private val currentDirectoryProvider: () -> File = { File(".").canonicalFile }
) : Callable<Int> {
    @CommandLine.Option(names = ["--debug"], description = ["Enable debug logs"])
    var debug: Boolean? = null

    @CommandLine.Option(names = ["--dir"], description = ["Directory to validate"])
    var directory: File? = null

    @CommandLine.Option(names = ["--spec-file"], description = ["Specification to validate, along with respective examples"])
    var file: File? = null

    private val recursiveSpecificationAndExampleClassifier = RecursiveSpecificationAndExampleClassifier(specmaticConfig, specCompatibilityChecker)
    private val effectiveConfigBackedSpecificationLoader =
        configBackedSpecificationLoader ?: ConfigBackedSpecificationLoader(specmaticConfig, recursiveSpecificationAndExampleClassifier)

    override fun call(): Int {
        configureLogging(LoggingConfiguration.Companion.LoggingFromOpts(debug = debug))
        validateArguments()

        val invalidSpecifications = findInvalidSpecificationCandidates()
        val data = loadSpecificationData()

        if (invalidSpecifications.isNotEmpty()) {
            println("Failed to parse ${invalidSpecifications.size} specification(s):")
            invalidSpecifications.forEach { println(it.path) }
        }

        if (data.isEmpty()) {
            println("No specifications found to validate.")
            return if (invalidSpecifications.isNotEmpty()) 1 else 0
        }

        val processor = ValidationProcessor(validator)
        val summary = processor.processValidation(data)
        return if (summary.isSuccess && invalidSpecifications.isEmpty()) 0 else 1
    }

    private fun loadSpecificationData(): List<SpecificationWithExamples> {
        if (file != null) {
            val specification = file ?: return emptyList()
            val loadedData = recursiveSpecificationAndExampleClassifier.load(specification) ?: return emptyList()
            return listOf(loadedData)
        }

        if (directory != null) {
            val resolvedDirectory = directory ?: return emptyList()
            return recursiveSpecificationAndExampleClassifier.loadAll(resolvedDirectory)
        }

        val resolvedDirectory = currentDirectoryProvider()
        val discoveredSpecifications = recursiveSpecificationAndExampleClassifier.loadAll(resolvedDirectory)
        val configSpecifications = if (File(getConfigFilePath()).exists()) effectiveConfigBackedSpecificationLoader.load() else emptyList()

        return (discoveredSpecifications + configSpecifications)
            .distinctBy { specificationWithExamples -> specificationWithExamples.specFile.normalizedPath() }
    }

    private fun findInvalidSpecificationCandidates(): List<File> {
        return when {
            file != null -> listOfNotNull(file).filter(::isMalformedOpenApiSpecification)
            directory != null -> findInvalidSpecificationsInDirectory(directory ?: return emptyList())
            else -> {
                val resolvedDirectory = currentDirectoryProvider()
                (findInvalidSpecificationsInDirectory(resolvedDirectory) + findInvalidConfigSpecifications())
                    .distinctBy { it.normalizedPath() }
            }
        }
    }

    private fun findInvalidSpecificationsInDirectory(directory: File): List<File> {
        return findContractCandidates(directory)
            .filter(::isMalformedOpenApiSpecification)
            .distinctBy { it.normalizedPath() }
    }

    private fun findInvalidConfigSpecifications(): List<File> {
        val configFile = File(getConfigFilePath()).canonicalFile
        if (!configFile.exists()) return emptyList()

        val classificationWorkingDirectory = configFile.parentFile?.canonicalFile ?: currentDirectoryProvider()
        val contractLoadingBaseDir = classificationWorkingDirectory.resolve(".specmatic").canonicalFile

        return specmaticConfig.loadSources().flatMap { source ->
            val contractLoadingWorkingDirectory =
                if (source is GitRepo) contractLoadingBaseDir.canonicalPath else classificationWorkingDirectory.canonicalPath
            source.loadContracts(
                ContractsSelectorPredicate { contractSource -> contractSource.testContracts + contractSource.stubContracts },
                contractLoadingWorkingDirectory,
                configFile.canonicalPath
            )
        }.map { contractPathData ->
            File(contractPathData.path).canonicalFile
        }.filter { specificationFile ->
            specificationFile.exists() && isMalformedOpenApiSpecification(specificationFile)
        }.distinctBy { it.normalizedPath() }
    }

    private fun findContractCandidates(directory: File): List<File> {
        if (!directory.isDirectory) return emptyList()

        return directory.listFiles()?.flatMap { file ->
            when {
                file.isDirectory -> findContractCandidates(file)
                file.isFile && file.extension.lowercase() in CONTRACT_EXTENSIONS -> listOf(file.canonicalFile)
                else -> emptyList()
            }
        }.orEmpty()
    }

    private fun isMalformedOpenApiSpecification(specificationFile: File): Boolean {
        if (!specificationFile.isFile) return false
        if (specificationFile.extension.lowercase() !in OPENAPI_FILE_EXTENSIONS) return false

        return try {
            specificationFile.reader().use { reader ->
                Yaml().load<Any?>(reader)
            }
            false
        } catch (_: Throwable) {
            true
        }
    }

    private fun validateArguments() {
        if (file != null) {
            val specification = file ?: return
            if (!specification.isFile)  throw ContractException("Specification is not a file ${specification.path}")
            if (!specification.exists()) throw ContractException("Specification ${specification.path} does not exist")
            if (!specification.canRead())  throw ContractException("Specification ${specification.path} cannot be read")
        }

        if (directory != null) {
            val directory = directory ?: return
            if (!directory.isDirectory)  throw ContractException("${directory.path} is not a directory")
            if (!directory.exists()) throw ContractException("Directory ${directory.path} does not exist")
        }
    }

    private fun File.normalizedPath(): String {
        return runCatching { canonicalPath }.getOrElse { absolutePath }
    }
}
