package io.specmatic.core.config.v3

import io.specmatic.core.config.v3.components.runOptions.IRunOptionSpecification
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.validation.ConfigValidationOutput
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.cast

abstract class TypedProtocolConfigValidator<Global : IRunOptions, Override : IRunOptionSpecification>(
    private val globalType: KClass<Global>,
    private val overrideType: KClass<Override>,
) : ProtocolConfigValidator {
    final override fun validate(
        specification: File,
        definition: SpecificationDefinition,
        configuration: ApplicableProtocolConfig<*, *>,
    ): List<ConfigValidationOutput> {
        val typedConfiguration = typedConfiguration(configuration) ?: return emptyList()
        return validateTyped(
            definition = definition,
            specification = specification,
            configuration = typedConfiguration,
        )
    }

    protected abstract fun validateTyped(
        specification: File,
        definition: SpecificationDefinition,
        configuration: ApplicableProtocolConfig<Global, Override>,
    ): List<ConfigValidationOutput>

    private fun typedConfiguration(
        configuration: ApplicableProtocolConfig<*, *>,
    ): ApplicableProtocolConfig<Global, Override>? {
        return when (configuration) {
            is ApplicableProtocolConfig.Global -> typedGlobal(configuration)
            is ApplicableProtocolConfig.Override -> typedOverride(configuration)
        }
    }

    private fun typedGlobal(
        configuration: ApplicableProtocolConfig.Global<*>,
    ): ApplicableProtocolConfig<Global, Override>? {
        val runOptions = configuration.runOptions.castTo(globalType) ?: return null
        return ApplicableProtocolConfig.Global(
            runOptions = runOptions,
            runOptionType = configuration.runOptionType,
        )
    }

    private fun typedOverride(
        configuration: ApplicableProtocolConfig.Override<*, *>,
    ): ApplicableProtocolConfig<Global, Override>? {
        val runOptions = configuration.runOptions.castTo(globalType) ?: return null
        val specOverride = configuration.specOverride.castTo(overrideType) ?: return null
        return ApplicableProtocolConfig.Override(
            runOptions = runOptions,
            specOverride = specOverride,
            runOptionType = configuration.runOptionType,
        )
    }

    private fun <T : Any> ValueWithContext<*>.castTo(type: KClass<T>): ValueWithContext<T>? {
        return type.safeCast(value)?.let { ValueWithContext(it, context) }
    }

    private fun <T : Any> KClass<T>.safeCast(value: Any?): T? {
        return if (value != null && isInstance(value)) cast(value) else null
    }
}
