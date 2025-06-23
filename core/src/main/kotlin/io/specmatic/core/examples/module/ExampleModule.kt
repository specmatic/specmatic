package io.specmatic.core.examples.module

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.core.*
import io.specmatic.core.examples.server.InteractiveExamplesMismatchMessages
import io.specmatic.core.examples.server.SchemaExample
import io.specmatic.core.log.consoleDebug
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.NullValue
import java.io.File
import kotlin.system.exitProcess

class ExampleModule {

    fun getExistingExampleFiles(feature: Feature, scenario: Scenario, examples: List<ExampleFromFile>): List<Pair<ExampleFromFile, Result>> {
        return examples.mapNotNull { example ->
            val matchResult = scenario.matches(
                httpRequest = example.request,
                httpResponse = example.response,
                mismatchMessages = InteractiveExamplesMismatchMessages,
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
                    if (isFailureRelatedToScenario) { example to matchResult } else null
                }
            }
        }
    }

    fun getExamplesDirPath(contractFile: File): File {
        return contractFile.canonicalFile
            .parentFile
            .resolve("""${contractFile.nameWithoutExtension}$EXAMPLES_DIR_SUFFIX""")
    }

    fun getExamplesFromDir(dir: File): List<ExampleFromFile> {
        return getExamplesFromFiles(dir.listFiles().orEmpty().filter { it.extension == "json" })
    }

    fun getExamplesFromFiles(files: List<File>): List<ExampleFromFile> {
        return files.mapNotNull {
            ExampleFromFile.fromFile(it).realise(
                hasValue = { example, _ -> example },
                orException = { err -> consoleDebug(exceptionCauseMessage(err.t)); null },
                orFailure = { null }
            )
        }
    }

    fun getSchemaExamplesWithValidation(
        feature: Feature,
        examplesDir: File
    ): List<Pair<SchemaExample, Result?>> {
        return getSchemaExamples(examplesDir).map {
            it to if(it.value !is NullValue) {
                feature.matchResultSchemaFlagBased(
                    discriminatorPatternName = it.discriminatorBasedOn,
                    patternName = it.schemaBasedOn,
                    value = it.value,
                    mismatchMessages = InteractiveExamplesMismatchMessages,
                    breadCrumbIfDiscriminatorMismatch = it.file.name
                )
            } else null
        }
    }


    fun loadExternalExamples(
        examplesDir: File
    ): Pair<File, List<File>> {
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

    fun getSchemaExamples(dir: File): List<SchemaExample> {
        return dir.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
            SchemaExample.fromFile(it).realise(
                hasValue = { example, _ -> example },
                orException = { err -> consoleDebug(exceptionCauseMessage(err.t)); null },
                orFailure = { null }
            )
        }
    }

}
