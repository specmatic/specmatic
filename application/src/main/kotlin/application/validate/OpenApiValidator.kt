package application.validate

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.FailureReason
import io.specmatic.core.Feature
import io.specmatic.core.Result
import io.specmatic.core.examples.module.ExampleValidationModule
import io.specmatic.loader.OpenApiLoaderStrategy
import io.specmatic.mock.ScenarioStub
import java.io.File

class OpenApiValidator(private val loaderStrategy: OpenApiLoaderStrategy = OpenApiLoaderStrategy()): Validator<Feature> {
    private val exampleValidationModule: ExampleValidationModule = ExampleValidationModule()

    override fun isCompatibleSpecification(file: File): Boolean {
        return loaderStrategy.isCompatibleSpecification(file)
    }

    override fun isCompatibleExample(file: File): Boolean {
        return loaderStrategy.isCompatibleExample(file)
    }

    override fun validateSpecification(specification: File): SpecValidationResult<Feature> {
        val (feature, result) = OpenApiSpecification.fromFile(specification.canonicalPath).toFeatureLenient()
        return SpecValidationResult.ValidationResult(feature, result)
    }

    override fun validateInlineExamples(specification: File, feature: Feature): Map<String, ExampleValidationResult> {
        return exampleValidationModule.validateInlineExamples(
            feature = feature,
            examples = feature.stubsFromExamples.mapValues { (_, stub) ->
                stub.map { (request, response) -> ScenarioStub(request, response) }
            },
        ).mapValues { (_, result) ->
            ExampleValidationResult.ValidationResult(specification, result)
        }
    }

    override fun validateExample(feature: Feature, file: File): ExampleValidationResult {
        val result = exampleValidationModule.validateExample(feature, file)
        return when {
            result !is Result.Failure -> ExampleValidationResult.ValidationResult(file, result)
            result.hasReason(FailureReason.IdentifierMismatch) -> ExampleValidationResult.DoesNotBelong(file, result)
            else -> ExampleValidationResult.ValidationResult(file, result)
        }
    }

    override fun validateExamples(feature: Feature, files: List<File>): Result {
        val examples = files.mapNotNull { exampleFile ->
            ExampleFromFile.fromFile(exampleFile, strictMode = false).realise(
                hasValue = { it, _ -> it }, orFailure = { null }, orException = { null }
            )
        }

        return exampleValidationModule.callLifecycleHook(feature, examples)
    }
}
