package application.validate

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault
import io.specmatic.core.log.configureLogging
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.SystemExit
import io.specmatic.linter.api.ConfigOptions
import io.specmatic.linter.api.SpecmaticLinter
import io.specmatic.linter.config.ConfigLoader
import io.specmatic.linter.config.ResolvedLintConfig
import io.specmatic.linter.model.OutputFormat
import io.specmatic.linter.model.ReportConfig
import io.specmatic.loader.OpenApiSpecCompatibilityChecker
import io.specmatic.loader.RecursiveSpecificationAndExampleClassifier
import io.specmatic.loader.SpecCompatibilityChecker
import io.specmatic.loader.SpecificationWithExamples
import picocli.CommandLine
import picocli.CommandLine.Command
import java.io.File
import java.util.concurrent.Callable

class ValidateCommandOptions {
    @CommandLine.Option(names = ["--debug"], description = ["Enable debug logs"])
    var debug: Boolean? = null

    @CommandLine.Option(names = ["--dir", "--directory"], description = ["Directory to validate"])
    var directory: File? = null

    @CommandLine.Option(names = ["--spec-file"], description = ["Specification to validate, along with respective examples"])
    var file: File? = null

    @CommandLine.Option(
        names = ["--lint-config"],
        description = [
            "Path to the linter config file to use.",
            "If not provided, the command looks for specmatic-linter.yaml or specmatic-linter.yml in the current directory."
        ],
    )
    var configPath: String? = null

    @CommandLine.Option(
        split = ",",
        names = ["--extends"],
        description = [
            "Override the preset list from the config file for this run.",
            "Use this when you want to temporarily run with presets such as minimal, recommended, or recommended-strict.",
        ],
    )
    var customExtends: List<String>? = null

    @CommandLine.Option(
        split = ",",
        names = ["--skip-rule"],
        description = [
            "Temporarily disable one or more rules for this run.",
            "This does not change your config file. Example: --skip-rule operation-summary,path-parameters-defined",
        ],
    )
    var skipRules: List<String>? = null

    @CommandLine.Option(
        names = ["--generate-ignore-file"],
        description = [
            "Create or update .specmatic-lint-ignore.yaml based on the problems found in this run.",
            "Use this when you want to keep current known issues out of future lint results while you fix them gradually.",
            "Instead of printing the lint output, this mode records the failing rule ids and JSON pointers for each problem location.",
        ],
    )
    var generateIgnoreFile: Boolean = false

    @CommandLine.Option(
        defaultValue = "warn",
        names = ["--lint-config-severity"],
        description = [
            "Validate the config file itself before linting APIs.",
            "Use warn to print config problems without failing the command, error to fail the command, or off to skip config validation.",
        ],
    )
    var lintConfigSeverity: String = "warn"

    @CommandLine.Option(
        names = ["--max-problems"],
        description = [
            "Maximum number of non-ignored lint problems to print, (default: 100)",
            "This only affects the displayed output. Linting still evaluates the full document.",
        ],
    )
    var maxProblems: Int? = null

    @CommandLine.Option(
        names = ["--format"],
        description = [
            "Output format for lint results, (default: codeframe)",
            "Common choices are codeframe for local use, json for tooling, summary for quick totals, and github-actions for CI annotations.",
        ],
    )
    var format: String? = null
}

