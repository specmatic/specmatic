package application.validate

import io.specmatic.core.Result
import io.specmatic.core.log.logger
import java.io.File

private class ConsoleTheme(unicode: Boolean) {
    val heavySeparator = if (unicode) "━".repeat(100) else "=".repeat(150)
    val lightSeparator = if (unicode) "─".repeat(100) else "-".repeat(150)
    val ok = if (unicode) "✅" else "[OK]"
    val fail = if (unicode) "❌" else "[FAIL]"
    val info = if (unicode) "ℹ️" else "[INFO]"
    val bullet = if (unicode) "•" else "-"
}

class ValidationConsoleOutput {
    private val unicode = System.getenv("FORCE_UNICODE") != null
    private val theme = ConsoleTheme(unicode)

    fun printValidationStart(totalSpecs: Int, totalSpecExamples: Int, totalSharedExamples: Int) {
        printSeparator(NEW_LINE, theme.heavySeparator, NEW_LINE)
        logger.log("Validating Specification and examples")
        printSeparator(theme.heavySeparator, NEW_LINE)
        logger.log("Found $totalSpecs specification(s)")
        logger.log("Found $totalSpecExamples specification-specific example(s)")
        logger.log("Found $totalSharedExamples shared example(s)")
    }

    fun printSpecificationHeader(specFile: File, index: Int, total: Int) {
        printSeparator(NEW_LINE, theme.heavySeparator, NEW_LINE)
        logger.log("[$index/$total] validating specification ${specFile.name} at ${specFile.path}")
        printSeparator(theme.heavySeparator, NEW_LINE)
    }

    fun <Feature> printSpecificationResult(outcome: SpecificationValidationOutcome<Feature>) {
        when (val validationResult = outcome.validationResult) {
            is SpecValidationResult.FailedToLoad -> {
                printFailure("Specification validation failed due to load errors")
                printResult(validationResult.result)
            }
            is SpecValidationResult.ValidationResult -> {
                if (outcome.hasErrors) {
                    printFailure("Specification has issues")
                    printResult(validationResult.result)
                    return
                }

                if (validationResult.result.isPartialFailure()) {
                    printInfo("Specification is valid, but has some warnings")
                } else {
                    printSuccess("Specification is valid")
                }

                if (validationResult.result.isSuccess()) return
                printResult(validationResult.result)
            }
        }
    }

    fun printExamplesSkipped(specExampleCount: Int, sharedExampleCount: Int) {
        if (specExampleCount > 0 || sharedExampleCount > 0) {
            printFailure("Skipped ${specExampleCount + sharedExampleCount} examples due to specification validation failure")
            if (specExampleCount > 0) logger.withIndentation(2) {
                logger.log("${theme.bullet} $specExampleCount specification-specific example(s)")
            }
            if (sharedExampleCount > 0) logger.withIndentation(2) {
                logger.log("${theme.bullet} $sharedExampleCount shared example(s)")
            }
        }
    }

    fun printExamplesSection(sectionName: String, count: Int) {
        printSeparator(NEW_LINE, theme.heavySeparator, NEW_LINE)
        logger.log(sectionName.plus( if (count > 0) " (Total: $count)" else ""))
        printSeparator(theme.heavySeparator, NEW_LINE)
    }

    fun printExampleResult(outcome: ExampleValidationOutcome, index: Int, total: Int) {
        printSeparator(NEW_LINE, theme.lightSeparator, NEW_LINE)
        logger.log("[$index/$total] validating Example ${outcome.file.name} at ${outcome.file.path}")
        printSeparator(theme.lightSeparator, NEW_LINE)
        printExampleResult(outcome)
    }

    fun printInlineExampleResult(outcome: ExampleValidationOutcome, name: String) {
        printSeparator(NEW_LINE, theme.lightSeparator, NEW_LINE)
        logger.log("validating Inline-Example $name at ${outcome.file.path}")
        printSeparator(theme.lightSeparator, NEW_LINE)
        printExampleResult(outcome)
    }

