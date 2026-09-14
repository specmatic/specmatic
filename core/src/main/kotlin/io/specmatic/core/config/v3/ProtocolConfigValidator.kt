package io.specmatic.core.config.v3

import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.v3.components.runOptions.IRunOptionSpecification
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import java.io.File

data class ValueWithContext<out T>(val value: T, val context: ValidationContext)
sealed interface ApplicableProtocolConfig<out Global : IRunOptions, out Override : IRunOptionSpecification> {
    val context: ValidationContext
    val runOptions: ValueWithContext<Global>

    data class Global<Global : IRunOptions>(
        override val runOptions: ValueWithContext<Global>,
        override val context: ValidationContext = runOptions.context
    ) : ApplicableProtocolConfig<Global, Nothing>

    data class Override<Global : IRunOptions, Override : IRunOptionSpecification>(
        val specOverride: ValueWithContext<Override>,
        override val runOptions: ValueWithContext<Global>,
        override val context: ValidationContext = specOverride.context,
    ) : ApplicableProtocolConfig<Global, Override>
}

interface ProtocolConfigValidator {
    fun validate(
        specification: File,
        definition: SpecificationDefinition,
        configuration: ApplicableProtocolConfig<*, *>,
    ): List<ConfigValidationOutput>
}
