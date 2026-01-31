package application.validate

import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault
import io.specmatic.core.log.configureLogging
import io.specmatic.core.pattern.ContractException
import io.specmatic.loader.RecursiveSpecificationAndExampleLoader
import io.specmatic.loader.SpecificationWithExamples
import picocli.CommandLine
import picocli.CommandLine.Command
import java.io.File
import java.util.concurrent.Callable

@Command(name = "validate", mixinStandardHelpOptions = true, description = ["Lint & Validate specification and external examples"])
class ValidateCommand(private val validator: Validator<*> = OpenApiValidator()): Callable<Int> {
    @CommandLine.Option(names = ["--debug"], description = ["Enable debug logs"])
    var debug: Boolean? = null

    @CommandLine.Option(names = ["--dir"], description = ["Directory to validate"])
    var directory: File? = null

    @CommandLine.Option(names = ["--file"], description = ["Specification to validate"])
    var file: File? = null

    private val specmaticConfig = loadSpecmaticConfigIfAvailableElseDefault()
    private val recursiveSpecificationAndExampleLoader = RecursiveSpecificationAndExampleLoader(specmaticConfig, validator)

    override fun call(): Int {
        configureLogging(LoggingConfiguration.Companion.LoggingFromOpts(debug = debug))
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
        validateArguments()

        if (file != null) {
            val specification = file ?: return emptyList()
            val loadedData = recursiveSpecificationAndExampleLoader.load(specification) ?: return emptyList()
            return listOf(loadedData)
        }

        val resolvedDirectory = directory ?: File(".").canonicalFile
        return recursiveSpecificationAndExampleLoader.loadAll(resolvedDirectory)
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
}
