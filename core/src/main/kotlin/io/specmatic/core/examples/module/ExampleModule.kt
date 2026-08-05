package io.specmatic.core.examples.module

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.core.*
import io.specmatic.core.examples.server.ExampleMismatchMessages
import io.specmatic.core.examples.server.SchemaExample
import io.specmatic.core.log.consoleDebug
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.NullValue
import io.specmatic.mock.PARTIAL
import java.io.File
import kotlin.system.exitProcess

data class ScenarioExampleMatch<T>(val example: T, val result: Result)
class ExampleModule(private val specmaticConfig: SpecmaticConfig) {
    fun getExistingExampleFiles(feature: Feature, scenario: Scenario, examples: List<ExampleFromFile>): List<Pair<ExampleFromFile, Result>> {
        val results = matchExamplesForScenario(
            feature = feature,
            scenario = scenario,
            examples = examples,
            requestOf = { it.request },
            responseOf = { it.response },
            isPartial = { it.isPartial() }
        )

        return results.map { it.example to it.result }
    }

    fun <T> matchExamplesForScenario(
        feature: Feature,
        examples: List<T>,
        scenario: Scenario,
        isPartial: (T) -> Boolean,
        requestOf: (T) -> HttpRequest,
        responseOf: (T) -> HttpResponse,
    ): List<ScenarioExampleMatch<T>> {
        return examples.mapNotNull { example ->
            val matchResult = scenario.matches(
                httpRequest = requestOf(example),
                httpResponse = responseOf(example),
                mismatchMessages = ExampleMismatchMessages,
                flagsBased = feature.flagsBased,
                isPartial = isPartial(example),
            )

            when (matchResult) {
                is Result.Success -> ScenarioExampleMatch(example, matchResult)
                is Result.Failure -> {
                    val isFailureRelatedToScenario = matchResult.getFailureBreadCrumbs("").none { breadCrumb ->
                        breadCrumb.contains(BreadCrumb.PATH.value)
                                || breadCrumb.contains(METHOD_BREAD_CRUMB)
                                || breadCrumb.contains(BreadCrumb.REQUEST.plus(BreadCrumb.PARAM_HEADER).with(CONTENT_TYPE))
                                || breadCrumb.contains("STATUS")
                                || breadCrumb.contains(BreadCrumb.RESPONSE.plus(BreadCrumb.HEADER).with(CONTENT_TYPE))
                    } || matchResult.hasReason(FailureReason.URLPathParamMismatchButSameStructure)
                            || matchResult.hasReason(FailureReason.UndeclaredRequestVariantMismatch)

                    if (!isFailureRelatedToScenario) return@mapNotNull null
                    if (!isPartial(example)) return@mapNotNull ScenarioExampleMatch(example, matchResult)
                    ScenarioExampleMatch(example, matchResult.breadCrumb(PARTIAL))
                }
            }
        }
    }

    fun getExamplesDirPaths(contractFile: File): List<File> {
        val testDirs = specmaticConfig.getTestExampleDirs(contractFile).map(::File)
        val stubDirs = specmaticConfig.getStubExampleDirs(contractFile).map(::File)
        val implicitExamplesDir = listOf(defaultExternalExampleDirFrom(contractFile))
        return listOf(implicitExamplesDir, testDirs, stubDirs).flatten().distinctBy { it.normalizedPath() }
    }

    fun getCandidateExampleFiles(contractFile: File): List<File> {
        return getExamplesDirPaths(contractFile)
            .filter { it.isDirectory }
            .flatMap { it.jsonFilesRecursively() }
    }

    fun getExamplesFor(contractFile: File, strictMode: Boolean = true): List<ExampleFromFile> {
        return getExamplesDirPaths(contractFile)
            .filter { it.isDirectory }
            .flatMap { getExamplesFromDir(it, strictMode) }
            .distinctBy { it.file.normalizedPath() }
    }

    fun getExamplesFromDir(dir: File, strictMode: Boolean = true): List<ExampleFromFile> {
        return getExamplesFromFiles(dir.jsonFilesRecursively(), strictMode)
    }

    fun getExamplesFromFiles(files: List<File>, strictMode: Boolean = true): List<ExampleFromFile> {
        return files.mapNotNull { exampleFromFile(it, strictMode) }
    }

    fun exampleFromFile(file: File, strictMode: Boolean = true): ExampleFromFile? {
        return ExampleFromFile.fromFile(file, strictMode).realise(
            orFailure = { null },
            hasValue = { example, _ -> example },
            orException = { err -> consoleDebug(exceptionCauseMessage(err.t)); null },
        )
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

    fun getFirstExampleDir(specFile: File): File {
        val testDirs = specmaticConfig.getTestExampleDirs(specFile).map(::File)
        val stubDirs = specmaticConfig.getStubExampleDirs(specFile).map(::File)
        val commonDir = firstCommonByNormalizedPath(testDirs, stubDirs)
        return commonDir ?: testDirs.firstOrNull() ?: stubDirs.firstOrNull() ?: defaultExternalExampleDirFrom(specFile)
    }

    private fun defaultExternalExampleDirFrom(contractFile: File): File {
        return contractFile.absoluteFile.parentFile.resolve(contractFile.nameWithoutExtension + "_examples")
    }

    private fun getSchemaExamples(dir: File): List<SchemaExample> {
        return dir.jsonFilesRecursively().mapNotNull {
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

    private fun File.jsonFilesRecursively(): List<File> {
        return walk().filter { it.isFile && it.extension == "json" }.toList()
    }

    private fun firstCommonByNormalizedPath(first: List<File>, second: List<File>): File? {
        val secondPaths = second.mapTo(HashSet()) { it.normalizedPath() }
        return first.firstOrNull { it.normalizedPath() in secondPaths }
    }
}
