package io.specmatic.core.examples.module

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.core.*
import io.specmatic.core.examples.server.ExampleMismatchMessages
import io.specmatic.core.examples.server.ScenarioFilter
import io.specmatic.core.examples.server.SchemaExample
import io.specmatic.core.lifecycle.ExamplesUsedFor
import io.specmatic.core.lifecycle.LifecycleHooks
import io.specmatic.core.log.logger
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicensedProduct
import io.specmatic.license.core.SpecmaticFeature
import io.specmatic.mock.FuzzyExampleJsonValidator
import io.specmatic.mock.PARTIAL
import io.specmatic.mock.ScenarioStub
import java.io.File

class ExampleValidationModule(private val lenientMode: Boolean = false, private val specmaticConfig: SpecmaticConfig) {
    fun validateProjectedInlineExampleStructure(example: JSONObjectValue): Result {
        return FuzzyExampleJsonValidator.matchesInlineExample(example)
    }

    fun validateProjectedInlineExample(feature: Feature, example: ScenarioStub): Result {
        return Result.fromResults(
            listOf(
                validateProjectedInlineExampleStructure(example.toJSON()),
                validateExample(feature, example)
            )
        )
    }

    fun validateProjectedInlineExample(feature: Feature, example: ExampleFromFile): Result {
        return Result.fromResults(
            listOf(
                validateProjectedInlineExampleStructure(example.json),
                validateExample(feature, example),
            )
        )
    }

    fun validateInlineExamples(
        feature: Feature,
        examples: List<NamedStub>,
        scenarioFilter: ScenarioFilter = ScenarioFilter()
    ): Map<String, Result> {
        val groupedExamples = examples.groupBy(
            keySelector = { it.name },
            valueTransform = { it.stub }
        )
        return validateInlineExamplesInternal(feature, groupedExamples, scenarioFilter)
    }

    @Deprecated(
        message = "Use list-based inline examples API",
        replaceWith = ReplaceWith(
            "validateInlineExamples(feature, examples.flatMap { (name, stubs) -> stubs.map { NamedStub(name, it) } }, scenarioFilter)"
        )
    )
    fun validateInlineExamples(
        feature: Feature,
        examples: Map<String, List<ScenarioStub>> = emptyMap(),
        scenarioFilter: ScenarioFilter = ScenarioFilter()
    ): Map<String, Result> {
        return validateInlineExamplesInternal(feature, examples, scenarioFilter)
    }

    private fun validateInlineExamplesInternal(
        feature: Feature,
        examples: Map<String, List<ScenarioStub>>,
        scenarioFilter: ScenarioFilter
    ): Map<String, Result> {
        val updatedFeature = scenarioFilter.filter(feature)

        val results = examples.mapValues { (name, exampleList) ->
            logger.debug("Validating $name")

            exampleList.mapNotNull { example ->
                val results = validateExampleReturningResults(updatedFeature, example)
                if (!results.hasResults()) return@mapNotNull null else results.toResultIfAny()
            }.let {
                Result.fromResults(it)
            }
        }

        return results
    }

    fun validateExamples(
        feature: Feature,
        examples: List<File> = emptyList(),
        scenarioFilter: ScenarioFilter = ScenarioFilter()
    ): ValidationResults {
        val updatedFeature = scenarioFilter.filter(feature)
        return ValidationResults(
            exampleValidationResults = examples.associate { exampleFile ->
                logger.debug("Validating ${exampleFile.name}")
                exampleFile.canonicalPath to validateExample(updatedFeature, exampleFile)
            },
            hookValidationResult = callLifecycleHook(
                feature = updatedFeature,
                examples = ExampleModule(specmaticConfig).getExamplesFromFiles(examples)
            )
        )
    }

    fun validateExample(contractFile: File, exampleFile: File): ValidationResult {
        val feature = parseContractFileToFeature(contractFile, specmaticConfig = specmaticConfig, lenientMode = lenientMode)
        return ValidationResult(
            exampleValidationResult = validateExample(feature, exampleFile),
            hookValidationResult = callLifecycleHook(feature, ExampleModule(specmaticConfig).getExamplesFromFiles(listOf(exampleFile)))
        )
    }

