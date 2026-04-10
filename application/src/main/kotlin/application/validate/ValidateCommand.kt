package application.validate

import io.specmatic.core.CONTRACT_EXTENSIONS
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault
import io.specmatic.core.log.configureLogging
import io.specmatic.core.pattern.ContractException
import io.specmatic.loader.OpenApiSpecCompatibilityChecker
import io.specmatic.loader.RecursiveSpecificationAndExampleClassifier
import io.specmatic.loader.SpecCompatibilityChecker
import io.specmatic.loader.SpecificationWithExamples
import picocli.CommandLine
import picocli.CommandLine.Command
import java.io.File
import java.util.concurrent.Callable

@Command(name = "validate", hidden = true, mixinStandardHelpOptions = true, description = ["Lint & Validate specification and external examples"])
class ValidateCommand(
    private val validator: Validator<out Any?> = OpenApiValidator(),
    specCompatibilityChecker: SpecCompatibilityChecker = OpenApiSpecCompatibilityChecker(),
    specmaticConfig: io.specmatic.core.SpecmaticConfig = loadSpecmaticConfigIfAvailableElseDefault(),
    configBackedSpecificationLoader: ConfigBackedSpecificationLoader? = null,
    private val currentDirectoryProvider: () -> File = { File(".").canonicalFile }
) : Callable<Int> {
    @CommandLine.Option(names = ["--debug"], description = ["Enable debug logs"])
    var debug: Boolean? = null

    @CommandLine.Option(names = ["--dir"], description = ["Directory to validate"])
    var directory: File? = null

    @CommandLine.Option(names = ["--spec-file"], description = ["Specification to validate, along with respective examples"])
    var file: File? = null

    private val recursiveSpecificationAndExampleClassifier =
        RecursiveSpecificationAndExampleClassifier(specmaticConfig, specCompatibilityChecker, setOf(".specmatic"))
    private val effectiveConfigBackedSpecificationLoader =
        configBackedSpecificationLoader ?: ConfigBackedSpecificationLoader(specmaticConfig, recursiveSpecificationAndExampleClassifier)

    override fun call(): Int {
        configureLogging(LoggingConfiguration.Companion.LoggingFromOpts(debug = debug))
        validateArguments()

        val data = loadSpecificationData()

        if (data.isEmpty()) {
            println("No specifications found to validate.")
            return 0
        }

        val processor = ValidationProcessor(validator)
        val summary = processor.processValidation(data)
        return if (summary.isSuccess) 0 else 1
    }

    private fun loadSpecificationData(): List<SpecificationWithExamples> {
        if (file != null) {
            val specification = resolvePath(file ?: return emptyList())
            val normalizedSpecification = specification.canonicalFile
            val entryDirectory = normalizedSpecification.parentFile ?: currentDirectoryProvider()
            val loadedData = recursiveSpecificationAndExampleClassifier.load(normalizedSpecification, entryDirectory) ?: return emptyList()
            return listOf(loadedData)
        }

        if (directory != null) {
            val resolvedDirectory = resolvePath(directory ?: return emptyList())
            return recursiveSpecificationAndExampleClassifier.loadAll(resolvedDirectory)
        }

        val resolvedDirectory = currentDirectoryProvider()
        val discoveredSpecifications = recursiveSpecificationAndExampleClassifier.loadAll(resolvedDirectory)
        val configSpecifications = if (File(getConfigFilePath()).exists()) effectiveConfigBackedSpecificationLoader.load() else emptyList()

        return (discoveredSpecifications + configSpecifications)
            .distinctBy { specificationWithExamples -> specificationWithExamples.specFile.normalizedPath() }
    }

    private fun validateArguments() {
        if (file != null) {
            val specification = resolvePath(file ?: return)
            if (!specification.isFile)  throw ContractException("Specification is not a file ${specification.path}")
            if (!specification.exists()) throw ContractException("Specification ${specification.path} does not exist")
            if (!specification.canRead())  throw ContractException("Specification ${specification.path} cannot be read")
        }

        if (directory != null) {
            val directory = resolvePath(directory ?: return)
            if (!directory.isDirectory)  throw ContractException("${directory.path} is not a directory")
            if (!directory.exists()) throw ContractException("Directory ${directory.path} does not exist")
        }
    }

    private fun resolvePath(path: File): File {
        return if (path.isAbsolute) path.canonicalFile else currentDirectoryProvider().resolve(path.path).canonicalFile
    }

    private fun File.normalizedPath(): String {
        return runCatching { canonicalPath }.getOrElse { absolutePath }
    }
}