    private fun printExampleResult(outcome: ExampleValidationOutcome) {
        when (val validationResult = outcome.validationResult) {
            is ExampleValidationResult.FailedToLoad -> {
                printFailure("Example validation failed due to load errors")
                printResult(validationResult.result)
            }
            is ExampleValidationResult.DoesNotBelong -> {
                if (outcome.isSpecificationSpecific) {
                    printFailure("Failed to find matching operation for the example")
                    printResult(validationResult.result)
                } else {
                    printInfo("Example doesn't belong to the specification, ignoring as it is shared")
                }
            }
            is ExampleValidationResult.ValidationResult -> {
                if (outcome.hasErrors) {
                    printFailure("Example has issues")
                    printResult(validationResult.result)
                } else {
                    if (validationResult.result.isPartialFailure()) printInfo("Example is valid, but has some warnings")
                    else printSuccess("Example is valid")
                    if (validationResult.result.isSuccess()) return
                    printResult(validationResult.result)
                }
            }
        }
    }

    fun printGlobalExampleResult(result: Result.Failure) {
        if (result.isPartialFailure()) {
            printInfo("Issues found when considering all examples")
            printResult(result)
        } else {
            printFailure("Issues found when considering all examples")
            printResult(result)
        }
    }

    fun printSpecificationSummary(result: SpecificationValidationResults<*>) {
        val totalExamples = result.specificationExampleResults.size + result.sharedExampleResults.size
        val specIcon = if (result.specificationOutcome.hasErrors) theme.fail else theme.ok
        val specStatus = if (result.specificationOutcome.hasErrors) "FAILED" else "PASSED"

        printSeparator(NEW_LINE, theme.lightSeparator, NEW_LINE)
        logger.log("$specIcon Specification ${result.specFile.name}: $specStatus")

        if (totalExamples > 0) {
            val failedExamples = result.specificationExampleResults.count { it.hasErrors } + result.sharedExampleResults.count { it.hasErrors }
            val passedExamples = totalExamples - failedExamples
            val examplesIcon = if (failedExamples > 0) theme.fail else theme.ok
            logger.log("$examplesIcon Examples: $passedExamples passed and $failedExamples failed out of $totalExamples total")
        } else {
            logger.log("${theme.info} No examples to validate")
        }

        printSeparator(theme.lightSeparator, NEW_LINE)
    }

    fun <Feature> printFinalSummary(summary: ValidationSummary<Feature>) {
        printSeparator(theme.heavySeparator, NEW_LINE)
        logger.log("FINAL SUMMARY")
        printSeparator(theme.heavySeparator, NEW_LINE)

        val passedSpecs = summary.totalSpecifications - summary.failedSpecifications
        logSummaryRow(label = "Specifications", passed = passedSpecs, failed = summary.failedSpecifications, total = summary.totalSpecifications)

        if (summary.totalExamples > 0) {
            val passedExamples = summary.totalExamples - summary.failedExamples
            logSummaryRow(label = "Examples", passed = passedExamples, failed = summary.failedExamples, total = summary.totalExamples)
        }

        logger.boundary()
        printSeparator(theme.heavySeparator)
        val resultIcon = if (summary.isSuccess) theme.ok else theme.fail
        val finalResult = if (summary.isSuccess) "PASSED" else "FAILED"
        logger.log("$resultIcon OVERALL RESULT: $finalResult")
        printSeparator(theme.heavySeparator)
        logger.boundary()
    }

    private fun printSeparator(vararg separator: String) { separator.forEach(::print) }
    private fun printInfo(message: String) = logger.log("${theme.info} $message")
    private fun printSuccess(message: String) = logger.log("${theme.ok} $message")
    private fun printFailure(message: String) = logger.log("${theme.fail} $message")
    private fun printResult(result: Result) { logger.boundary(); logger.log(result.reportString()) }
    private fun logSummaryRow(label: String, passed: Int, failed: Int, total: Int) {
        logger.log(
        label.padEnd(15) +
        "| Passed: ${passed.toString().padStart(5)} " +
        "| Failed: ${failed.toString().padStart(5)} " +
        "| Total: ${total.toString().padStart(5)}"
        )
    }

    companion object {
        private const val NEW_LINE = "\n"
    }
}