    fun validateExample(contractFile: File, exampleFile: ExampleFromFile): ValidationResult {
        val feature = parseContractFileToFeature(contractFile, specmaticConfig = specmaticConfig, lenientMode = lenientMode)
        return ValidationResult(
            exampleValidationResult = validateExample(feature, exampleFile),
            hookValidationResult = callLifecycleHook(feature, listOf(exampleFile))
        )
    }

    fun validateExampleReturningResults(feature: Feature, scenarioStub: ScenarioStub): Results {
        LicenseResolver.utilize(
            product = LicensedProduct.OPEN_SOURCE,
            feature = SpecmaticFeature.EXAMPLES_VALIDATED,
            protocol = listOf(feature.protocol)
        )

        return feature.matchResultFlagBased(scenarioStub, ExampleMismatchMessages)
    }

    fun validateExample(feature: Feature, scenarioStub: ScenarioStub): Result {
        val result = validateExampleReturningResults(feature, scenarioStub).toResultIfAnyWithCauses()
        val scenarioResultWithBreadCrumb = scenarioStub.breadCrumbIfPartial(result)
        return Result.fromResults(listOf(scenarioStub.validationErrors, scenarioResultWithBreadCrumb))
    }

    fun validateExample(feature: Feature, example: ExampleFromFile): Result {
        LicenseResolver.utilize(
            product = LicensedProduct.OPEN_SOURCE,
            feature = SpecmaticFeature.EXAMPLES_VALIDATED,
            protocol = listOf(feature.protocol)
        )

        val scenarioResult = feature.matchResultFlagBased(
            request = example.request,
            response = example.response,
            mismatchMessages = ExampleMismatchMessages,
            isPartial = example.isPartial()
        ).toResultIfAnyWithCauses()

        val scenarioResultWithBreadCrumb = example.breadCrumbIfPartial(scenarioResult)
        return Result.fromResults(listOf(example.validationErrors, scenarioResultWithBreadCrumb))
    }

    fun validateSchemaExample(feature: Feature, schemaExample: SchemaExample): Result {
        LicenseResolver.utilize(
            product = LicensedProduct.OPEN_SOURCE,
            feature = SpecmaticFeature.EXAMPLES_VALIDATED,
            protocol = listOf(feature.protocol)
        )

        if (schemaExample.value is NullValue) {
            return Result.Success()
        }

        return feature.matchResultSchemaFlagBased(
            discriminatorPatternName = schemaExample.discriminatorBasedOn,
            patternName = schemaExample.schemaBasedOn,
            value = schemaExample.value,
            mismatchMessages = ExampleMismatchMessages,
            breadCrumbIfDiscriminatorMismatch = schemaExample.file.name
        )
    }

    fun validateExample(feature: Feature, exampleFile: File, strictMode: Boolean = false): Result {
        return ExampleFromFile.fromFile(exampleFile, strictMode = strictMode).realise(
            hasValue = { example, _ -> validateExample(feature, example) },
            orFailure = { validateSchemaExample(feature, exampleFile) },
            orException = { it.toHasFailure().failure }
        )
    }

    fun validateSchemaExample(feature: Feature, exampleFile: File): Result {
        return SchemaExample.fromFile(exampleFile).realise(
            hasValue = { example, _ -> validateSchemaExample(feature, example) },
            orException = { it.toHasFailure().failure },
            orFailure = { it.failure }
        )
    }

    fun callLifecycleHook(feature: Feature, examples: List<ExampleFromFile>): Result {
        val scenarioStubs = examples.map { ScenarioStub(request = it.request, filePath = it.file.path) }
        return LifecycleHooks.afterLoadingStaticExamples.call(
            ExamplesUsedFor.Validation,
            listOf(Pair(feature, scenarioStubs))
        )
    }
}

internal fun ScenarioStub.breadCrumbIfPartial(result: Result): Result {
    return if (isPartial()) {
        result.breadCrumb(PARTIAL)
    } else {
        result
    }
}

internal fun ExampleFromFile.breadCrumbIfPartial(result: Result): Result {
    return if (isPartial()) {
        result.breadCrumb(PARTIAL)
    } else {
        result
    }
}
