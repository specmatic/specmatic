package io.specmatic.core.examples.module

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.core.*
import io.specmatic.core.examples.server.ExampleMismatchMessages
import io.specmatic.core.examples.server.SchemaExample
import io.specmatic.core.log.consoleDebug
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.NullValue
import java.io.File
import kotlin.system.exitProcess

class ExampleModule(private val specmaticConfig: SpecmaticConfig) {
    fun getExistingExampleFiles(feature: Feature, scenario: Scenario, examples: List<ExampleFromFile>): List<Pair<ExampleFromFile, Result>> {
        return examples.mapNotNull { example ->
            val matchResult = scenario.matches(
                httpRequest = example.request,
                httpResponse = example.response,
                mismatchMessages = ExampleMismatchMessages,
                flagsBased = feature.flagsBased,
                isPartial = example.isPartial()
            )

            when (matchResult) {
                is Result.Success -> example to matchResult
                is Result.Failure -> {
                    val isFailureRelatedToScenario = matchResult.getFailureBreadCrumbs("").none { breadCrumb ->
                        breadCrumb.contains(BreadCrumb.PATH.value)
                                || breadCrumb.contains(METHOD_BREAD_CRUMB)
                                || breadCrumb.contains(BreadCrumb.REQUEST.plus(BreadCrumb.PARAM_HEADER).with(CONTENT_TYPE))
                                || breadCrumb.contains("STATUS")
                    } || matchResult.hasReason(FailureReason.URLPathParamMismatchButSameStructure)
                    if (isFailureRelatedToScenario) { example to example.breadCrumbIfPartial(matchResult) } else null
                }
            }
        }
    }

    fun getExamplesDirPaths(contractFile: File): List<File> {
        val testDirs = specmaticConfig.getTestExampleDirs(contractFile).map(::File)
        val stubDirs = specmaticConfig.getStubExampleDirs(contractFile).map(::File)
        val externalDir = listOf(defaultExternalExampleDirFrom(contractFile))
        return listOf(testDirs, stubDirs, externalDir).flatten().distinctBy { it.normalizedPath() }
    }

    fun getExamplesFor(contractFile: File, strictMode: Boolean = true): List<ExampleFromFile> {
        return getExamplesDirPaths(contractFile)
            .filter { it.isDirectory }
            .flatMap { getExamplesFromDir(it, strictMode) }
            .distinctBy { it.file.normalizedPath() }
    }

    fun getExamplesFromDir(dir: File, strictMode: Boolean = true): List<ExampleFromFile> {
        return getExamplesFromFiles(dir.listFiles().orEmpty().filter { it.extension == "json" }, strictMode)
    }

    fun getExamplesFromFiles(files: List<File>, strictMode: Boolean = true): List<ExampleFromFile> {
        return files.mapNotNull {
            ExampleFromFile.fromFile(it, strictMode).realise(
                hasValue = { example, _ -> example },
                orException = { err -> consoleDebug(exceptionCauseMessage(err.t)); null },
                orFailure = { null }
            )
        }
    }

    fun getSchemaExamplesFor(contractFile: File): List<SchemaExample> {
        val exampleDirs = getExamplesDirPaths(contractFile)
        return exampleDirs.fold(emptyList()) { acc, dir -> acc.plus(getSchemaExamples(dir)) }
    }

    fun getSchemaExamplesWithValidation(feature: Feature): List<Pair<SchemaExample, Result?>> {
        val exampleDirs = getExamplesDirPaths(File(feature.path))
        return exampleDirs.fold(emptyList()) { acc, dir ->
            acc.plus(getSchemaExamplesWithValidation(feature, dir))
        }
    }

    fun getSchemaExamplesWithValidation(feature: Feature, examplesDir: File): List<Pair<SchemaExample, Result?>> {
        return getSchemaExamples(examplesDir).map {
            it to if(it.value !is NullValue) {
                feature.matchResultSchemaFlagBased(
                    discriminatorPatternName = it.discriminatorBasedOn,
                    patternName = it.schemaBasedOn,
                    value = it.value,
                    mismatchMessages = ExampleMismatchMessages,
                    breadCrumbIfDiscriminatorMismatch = it.file.name
                )
            } else null
        }
    }

    fun loadExternalExamples(examplesDir: File): Pair<File, List<File>> {
        if (!examplesDir.isDirectory) {
            logger.log("$examplesDir does not exist, did not find any files to validate")
            exitProcess(1)
        }

        return examplesDir to examplesDir.walk().mapNotNull {
            it.takeIf { it.isFile && it.extension == "json" }
        }.toList()
    }

    fun defaultExternalExampleDirFrom(contractFile: File): File {
        return contractFile.absoluteFile.parentFile.resolve(contractFile.nameWithoutExtension + "_examples")
    }

    private fun getSchemaExamples(dir: File): List<SchemaExample> {
        return dir.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
            SchemaExample.fromFile(it).realise(
                hasValue = { example, _ -> example },
                orException = { err -> consoleDebug(exceptionCauseMessage(err.t)); null },
                orFailure = { null }
            )
        }
    }

    private fun File.normalizedPath(): String {
        return runCatching { canonicalPath }.getOrElse { absolutePath }
    }
}
