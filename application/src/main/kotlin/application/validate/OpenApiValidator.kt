package application.validate

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.FailureReason
import io.specmatic.core.Feature
import io.specmatic.core.OPENAPI_FILE_EXTENSIONS
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.examples.module.ExampleValidationModule
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.mock.ScenarioStub
import io.specmatic.test.asserts.toFailure
import java.io.File

class OpenApiValidator: Validator<Feature> {
    override fun validateSpecification(specification: File, specmaticConfig: SpecmaticConfig): SpecValidationResult<Feature> {
        if (specification.extension in OPENAPI_FILE_EXTENSIONS) {
            val (feature, result) = OpenApiSpecification.fromFile(specification.canonicalPath, specmaticConfig).toFeatureLenient()
            return SpecValidationResult.ValidationResult(feature, result)
        }

        return runCatching {
            SpecValidationResult.ValidationResult(parseContractFileToFeature(specification), Result.Success())
        }.getOrElse { exception ->
            SpecValidationResult.FailedToLoad(exception.toFailure())
        }
    }

    override fun validateInlineExamples(specification: File, feature: Feature, specmaticConfig: SpecmaticConfig): Map<String, ExampleValidationResult> {
        return ExampleValidationModule(specmaticConfig = specmaticConfig).validateInlineExamples(
            feature = feature,
            examples = feature.stubsFromExamples.mapValues { (_, stub) ->
                stub.map { (request, response) -> ScenarioStub(request, response) }
            },
        ).mapValues { (_, result) ->
            ExampleValidationResult.ValidationResult(specification, result)
        }
    }

    override fun validateExample(feature: Feature, file: File, specmaticConfig: SpecmaticConfig): ExampleValidationResult {
        val result = ExampleValidationModule(specmaticConfig = specmaticConfig).validateExample(feature, file)
        return when {
            result !is Result.Failure -> ExampleValidationResult.ValidationResult(file, result)
            result.hasReason(FailureReason.IdentifierMismatch) -> ExampleValidationResult.DoesNotBelong(file, result)
            else -> ExampleValidationResult.ValidationResult(file, result)
        }
    }

    override fun validateExamples(feature: Feature, files: List<File>, specmaticConfig: SpecmaticConfig): Result {
        val examples = files.mapNotNull { exampleFile ->
            ExampleFromFile.fromFile(exampleFile, strictMode = false).realise(
                hasValue = { it, _ -> it }, orFailure = { null }, orException = { null }
            )
        }

        return ExampleValidationModule(specmaticConfig = specmaticConfig).callLifecycleHook(feature, examples)
    }
}
