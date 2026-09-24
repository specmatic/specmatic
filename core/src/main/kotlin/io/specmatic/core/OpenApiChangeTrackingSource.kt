package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.log.NonVerbose
import io.specmatic.mock.NoLogPrinter

data class OpenApiChangeTrackingSource(
    val yamlContent: String,
    val openApiFilePath: String,
    val lenientMode: Boolean,
) {
    fun scenarios(
        specmaticConfig: SpecmaticConfig,
        strictMode: Boolean,
        exampleDirPaths: List<String>,
    ): List<Scenario> {
        return OpenApiSpecification
            .fromYAMLForChangeTracking(
                yamlContent = yamlContent,
                openApiFilePath = openApiFilePath,
                specmaticConfig = specmaticConfig,
                strictMode = strictMode,
                lenientMode = lenientMode,
                exampleDirPaths = exampleDirPaths,
                logger = NonVerbose(NoLogPrinter()),
            )
            .toFeature()
            .scenarios
    }
}