@Command(name = "validate", hidden = true, mixinStandardHelpOptions = true, description = ["Lint & Validate specification and external examples"])
class ValidateCommand(
    private val validator: Validator<out Any?> = OpenApiValidator(),
    specCompatibilityChecker: SpecCompatibilityChecker = OpenApiSpecCompatibilityChecker(),
    specmaticConfig: SpecmaticConfig = loadSpecmaticConfigIfAvailableElseDefault(),
    configBackedSpecificationLoader: ConfigBackedSpecificationLoader? = null,
    private val currentDirectoryProvider: () -> File = { File(".").canonicalFile },
    @field:CommandLine.Mixin val validateOptions: ValidateCommandOptions = ValidateCommandOptions()
) : Callable<Int> {
    private val recursiveSpecificationAndExampleClassifier =
        RecursiveSpecificationAndExampleClassifier(specmaticConfig, specCompatibilityChecker, setOf(".specmatic"))
    private val effectiveConfigBackedSpecificationLoader =
        configBackedSpecificationLoader ?: ConfigBackedSpecificationLoader(specmaticConfig, recursiveSpecificationAndExampleClassifier)

    override fun call(): Int {
        configureLogging(LoggingConfiguration.Companion.LoggingFromOpts(debug = validateOptions.debug))
        validateArguments()

        val linterConfig = createLintConfig(currentDirectoryProvider())
        val data = loadSpecificationData()

        if (data.isEmpty()) {
            println("No ${validator.name} specifications found to validate.")
            return 0
        }

        val ignoreFileEntries = IgnoreFileGenerator(generateIgnoreFile = validateOptions.generateIgnoreFile)
        val processor = ValidationProcessor(validator, linterConfig, ignoreFileEntries)
        val summary = processor.processValidation(data)
        return if (summary.isSuccess) 0 else 1
    }

    private fun loadSpecificationData(): List<SpecificationWithExamples> {
        if (validateOptions.file != null) {
            val specification = resolvePath(validateOptions.file ?: return emptyList())
            val normalizedSpecification = specification.canonicalFile
            val entryDirectory = normalizedSpecification.parentFile ?: currentDirectoryProvider()
            val loadedData = recursiveSpecificationAndExampleClassifier.load(normalizedSpecification, entryDirectory) ?: return emptyList()
            return listOf(loadedData)
        }

        if (validateOptions.directory != null) {
            val resolvedDirectory = resolvePath(validateOptions.directory ?: return emptyList())
            return recursiveSpecificationAndExampleClassifier.loadAll(resolvedDirectory)
        }

        val resolvedDirectory = currentDirectoryProvider()
        val discoveredSpecifications = recursiveSpecificationAndExampleClassifier.loadAll(resolvedDirectory)
        val configSpecifications = if (File(getConfigFilePath()).exists()) effectiveConfigBackedSpecificationLoader.load() else emptyList()

        return (discoveredSpecifications + configSpecifications)
            .distinctBy { specificationWithExamples -> specificationWithExamples.specFile.normalizedPath() }
    }

    private fun validateArguments() {
        if (validateOptions.file != null) {
            val specification = resolvePath(validateOptions.file ?: return)
            if (!specification.isFile)  throw ContractException("Specification is not a file ${specification.path}")
            if (!specification.exists()) throw ContractException("Specification ${specification.path} does not exist")
            if (!specification.canRead())  throw ContractException("Specification ${specification.path} cannot be read")
        }

        if (validateOptions.directory != null) {
            val directory = resolvePath(validateOptions.directory ?: return)
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

    private fun createLintConfig(workingDirectory: File): ResolvedLintConfig {
        lintConfigSeverity()
        val opts = ConfigOptions(
            skipRules = validateOptions.skipRules,
            workingDirectory = workingDirectory.toPath(),
            customExtends = validateOptions.customExtends,
            configPath = validateOptions.configPath?.let(::File),
            report = ReportConfig(format = validateOptions.format?.let(OutputFormat::fromText), maxProblems = validateOptions.maxProblems),
        )

        return SpecmaticLinter.loadConfig(opts)
    }

    private fun lintConfigSeverity() {
        val configPath = validateOptions.configPath ?: return
        if (validateOptions.lintConfigSeverity == "off") return

        val configLintResult = ConfigLoader.lintConfig(configPath)
        if (configLintResult.problems.isEmpty()) return

        System.err.println(configLintResult.problems.joinToString("\n"))
        if (validateOptions.lintConfigSeverity == "error") {
            SystemExit.exitWith(2, "Lint config has errors")
        }
    }
}
