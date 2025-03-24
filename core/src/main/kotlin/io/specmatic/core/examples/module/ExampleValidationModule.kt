package io.specmatic.core.examples.module

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.core.Feature
import io.specmatic.core.Result
import io.specmatic.core.Results
import io.specmatic.core.examples.server.InteractiveExamplesMismatchMessages
import io.specmatic.core.examples.server.ScenarioFilter
import io.specmatic.core.examples.server.SchemaExample
import io.specmatic.core.log.logger
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.NullValue
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
    ): Map<String, Result> {
        val updatedFeature = scenarioFilter.filter(feature)

        val results = examples.associate { exampleFile ->
            logger.debug("Validating ${exampleFile.name}")
            exampleFile.canonicalPath to validateExample(updatedFeature, exampleFile)
        }

        return results
    }

    fun validateExample(contractFile: File, exampleFile: File): Result {
        val feature = parseContractFileToFeature(contractFile)
        return validateExample(feature, exampleFile)
    }

    fun validateExample(feature: Feature, scenarioStub: ScenarioStub): Results {
        return feature.matchResultFlagBased(scenarioStub, InteractiveExamplesMismatchMessages)
    }

    private fun validateExample(feature: Feature, example: ExampleFromFile): Result {
        return feature.matchResultFlagBased(example.request, example.response, InteractiveExamplesMismatchMessages).toResultIfAnyWithCauses()
    }

    private fun validateExample(feature: Feature, schemaExample: SchemaExample): Result {
        if (schemaExample.value is NullValue) {
            return Result.Success()
        }

        return feature.matchResultSchemaFlagBased(
            discriminatorPatternName = schemaExample.discriminatorBasedOn,
            patternName = schemaExample.schemaBasedOn,
            value = schemaExample.value,
            mismatchMessages = InteractiveExamplesMismatchMessages,
            breadCrumbIfDiscriminatorMismatch = schemaExample.file.name
        )
    }

    fun validateExample(feature: Feature, exampleFile: File): Result {
        return ExampleFromFile.fromFile(exampleFile).realise(
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

}