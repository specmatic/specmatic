package io.specmatic.core.examples.source

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.SpecificationPreparationDiagnostics
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Row
import io.specmatic.core.utilities.exceptionCauseMessage
import java.io.File

class DirectoryExampleSource(val exampleDirs: List<String>, val strictMode: Boolean, val specmaticConfig: SpecmaticConfig) : ExampleSource {
    override val examples: Map<OpenApiSpecification.OperationIdentifier, List<Row>>
        get() {
            return exampleDirs.flatMap { directory ->
                loadExternalisedJSONExamples(File(directory)).entries
            }.associate { it.toPair() }
        }

    private fun loadExternalisedJSONExamples(testsDirectory: File?): Map<OpenApiSpecification.OperationIdentifier, List<Row>> {
        if (testsDirectory == null)
            return emptyMap()

        if (!testsDirectory.exists())
            return emptyMap()

        val files = testsDirectory.walk().filterNot { it.isDirectory }.filter {
            it.extension == "json"
        }.toList().sortedBy { it.name }

        if (files.isEmpty()) return emptyMap()

        SpecificationPreparationDiagnostics.externalExamplesDiscovered(testsDirectory.path, files.size)

        val examplesInSubdirectories: Map<OpenApiSpecification.OperationIdentifier, List<Row>> =
            files.filter {
                it.isDirectory
            }.fold(emptyMap()) { acc, item ->
                acc + loadExternalisedJSONExamples(item)
            }

        logger.log("Loading externalised examples in ${testsDirectory.path}: ")
        logger.boundary()

        return examplesInSubdirectories + files.asSequence()
            .filterNot { it.isDirectory }
            .mapNotNull { exampleFile ->
                runCatching { ExampleFromFile(exampleFile) }.getOrElse { e ->
                    logger.log("Could not load test file ${exampleFile.canonicalPath}")
                    logger.log(e)
                    logger.boundary()
                    SpecificationPreparationDiagnostics.externalExampleLoadFailed(
                        exampleFile = exampleFile.canonicalPath,
                        throwable = e,
                        remediation = "Fix the example JSON structure or remove the file if it should not participate in this test run.",
                    )
                    if (strictMode) throw ContractException(exceptionCauseMessage(e))
                    null
                }
            }.map { example -> OpenApiSpecification.OperationIdentifier(example) to example.toRow(specmaticConfig) }
            .groupBy { (operationIdentifier, _) -> operationIdentifier }
            .mapValues { (_, value) -> value.map { it.second } }
    }
}
