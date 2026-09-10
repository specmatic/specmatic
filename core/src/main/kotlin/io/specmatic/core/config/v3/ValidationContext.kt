package io.specmatic.core.config.v3

import io.specmatic.core.config.v3.components.runOptions.RunOptionType
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.validation.ConfigValidationMetadata
import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.validation.ConfigValidationSeverity
import io.specmatic.reporter.model.SpecType
import java.io.File
import java.util.ServiceLoader

data class ValidationContext(
    val location: String = "",
    val resolver: RefOrValueResolver,
    val protocolConfigValidators: List<ProtocolConfigValidator> = ServiceLoader.load(ProtocolConfigValidator::class.java).toList()
) {
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

    fun validateProtocolConfig(specType: SpecType, runOptionType: RunOptionType, specFile: File, definition: SpecificationDefinition, config: Map<String, Any>): List<ConfigValidationOutput> {
        val validators = protocolConfigValidators.filter { it.supports(specType, runOptionType) }
        return validators.flatMap { validator ->
            runCatching {
                validator.validate(context = this, configuration = config, definition = definition, specification = specFile)
            }.getOrElse { failure ->
                listOf(
                    element = error(
                        severity = ConfigValidationSeverity.ERROR,
                        message = "Protocol validation failed for '${specFile.path}': ${failure.message ?: failure::class.simpleName}",
                    )
                )
            }
        }
    }

    fun <R: Any> updateWithRefOrValue(refOrValue: RefOrValue<R>?): ValidationContext {
        if (refOrValue !is RefOrValue.Reference) return this
        return contextFor(refOrValue)
    }

    private fun contextFor(reference: RefOrValue.Reference): ValidationContext {
        val targetLocation = reference.ref.takeIf { it.startsWith("#/") }?.removePrefix("#")
        return targetLocation?.let { copy(location = it) } ?: this
    }
}
