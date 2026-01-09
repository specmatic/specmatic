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
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.LicensedProduct
import io.specmatic.license.core.SpecmaticFeature
import io.specmatic.mock.PARTIAL
import io.specmatic.mock.ScenarioStub
import java.io.File

class ExampleValidationModule {

    fun validateInlineExamples(
        feature: Feature,
        examples: Map<String, List<ScenarioStub>> = emptyMap(),
        scenarioFilter: ScenarioFilter = ScenarioFilter()
    ): Map<String, Result> {
        val updatedFeature = scenarioFilter.filter(feature)

        val results = examples.mapValues { (name, exampleList) ->
            logger.debug("Validating $name")

            exampleList.mapNotNull { example ->
                val results = validateExample(updatedFeature, example)
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
            examples.associate { exampleFile ->
                logger.debug("Validating ${exampleFile.name}")
                exampleFile.canonicalPath to validateExample(updatedFeature, exampleFile)
            },
            callLifecycleHook(updatedFeature, ExampleModule().getExamplesFromFiles(examples))
        )
    }

    fun validateExample(contractFile: File, exampleFile: File): ValidationResult {
        val feature = parseContractFileWithNoMissingConfigWarning(contractFile)
        return ValidationResult(
            validateExample(feature, exampleFile),
            callLifecycleHook(feature, ExampleModule().getExamplesFromFiles(listOf(exampleFile)))
        )
    }

    fun validateExample(feature: Feature, scenarioStub: ScenarioStub): Results {
        return feature.matchResultFlagBased(scenarioStub, ExampleMismatchMessages)
    }

    private fun validateExample(feature: Feature, example: ExampleFromFile): Result {
        val scenarioResult = feature.matchResultFlagBased(
            request = example.request,
            response = example.response,
            mismatchMessages = ExampleMismatchMessages,
            isPartial = example.isPartial()
        ).toResultIfAnyWithCauses()

        val scenarioResultWithBreadCrumb = example.breadCrumbIfPartial(scenarioResult)
        return Result.fromResults(listOf(example.validationErrors, scenarioResultWithBreadCrumb))
    }

    private fun validateExample(feature: Feature, schemaExample: SchemaExample): Result {
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

    fun validateExample(feature: Feature, exampleFile: File): Result {

        LicenseResolver.utilize(
            product = LicensedProduct.OPEN_SOURCE,
            feature = SpecmaticFeature.EXAMPLES_VALIDATED,
            protocol = feature.protocols()
        )

        return ExampleFromFile.fromFile(exampleFile, strictMode = false).realise(
            hasValue = { example, _ -> validateExample(feature, example) },
            orFailure = { validateSchemaExample(feature, exampleFile) },
            orException = { it.toHasFailure().failure }
        )
    }

    fun validateSchemaExample(feature: Feature, exampleFile: File): Result {
        return SchemaExample.fromFile(exampleFile).realise(
            hasValue = { example, _ -> validateExample(feature, example) },
            orException = { it.toHasFailure().failure },
            orFailure = { it.failure }
        )
    }

    private fun callLifecycleHook(feature: Feature, examples: List<ExampleFromFile>): Result {
        val scenarioStubs = examples.map { ScenarioStub(request = it.request, filePath = it.file.path) }
        return LifecycleHooks.afterLoadingStaticExamples.call(
            ExamplesUsedFor.Validation,
            listOf(Pair(feature, scenarioStubs))
        )
    }
}

internal fun ExampleFromFile.breadCrumbIfPartial(result: Result): Result {
    return if (isPartial()) {
        result.breadCrumb(PARTIAL)
    } else {
        result
    }
}
