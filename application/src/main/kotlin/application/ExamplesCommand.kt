package application

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.examples.module.*
import io.specmatic.core.examples.server.ScenarioFilter
import io.specmatic.core.log.CompositePrinter
import io.specmatic.core.log.ConsolePrinter
import io.specmatic.core.log.NonVerbose
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.isOpenAPI
import picocli.CommandLine.*
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger

private const val SUCCESS_EXIT_CODE = 0
private const val FAILURE_EXIT_CODE = 1

@Command(
    name = "examples",
    mixinStandardHelpOptions = true,
    description = ["Validate inline and externalised examples"],
    subcommands = [ExamplesCommand.Validate::class]
)
class ExamplesCommand : Callable<Int> {

    override fun call(): Int {
        logger.log("Please use one of the subcommands. Use --help to view the list of available subcommands.")
        return FAILURE_EXIT_CODE
    }

    @Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = ["Validate the examples"]
    )
    class Validate : Callable<Int> {
        @Option(
            names= ["--filter"],
            description = [
                """Filter tests matching the specified filtering criteria

You can filter tests based on the following keys:
- `METHOD`: HTTP methods (e.g., GET, POST)
- `PATH`: Request paths (e.g., /users, /product)
- `STATUS`: HTTP response status codes (e.g., 200, 400)

You can find all available filters and their usage at:
https://docs.specmatic.io/documentation/contract_tests.html#supported-filters--operators"""
            ],
            required = false
        )
        var filter: String = ""

        @Option(names = ["--contract-file", "--spec-file"], description = ["Contract file path"], required = false)
        var contractFile: File? = null

        @Option(names = ["--example-file"], description = ["Example file path"], required = false)
        val exampleFile: File? = null

        @Option(names = ["--examples-dir"], description = ["External examples directory path for a single API specification (If you are not following the default naming convention for external examples directory)"], required = false)
        val examplesDir: File? = null

        @Option(names = ["--specs-dir"], description = ["Directory with the API specification files"], required = false)
        val specsDir: File? = null

        @Option(
            names = ["--examples-base-dir"],
            description = ["Base directory which contains multiple external examples directories each named as per the Specmatic naming convention to associate them with the corresponding API specification"],
            required = false
        )
        val examplesBaseDir: File? = null

        @Option(names = ["--debug"], description = ["Debug logs"])
        var verbose = false

        @Option(
            names = ["--filter-name"],
            description = ["Validate examples of only APIs with this value in their name"],
            defaultValue = "\${env:SPECMATIC_FILTER_NAME}",
            hidden = true
        )
        var filterName: String = ""

        @Option(
            names = ["--filter-not-name"],
            description = ["Validate examples of only APIs which do not have this value in their name"],
            defaultValue = "\${env:SPECMATIC_FILTER_NOT_NAME}",
            hidden = true
        )
        var filterNotName: String = ""

        @Option(
            names = ["--examples-to-validate"],
            description = ["Whether to validate inline, external, or both examples. Options: INLINE, EXTERNAL, BOTH"],
            converter = [ExamplesToValidateConverter::class],
            defaultValue = "BOTH"
        )
        var examplesToValidate: ExamplesToValidate = ExamplesToValidate.BOTH

        enum class ExamplesToValidate { INLINE, EXTERNAL, BOTH }
        class ExamplesToValidateConverter : ITypeConverter<ExamplesToValidate> {
            override fun convert(value: String): ExamplesToValidate {
                return ExamplesToValidate.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: throw IllegalArgumentException("Invalid value: $value. Expected one of: ${ExamplesToValidate.entries.joinToString(", ")}")
            }
        }

        private val exampleValidationModule = ExampleValidationModule()

        override fun call(): Int {
            configureLogger(this.verbose)

            if(contractFile != null) {
                OpenApiSpecification.checkSpecValidity(contractFile!!.canonicalPath)

                if(exampleFile != null) return validateExampleFile(contractFile!!, exampleFile)
                if(examplesDir != null) {
                    val (exitCode, validationResults) = validateExamplesDir(contractFile!!, examplesDir)

                    printValidationResult(validationResults.exampleValidationResults, "Example directory")
                    return if (exitCode == FAILURE_EXIT_CODE) FAILURE_EXIT_CODE else validationResults.exitCode
                }
                return validateImplicitExamplesFrom(contractFile!!)
            }

            if (specsDir != null && examplesBaseDir != null) {
                logger.log("- Validating associated examples in the directory: ${examplesBaseDir.path}")
                logger.newLine()
                val externalExampleValidationResults = validateAllExamplesAssociatedToEachSpecIn(specsDir, examplesBaseDir)

                logger.newLine()
                logger.log("- Validating associated examples in the directory: ${specsDir.path}")
                logger.newLine()
                val implicitExampleValidationResults = validateAllExamplesAssociatedToEachSpecIn(specsDir, specsDir)

                logger.newLine()
                val summaryTitle = "- Validation summary across all example directories:"
                logger.log("_".repeat(summaryTitle.length))
                logger.log("- Validation summary across all example directories:")
                val allResults = implicitExampleValidationResults + externalExampleValidationResults
                printValidationResult(allResults.ofAllExamples(), "")

                return allResults.exitCode()
            }

            if (specsDir != null) {
                return validateAllExamplesAssociatedToEachSpecIn(specsDir, specsDir).exitCode()
            }

            logger.log("Invalid combination of CLI options. Please refer to the help section using --help command to understand how to use this command")
            return FAILURE_EXIT_CODE
        }

        private fun validateExampleFile(contractFile: File, exampleFile: File): Int {
            if (!contractFile.exists()) {
                logger.log("Could not find file ${contractFile.path}")
                return FAILURE_EXIT_CODE
            }

            try {
                val validationResult = exampleValidationModule.validateExample(contractFile, exampleFile)

                validationResult.errorMessage?.let {
                    logger.log(it)
                }

                return validationResult.exitCode
            } catch (e: Throwable) {
                logger.log(e, "The provided example ${exampleFile.name} is invalid.")
                return FAILURE_EXIT_CODE
            }
        }

        private fun validateExamplesDir(contractFile: File, examplesDir: File): Pair<Int, ValidationResults> =
            validateExamplesDir(parseContractFileWithNoMissingConfigWarning(contractFile), examplesDir)

        private fun validateExamplesDir(feature: Feature, examplesDir: File): Pair<Int, ValidationResults> {
            val (externalExampleDir, externalExamples) = ExampleModule().loadExternalExamples(examplesDir = examplesDir)
            if (!externalExampleDir.exists()) {
                logger.log("$externalExampleDir does not exist, did not find any files to validate")
                return FAILURE_EXIT_CODE to ValidationResults.forNoExamples()
            }
            if (externalExamples.isEmpty()) {
                logger.log("No example files found in $externalExampleDir")
                return SUCCESS_EXIT_CODE to ValidationResults.forNoExamples()
            }
            return SUCCESS_EXIT_CODE to validateExternalExamples(feature, externalExamples)
        }

        private fun validateAllExamplesAssociatedToEachSpecIn(specsDir: File, examplesBaseDir: File): List<ValidationResults> {
            val ordinal = AtomicInteger(1)
            val allSpecFiles = specsDir.walk().filter(File::isFile).filter { isOpenAPI(it.canonicalPath) }.onEach {
                OpenApiSpecification.checkSpecValidity(it.canonicalPath)
            }

            val validationResults = allSpecFiles.map { specFile ->
                val relativeSpecPath = specsDir.toPath().relativize(specFile.toPath()).toString()
                logger.log("${ordinal.getAndIncrement()}. Validating examples associated to '$relativeSpecPath'...")
                logger.boundary()

                val feature = parseContractFileWithNoMissingConfigWarning(specFile)
                val inlineExampleValidationResults = validateInlineExamples(feature)
                printValidationResult(inlineExampleValidationResults, "Inline example")
                logger.boundary()

                val associatedExamplesDir = examplesBaseDir.resolve(relativeSpecPath.substringBeforeLast(".").plus("_examples"))
                val externalExampleValidationResult = when {
                    associatedExamplesDir.exists() -> validateExamplesDir(feature, associatedExamplesDir).second
                    else -> ValidationResults.forNoExamples()
                }
                printValidationResult(externalExampleValidationResult.exampleValidationResults, "Example file")
                logger.boundary()

                externalExampleValidationResult.plus(inlineExampleValidationResults)
            }.toList()

            logger.log("Summary:")
            printValidationResult(validationResults.ofAllExamples(), "Overall")
            return validationResults
        }

        private fun validateImplicitExamplesFrom(contractFile: File): Int {
            val feature = parseContractFileWithNoMissingConfigWarning(contractFile)

            val (validateInline, validateExternal) = getValidateInlineAndValidateExternalFlags()

            val inlineExampleValidationResults =
                if (!validateInline)
                    emptyMap()
                else
                    validateInlineExamples(feature)

            val externalExampleValidationResults =
                if (!validateExternal)
                    ValidationResults.forNoExamples()
                else {
                    val (exitCode, validationResults)
                            = validateExamplesDir(feature, ExampleModule().defaultExternalExampleDirFrom(contractFile))
                    if(exitCode == 1) return exitCode
                    validationResults
                }

            printValidationResult(inlineExampleValidationResults, "Inline example")
            printValidationResult(externalExampleValidationResults.exampleValidationResults, "Example file")

            if (inlineExampleValidationResults.containsOnlyCompleteFailures())
                return FAILURE_EXIT_CODE

            return externalExampleValidationResults.exitCode
        }

        private fun validateInlineExamples(feature: Feature): Map<String, Result> {
            return exampleValidationModule.validateInlineExamples(
                feature,
                examples = feature.stubsFromExamples.mapValues { (_, stub) ->
                    stub.map { (request, response) ->
                        ScenarioStub(request, response)
                    }
                },
                scenarioFilter = ScenarioFilter(filterName, filterNotName, filter)
            )
        }

        private fun validateExternalExamples(feature: Feature, externalExamples: List<File>): ValidationResults {
            return exampleValidationModule.validateExamples(
                feature,
                examples = externalExamples,
                scenarioFilter = ScenarioFilter(filterName, filterNotName, filter)
            )
        }

        private fun getValidateInlineAndValidateExternalFlags(): Pair<Boolean, Boolean> {
            return when(examplesToValidate) {
                ExamplesToValidate.BOTH -> true to true
                ExamplesToValidate.INLINE -> true to false
                ExamplesToValidate.EXTERNAL -> false to true
            }
        }

        private fun printValidationResult(validationResults: Map<String, Result>, tag: String) {
            val titleTag = tag.split(" ").joinToString(" ") { if (it.isBlank()) it else it.capitalizeFirstChar() }

            if (validationResults.containsFailuresOrPartialFailures()) {
                logger.boundary()
                logger.log("=============== $titleTag Validation Results ===============")
                validationResults.forEach { (exampleFileName, result) ->
                    if (!result.isSuccess()) {
                        val errorPrefix = if (result.isPartialFailure()) "Warning" else "Error"
                        logger.boundary()
                        logger.log("$errorPrefix(s) found in the $tag - '$exampleFileName':")
                        logger.log(result.reportString())
                    }
                }
            }

            val summaryTitle = "=============== $titleTag Validation Summary ==============="
            logger.boundary()
            logger.log(summaryTitle)
            logger.log(Results(validationResults.values.toList()).summary())
            logger.log("=".repeat(summaryTitle.length))
        }

        private fun Map<String, Result>.containsOnlyCompleteFailures(): Boolean {
            return this.any { it.value is Result.Failure && !it.value.isPartialFailure() }
        }

        private fun Map<String, Result>.containsFailuresOrPartialFailures(): Boolean {
            return this.any { it.value is Result.Failure }
        }

    }

}

private fun configureLogger(verbose: Boolean) {
    val logPrinters = listOf(ConsolePrinter)

    logger = if (verbose)
        Verbose(CompositePrinter(logPrinters))
    else
        NonVerbose(CompositePrinter(logPrinters))
}
