package io.specmatic.core.config.v3

import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.v3.components.runOptions.RunOptionType
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.reporter.model.SpecType
import java.io.File

interface ProtocolConfigValidator {
    fun supports(specType: SpecType, runOptionType: RunOptionType): Boolean
    fun validate(
        specification: File,
        context: ValidationContext,
        configuration: Map<String, Any>,
        definition: SpecificationDefinition,
    ): List<ConfigValidationOutput>
}
