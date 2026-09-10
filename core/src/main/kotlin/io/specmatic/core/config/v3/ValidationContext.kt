package io.specmatic.core.config.v3

import io.specmatic.core.config.validation.ConfigValidationMetadata
import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.validation.ConfigValidationSeverity

data class ValidationContext(val resolver: RefOrValueResolver, private val location: String = "") {
    fun child(segment: String): ValidationContext = copy(location = "$location/$segment")

    fun child(index: Int): ValidationContext = child(index.toString())

    fun error(severity: ConfigValidationSeverity, message: String, metadata: ConfigValidationMetadata? = null): ConfigValidationOutput {
        return ConfigValidationOutput(
            valid = false,
            error = message,
            severity = severity,
            metadata = metadata,
            keywordLocation = "",
            instanceLocation = location,
        )
    }

    fun <T : Any> check(
        reference: RefOrValue<T>,
        resolve: (RefOrValue<T>, RefOrValueResolver) -> T,
        validate: (T, ValidationContext) -> List<ConfigValidationOutput> = { _, _ -> emptyList() },
        metadata: ConfigValidationMetadata? = null,
    ): List<ConfigValidationOutput> {
        return when (reference) {
            is RefOrValue.Value -> validate(reference.value, this)
            is RefOrValue.Reference -> {
                val resolved = runCatching { resolve(reference, resolver) }.getOrElse { failure ->
                    return listOf(error(
                        metadata = metadata,
                        severity = ConfigValidationSeverity.ERROR,
                        message = "Reference '${reference.ref}' could not be resolved as the expected typed value: ${failure.message ?: failure::class.simpleName}",
                    ))
                }

                validate(resolved, contextFor(reference))
            }
        }
    }

    private fun contextFor(reference: RefOrValue.Reference): ValidationContext {
        val targetLocation = reference.ref.takeIf { it.startsWith("#/") }?.removePrefix("#")
        return targetLocation?.let { copy(location = it) } ?: this
    }
}
