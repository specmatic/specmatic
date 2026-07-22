package application.backwardCompatibility

import application.backwardCompatibility.BackwardCompatibilityCheckBaseCommand.ProcessedSpec
import application.backwardCompatibility.BackwardCompatibilityCheckBaseCommand.ChangedFiles
import application.backwardCompatibility.BackwardCompatibilityCheckBaseCommand.ExampleValidationResult
import io.specmatic.core.Results
import io.specmatic.core.log.logger

internal class BackwardCompatibilityCheckLogger {
    private val newLine = System.lineSeparator()

    fun logFilesToBeCheckedForBackwardCompatibility(
        changedFiles: ChangedFiles,
        filesReferringToChangedFiles: Set<String>,
        specificationsOfChangedExternalisedExamples: Set<String>,
        untrackedFiles: Set<String>
    ) {
        changedFiles.externalisedExamples.printSummary("Externalised examples that have changed")

        logger.log("Checking backward compatibility of the following specs:$newLine")
        changedFiles.specs.printSummary("Specs that have changed")
        filesReferringToChangedFiles.printSummary("Specs referring to the changed specs")
        specificationsOfChangedExternalisedExamples.printSummary("Specs whose externalised examples were changed")
        untrackedFiles.printSummary("Specs that will be skipped (untracked specs, or schema files that are not referred to in other specs)")
        logger.log("-".repeat(20))
        logger.log(newLine)
    }

    fun logCheckStart(index: Int, processedSpec: ProcessedSpec) {
        logger.log("${index.inc()}. Running the check for ${processedSpec.specFilePath}:")
    }

    fun logNewFile(processedSpec: ProcessedSpec) {
        logger.log("${ONE_INDENT}${processedSpec.specFilePath} is a new file.$newLine")
    }

    private fun Set<String>.printSummary(message: String) {
        if (isNotEmpty()) {
            logger.log("${ONE_INDENT}- $message: ")
            forEachIndexed { index, file ->
                logger.log(file.prependIndent("$TWO_INDENTS${index.inc()}. "))
            }
            logger.boundary()
        }
    }

    fun logIncompatibleSpec(processedSpec: ProcessedSpec, verdictMessage: String) {
        logIncompatibilityReport(processedSpec.backwardCompatibilityResult)
        logWipScenarios(processedSpec.backwardCompatibilityResult)
        logVerdictFor(processedSpec.specFilePath, verdictMessage.prependIndent(ONE_INDENT))
    }

    fun logBackwardCompatibleSpec(
        processedSpec: ProcessedSpec,
        exampleValidationStatus: BCCExampleValidationStatus,
        verdictMessage: String
    ) {
        logExampleValidationSummary(processedSpec, exampleValidationStatus)
        logChangedExternalisedExampleValidation(processedSpec)
        logVerdictFor(
            processedSpec.specFilePath,
            verdictMessage.prependIndent(ONE_INDENT),
            startWithNewLine = exampleValidationStatus.hasErrors || processedSpec.exampleValidationResults.isNotEmpty()
        )
    }

    fun logChangedExternalisedExampleValidation(processedSpec: ProcessedSpec) {
        val results = processedSpec.exampleValidationResults
        if (results.isEmpty()) return

        logger.log("_".repeat(40).prependIndent(ONE_INDENT))
        logger.log("Changed Externalised Examples Validation:$newLine".prependIndent(ONE_INDENT))
        results.filter { !it.result.isSuccess() }
            .sortedBy { it.examplePath }
            .forEachIndexed(::logExampleValidationFailure)
        logger.newLine()
        logger.log("Example validation verdict:".prependIndent(ONE_INDENT))
        logger.log(Results(results.map { it.result }).summary().prependIndent(TWO_INDENTS))
    }

    private fun logExampleValidationFailure(index: Int, validationResult: ExampleValidationResult) {
        val prefix = if (validationResult.result.isPartialFailure()) "Warning" else "Error"
        logger.log("${index.inc()}. $prefix(s) found in ${validationResult.examplePath}:".prependIndent(TWO_INDENTS))
        logger.newLine()
        logger.log(validationResult.result.reportString().prependIndent(TWO_INDENTS))
    }

    private fun logIncompatibilityReport(backwardCompatibilityResult: Results) {
        logger.log("_".repeat(40).prependIndent(ONE_INDENT))
        logger.log("The Incompatibility Report:$newLine".prependIndent(ONE_INDENT))
        logger.log(
            backwardCompatibilityResult.withoutIgnorableFailures().withoutViolationReport().distinctReport()
                .prependIndent(TWO_INDENTS)
        )
    }

    fun logWipScenarios(backwardCompatibilityResult: Results) {
        if (!backwardCompatibilityResult.hasIgnorableFailures()) return

        logger.log("_".repeat(40).prependIndent(ONE_INDENT))
        logger.log("WIP scenarios (incompatible, not breaking the check):$newLine".prependIndent(ONE_INDENT))
        logger.log(
            backwardCompatibilityResult.ignorableFailures().withoutViolationReport().distinctReport()
                .prependIndent(TWO_INDENTS)
        )
    }

    private fun logVerdictFor(specFilePath: String, message: String, startWithNewLine: Boolean = true) {
        if (startWithNewLine) logger.log(newLine)
        logger.log("-".repeat(20).prependIndent(ONE_INDENT))
        logger.log("Verdict for spec $specFilePath:".prependIndent(ONE_INDENT))
        logger.log("$ONE_INDENT$message")
        logger.log("-".repeat(20).prependIndent(ONE_INDENT))
        logger.log(newLine)
    }

    private fun logExampleValidationSummary(
        processedSpec: ProcessedSpec,
        exampleValidationStatus: BCCExampleValidationStatus
    ) {
        if (exampleValidationStatus.hasErrors) {
            logger.log("_".repeat(40).prependIndent(ONE_INDENT))
            logger.log("The Examples Validity Summary:$newLine".prependIndent(ONE_INDENT))
        }
        if (exampleValidationStatus.areExamplesInvalid) {
            logger.log("Examples in ${processedSpec.specFilePath} are not valid.$newLine".prependIndent(TWO_INDENTS))
        }
        if (exampleValidationStatus.hasUnloadableExamples) {
            logger.log("Some examples for ${processedSpec.specFilePath} could not be loaded.$newLine".prependIndent(TWO_INDENTS))
        }
        if (exampleValidationStatus.hasInvalidChangedExternalisedExamples) {
            logger.log("Some changed externalised examples for ${processedSpec.specFilePath} are not valid.$newLine".prependIndent(TWO_INDENTS))
        }
    }

    private companion object {
        const val ONE_INDENT = "  "
        const val TWO_INDENTS = "${ONE_INDENT}${ONE_INDENT}"
    }
}
