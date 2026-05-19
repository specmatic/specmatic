package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification

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
            )
            .toFeature()
            .scenarios
    }
}
