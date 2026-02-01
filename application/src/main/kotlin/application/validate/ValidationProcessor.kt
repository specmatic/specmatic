package application.validate

import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault
import io.specmatic.loader.SpecificationWithExamples
import io.specmatic.test.asserts.toFailure
import java.io.File

class ValidationProcessor<Feature>(
    private val validator: Validator<Feature>,
    private val specmaticConfig: SpecmaticConfig = loadSpecmaticConfigIfAvailableElseDefault(),
    private val consoleOutput: ValidationConsoleOutput = ValidationConsoleOutput(),
) {
    fun processValidation(allSpecificationData: List<SpecificationWithExamples>): ValidationSummary<Feature> {
        val totalSpecExamples = allSpecificationData.sumOf { it.examples.specExamples.size }
        val totalSharedExamples = allSpecificationData.sumOf { it.examples.sharedExamples.size }

        consoleOutput.printValidationStart(totalSpecs = allSpecificationData.size, totalSpecExamples = totalSpecExamples, totalSharedExamples = totalSharedExamples)
        val results = allSpecificationData.mapIndexed { index, specData ->
            validateSpecificationWithExamples(specData, index + 1, allSpecificationData.size)
        }

        val summary = ValidationSummary(results)
        consoleOutput.printFinalSummary(summary)
        return summary
    }

    private fun validateSpecificationWithExamples(specData: SpecificationWithExamples, index: Int, total: Int): SpecificationValidationResults<Feature> {
        consoleOutput.printSpecificationHeader(specData.specFile, index, total)
        val specOutcome = validateSpecification(specData.specFile)
        consoleOutput.printSpecificationResult(specOutcome)

        if (specOutcome.hasErrors) {
            consoleOutput.printExamplesSkipped(specExampleCount = specData.examples.specExamples.size, sharedExampleCount = specData.examples.sharedExamples.size)
            val result = SpecificationValidationResults(specFile = specData.specFile, specificationOutcome = specOutcome)
            consoleOutput.printSpecificationSummary(result)
            return result
        }

        val feature = specOutcome.feature ?: run {
            consoleOutput.printExamplesSkipped(specExampleCount = specData.examples.specExamples.size, sharedExampleCount = specData.examples.sharedExamples.size)
            val result = SpecificationValidationResults(specFile = specData.specFile, specificationOutcome = specOutcome)
            consoleOutput.printSpecificationSummary(result)
            return result
        }

        val inlineExampleResult = validateInlineExamples(specData.specFile, feature)
        if (inlineExampleResult.isNotEmpty()) {
            consoleOutput.printExamplesSection("Inline examples", inlineExampleResult.size)
            inlineExampleResult.forEach { (name, result) ->
                consoleOutput.printInlineExampleResult(result, name)
            }
        }

        val specExampleResults = validateExamples(
            feature = feature,
            examples = specData.examples.specExamples,
            isSpecificationSpecific = true
        )

        if (specExampleResults.isNotEmpty()) {
            consoleOutput.printExamplesSection("Specification examples", specExampleResults.size)
            specExampleResults.forEachIndexed { exIndex, it ->
                consoleOutput.printExampleResult(it, exIndex + 1, specExampleResults.size)
            }
        }

        val sharedExampleResults = validateExamples(
            feature = feature,
            examples = specData.examples.sharedExamples,
            isSpecificationSpecific = false
        )

        if (sharedExampleResults.isNotEmpty()) {
            consoleOutput.printExamplesSection("Shared examples", sharedExampleResults.size)
            sharedExampleResults.forEachIndexed { exIndex, it ->
                consoleOutput.printExampleResult(it, exIndex + 1, sharedExampleResults.size)
            }
        }

        val finalExampleResult = validator.validateExamples(
            feature = feature,
            specmaticConfig = specmaticConfig,
            files = specData.examples.specExamples.plus(specData.examples.sharedExamples),
        )

        if (finalExampleResult is Result.Failure) {
            consoleOutput.printExamplesSection("Global example validation", count = 0)
            consoleOutput.printGlobalExampleResult(finalExampleResult)
        }

        val result = SpecificationValidationResults(
            specFile = specData.specFile,
            specificationOutcome = specOutcome,
            specificationExampleResults = specExampleResults,
            sharedExampleResults = sharedExampleResults
        )

        consoleOutput.printSpecificationSummary(result)
        return result
    }

    private fun validateSpecification(specFile: File): SpecificationValidationOutcome<Feature> {
        val validationResult = try {
            validator.validateSpecification(specFile, specmaticConfig = specmaticConfig,)
        } catch (e: Exception) {
            SpecValidationResult.FailedToLoad(result = e.toFailure())
        }

        return SpecificationValidationOutcome(file = specFile, validationResult = validationResult)
    }

    private fun validateExamples(examples: List<File>, feature: Feature, isSpecificationSpecific: Boolean): List<ExampleValidationOutcome> {
        return examples.map { exampleFile ->
            validateExample(exampleFile, feature, isSpecificationSpecific)
        }
    }

    private fun validateInlineExamples(specification: File, feature: Feature): Map<String, ExampleValidationOutcome> {
        return validator.validateInlineExamples(specification, feature, specmaticConfig = specmaticConfig,).mapValues { (_, result) ->
            ExampleValidationOutcome(file = result.file, validationResult = result, isSpecificationSpecific = true)
        }
    }

    private fun validateExample(exampleFile: File, feature: Feature, isSpecificationSpecific: Boolean): ExampleValidationOutcome {
        val validationResult = try {
            validator.validateExample(feature, exampleFile, specmaticConfig = specmaticConfig,)
        } catch (e: Exception) {
            ExampleValidationResult.FailedToLoad(file = exampleFile, result = e.toFailure())
        }

        return ExampleValidationOutcome(
            file = exampleFile,
            validationResult = validationResult,
            isSpecificationSpecific = isSpecificationSpecific
        )
    }
}