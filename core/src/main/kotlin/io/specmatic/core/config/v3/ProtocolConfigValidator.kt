package io.specmatic.core.config.v3

import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.v3.components.runOptions.IRunOptionSpecification
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.RunOptionType
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import java.io.File

data class ValueWithContext<out T>(val value: T, val context: ValidationContext)
sealed interface ApplicableProtocolConfig<out Global : IRunOptions, out Override : IRunOptionSpecification> {
    val runOptionType: RunOptionType
    val runOptions: ValueWithContext<Global>
    @Suppress("RemoveRedundantQualifierName")
    val context: ValidationContext
        get() = when (this) {
            is ApplicableProtocolConfig.Global<*> -> runOptions.context
            is ApplicableProtocolConfig.Override<*, *> -> specOverride.context
        }

    data class Global<Global : IRunOptions>(
        override val runOptionType: RunOptionType,
        override val runOptions: ValueWithContext<Global>,
    ) : ApplicableProtocolConfig<Global, Nothing>

    data class Override<Global : IRunOptions, Override : IRunOptionSpecification>(
        override val runOptionType: RunOptionType,
        val specOverride: ValueWithContext<Override>,
        override val runOptions: ValueWithContext<Global>,
    ) : ApplicableProtocolConfig<Global, Override>
}

interface ProtocolConfigValidator {
    fun validate(
        specification: File,
        definition: SpecificationDefinition,
        configuration: ApplicableProtocolConfig<*, *>,
    ): List<ConfigValidationOutput>
}
