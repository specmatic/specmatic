package application.validate

import io.specmatic.core.Result
import io.specmatic.loader.LoaderStrategy
import java.io.File

sealed interface SpecValidationResult<Feature> {
    data class FailedToLoad<Feature>(val result: Result.Failure): SpecValidationResult<Feature>
    data class ValidationResult<Feature>(val feature: Feature, val result: Result) : SpecValidationResult<Feature>
}

sealed interface ExampleValidationResult {
    val file: File
    val result: Result

    data class FailedToLoad(override val file: File, override val result: Result.Failure): ExampleValidationResult
    data class DoesNotBelong(override val file: File, override val result: Result) : ExampleValidationResult
    data class ValidationResult(override val file: File, override val result: Result) : ExampleValidationResult
}

interface Validator<Feature> : LoaderStrategy {
    fun validateSpecification(specification: File): SpecValidationResult<Feature>
    fun validateInlineExamples(specification: File, feature: Feature): Map<String, ExampleValidationResult>
    fun validateExample(feature: Feature, file: File): ExampleValidationResult
    fun validateExamples(feature: Feature, files: List<File>): Result
}
