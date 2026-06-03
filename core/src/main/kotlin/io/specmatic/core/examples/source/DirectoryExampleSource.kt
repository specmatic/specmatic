package io.specmatic.core.examples.source

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Row
import io.specmatic.core.utilities.exceptionCauseMessage
import java.io.File

class DirectoryExampleSource(val exampleDirs: List<String>, val strictMode: Boolean, val specmaticConfig: SpecmaticConfig) : ExampleSource {
    override val examples: Map<OpenApiSpecification.OperationIdentifier, List<Row>>
        get() {
            return exampleDirs.map(::File).distinctBy { it.normalizedPath() }
                .flatMap { directory -> loadExternalisedJSONExamples(directory).entries }
                .groupBy(keySelector = { it.key }, valueTransform = { it.value })
                .mapValues { (_, lists) -> lists.flatten() }
        }

    private fun filesIn(testsDirectory: File): List<File> {
        if (!testsDirectory.exists()) return emptyList()
        return testsDirectory.walk()
            .filterNot { it.isDirectory }
            .filter { it.extension == "json" }
            .toList().sortedBy { it.name }
    }

    private fun loadExternalisedJSONExamples(testsDirectory: File): Map<OpenApiSpecification.OperationIdentifier, List<Row>> {
        val files = filesIn(testsDirectory)
        logger.boundary()
        logger.log("Loading externalised examples from ${testsDirectory.path}: ")
        logger.withIndentation(count = 2) { logger.log("${files.size} examples(s) found in ${testsDirectory.path}") }
        logger.boundary()

        return files.asSequence()
            .filterNot { it.isDirectory }
            .mapNotNull { exampleFile ->
                runCatching { ExampleFromFile(exampleFile) }.getOrElse { e ->
                    logger.log("Could not load test file ${exampleFile.canonicalPath}")
                    logger.log(e)
                    logger.boundary()
                    if (strictMode) throw ContractException(exceptionCauseMessage(e))
                    null
                }
            }.map { example -> OpenApiSpecification.OperationIdentifier(example) to example.toRow(specmaticConfig) }
            .groupBy { (operationIdentifier, _) -> operationIdentifier }
            .mapValues { (_, value) -> value.map { it.second } }
    }

    private fun File.normalizedPath(): String {
        return runCatching { canonicalPath }.getOrElse { absolutePath }
    }
}
