@file:JvmName("API")
package io.specmatic.stub

import io.specmatic.core.CONTRACT_EXTENSION
import io.specmatic.core.CONTRACT_EXTENSIONS
import io.specmatic.core.CommandHook
import io.specmatic.core.ContractAndStubMismatchMessages
import io.specmatic.core.DATA_DIR_SUFFIX
import io.specmatic.core.Feature
import io.specmatic.core.HookName
import io.specmatic.core.HttpRequest
import io.specmatic.core.MISSING_CONFIG_FILE_MESSAGE
import io.specmatic.core.OPENAPI_FILE_EXTENSIONS
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.WorkingDirectory
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.git.SystemGit
import io.specmatic.core.isContractFile
import io.specmatic.core.loadSpecmaticConfigOrDefault
import io.specmatic.core.log.StringLog
import io.specmatic.core.log.consoleDebug
import io.specmatic.core.log.consoleLog
import io.specmatic.core.log.logger
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.parseGherkinStringToFeature
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.core.utilities.ContractPathData.Companion.specToBaseUrlMap
import io.specmatic.core.utilities.contractStubPaths
import io.specmatic.core.utilities.examplesDirFor
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.utilities.exitWithMessage
import io.specmatic.core.utilities.runWithTimeout
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.mock.ScenarioStub
import org.yaml.snakeyaml.Yaml
import java.io.File

private const val HTTP_STUB_SHUTDOWN_TIMEOUT = 2000L
private const val STUB_START_TIMEOUT = 20_000L
private const val INDENT = "  "

// Used by stub client code
fun createStubFromContractAndData(contractGherkin: String, dataDirectory: String, host: String = "localhost", port: Int = 9000): ContractStub {
    val contractBehaviour = parseGherkinStringToFeature(contractGherkin)

    val mocks = (File(dataDirectory).listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()).map { file ->
        consoleLog(StringLog("Loading data from ${file.name}"))

        ScenarioStub.readFromFile(file)
                .also {
                    contractBehaviour.matchingStub(it, ContractAndStubMismatchMessages)
                }
    }

    return HttpStub(
        contractBehaviour,
        mocks,
        host,
        port,
        ::consoleLog,
        specToStubBaseUrlMap = mapOf(contractBehaviour.specification.orEmpty() to endPointFromHostAndPort(host, port, null))
    )
}

// Used by stub client code
fun allContractsFromDirectory(dirContainingContracts: String): List<String> =
    File(dirContainingContracts).listFiles()?.filter { it.extension == CONTRACT_EXTENSION }?.map { it.absolutePath } ?: emptyList()

fun createStub(host: String = "localhost", port: Int = 9000): ContractStub {
    return createStub(host, port, false)
}

fun createStub(host: String = "localhost", port: Int = 9000, strict: Boolean = false): ContractStub {
    return createStub(host, port, timeoutMillis = HTTP_STUB_SHUTDOWN_TIMEOUT, strict)
}

// Used by stub client code
fun createStub(
    dataDirPaths: List<String>,
    host: String = "localhost",
    port: Int = 9000
): ContractStub {
    return createStub(dataDirPaths, host, port, false)
}

fun createStub(
    dataDirPaths: List<String>,
    host: String = "localhost",
    port: Int = 9000,
    strict: Boolean = false
): ContractStub {
    return createStub(dataDirPaths, host, port, timeoutMillis = HTTP_STUB_SHUTDOWN_TIMEOUT, strict = strict)
}

fun createStubFromContracts(
    contractPaths: List<String>,
    dataDirPaths: List<String>,
    host: String = "localhost",
    port: Int = 9000
): ContractStub {
    return createStubFromContracts(
        contractPaths,
        dataDirPaths,
        host,
        port,
        timeoutMillis = HTTP_STUB_SHUTDOWN_TIMEOUT
    )
}

// Used by stub client code
fun createStubFromContracts(contractPaths: List<String>, host: String = "localhost", port: Int = 9000): ContractStub {
    return createStubFromContracts(
        contractPaths,
        host,
        port,
        timeoutMillis = HTTP_STUB_SHUTDOWN_TIMEOUT
    )
}

internal fun createStub(
    dataDirPaths: List<String>,
    host: String = "localhost",
    port: Int = 9000,
    timeoutMillis: Long,
    strict: Boolean = false
): ContractStub {
    return createStub(host, port, timeoutMillis, strict, null, dataDirPaths)
}

internal fun createStub(
    host: String = "localhost",
    port: Int = 9000,
    timeoutMillis: Long,
    strict: Boolean = false,
    givenConfigFileName: String? = null,
    dataDirPaths: List<String> = emptyList()
): ContractStub {
    val configFileName = givenConfigFileName ?: getConfigFilePath()
    val specmaticConfig = loadSpecmaticConfigOrDefault(configFileName)

    val stubValues = runWithTimeout(specmaticConfig.getStubStartTimeoutInMilliseconds()) {
        val workingDirectory = WorkingDirectory()
        if (File(configFileName).exists().not()) exitWithMessage(MISSING_CONFIG_FILE_MESSAGE)

        val contractStubPaths = contractStubPaths(configFileName)

        val stubs = if (dataDirPaths.isEmpty()) {
            loadContractStubsFromImplicitPaths(contractStubPaths, specmaticConfig, dataDirPaths)
        } else {
            loadContractStubsFromFiles(contractStubPaths, dataDirPaths, specmaticConfig, strict, withImplicitStubs = true)
        }
        val features = stubs.map { it.first }
        val expectations = contractInfoToHttpExpectations(stubs)

        object {
            val workingDirectory = workingDirectory
            val features = features
            val expectations = expectations
            val contractStubPaths = contractStubPaths
        }
    }

    return HttpStub(
        stubValues.features,
        stubValues.expectations,
        host,
        port,
        log = ::consoleLog,
        workingDirectory = stubValues.workingDirectory,
        specmaticConfigPath = File(configFileName).canonicalPath,
        timeoutMillis = timeoutMillis,
        strictMode = strict,
        specToStubBaseUrlMap = stubValues.contractStubPaths.specToBaseUrlMap()
    )
}

internal fun createStubFromContracts(
    contractPaths: List<String>,
    dataDirPaths: List<String>,
    host: String = "localhost",
    port: Int = 9000,
    timeoutMillis: Long,
    specmaticConfigPath: String? = null
): HttpStub {

    return createStubFromContracts(
        contractPaths,
        dataDirPaths,
        host,
        port,
        timeoutMillis,
        loadSpecmaticConfigOrDefault(specmaticConfigPath)
    )
}

internal fun createStubFromContracts(
    contractPaths: List<String>,
    dataDirPaths: List<String>,
    host: String = "localhost",
    port: Int = 9000,
    timeoutMillis: Long,
    specmaticConfig: SpecmaticConfig
): HttpStub {
    val contractPathData = contractPaths.map { ContractPathData("", it) }
    val contractInfo = loadContractStubsFromFiles(contractPathData, dataDirPaths, specmaticConfig)
    val features = contractInfo.map { it.first }
    val httpExpectations = contractInfoToHttpExpectations(contractInfo)

    return HttpStub(
        features,
        httpExpectations,
        host,
        port,
        ::consoleLog,
        specmaticConfigPath = File(getConfigFilePath()).canonicalPath,
        timeoutMillis = timeoutMillis,
        specToStubBaseUrlMap = contractPathData.specToBaseUrlMap()
    )
}

internal fun createStubFromContracts(
    contractPaths: List<String>,
    host: String = "localhost",
    port: Int = 9000,
    timeoutMillis: Long
): ContractStub {
    val defaultImplicitDirs: List<String> = implicitContractDataDirs(contractPaths)

    val completeList = if(customImplicitStubBase() != null) {
        defaultImplicitDirs.plus(implicitContractDataDirs(contractPaths, customImplicitStubBase()))
    } else
        defaultImplicitDirs

    return createStubFromContracts(contractPaths, completeList, host, port, timeoutMillis)
}

fun loadContractStubsFromImplicitPaths(
    contractPathDataList: List<ContractPathData>,
    specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
    externalDataDirPaths: List<String>,
    cachedFeatures: List<Feature> = emptyList(),
    processedInvalidSpecs: List<String> = emptyList()
): List<Pair<Feature, List<ScenarioStub>>> {
    return contractPathDataList.filter {
        it.path !in processedInvalidSpecs
    }.map { Pair(File(it.path), it) }.flatMap { (specFile, contractSource) ->
        when {
            specFile.isFile && specFile.extension in CONTRACT_EXTENSIONS -> {
                val cachedFeature = cachedFeatures.firstOrNull { it.path == specFile.path }
                if(cachedFeature == null) {
                    logger.newLine()
                    consoleLog(StringLog("Loading the spec file: $specFile${System.lineSeparator()}"))
                }
                val feature = cachedFeature ?: loadIfOpenAPISpecification(
                    contractSource,
                    specmaticConfig
                )?.second

                if(feature == null) {
                    emptyList()
                }
                else try {
                    val implicitDataDirs = implicitDirsForSpecifications(specFile)
                    val externalDataDirs = dataDirFiles(externalDataDirPaths)

                    consoleLog("")
                    val dataFiles = implicitDataDirs.flatMap { filesInDir(it).orEmpty() }
                    if(dataFiles.isEmpty()) {
                        debugLogNonExistentDataFiles(implicitDataDirs.map { it.path }.relativePaths())
                    } else {
                        consoleLog(dataFilesLogForStubScan(
                            dataFiles,
                            implicitDataDirs.map { it.path }.relativePaths()
                        ))
                    }
                    val stubData = when {
                        implicitDataDirs.any { it.isDirectory } -> {
                            implicitDataDirs.filter { it.isDirectory }.flatMap { implicitDataDir ->

                                val stubDataFiles =
                                    filesInDir(implicitDataDir)?.toList()?.sorted()?.filter { it.extension == "json" }.orEmpty()
                                logIgnoredFiles(implicitDataDir)

                                stubDataFiles.mapNotNull {
                                    try {
                                        Pair(it.path, ScenarioStub.readFromFile(it))
                                    } catch (e: Throwable) {
                                        logger.log(e, "Could not load stub file ${it.canonicalPath}...")
                                        null
                                    }
                                }
                            }
                        }

                        else -> emptyList()
                    }

                    val implicitExampleDirs = stubData.map { File(it.first) }
                    consoleDebug(featuresLogForStubScan(listOf(Pair(specFile.path, feature))))
                    logStubScanForDebugging(
                        listOf(Pair(specFile.path, feature)),
                        implicitExampleDirs,
                        implicitDataDirs.map { it.path }.relativePaths()
                    )
                    loadContractStubs(
                        features = listOf(
                            Pair(
                                specFile.path,
                                feature.overrideInlineExamples(
                                    (implicitExampleDirs + externalDataDirs).map {
                                        it.nameWithoutExtension
                                    }.toSet()
                                )
                            )
                        ),
                        stubData = stubData,
                        logIgnoredFiles = true
                    )
                } catch(e: Throwable) {
                    logger.log("Could not load ${specFile.canonicalPath}")
                    logger.log(e)
                    emptyList()
                }
            }
            specFile.isDirectory -> {
                loadContractStubsFromImplicitPaths(
                    contractPathDataList = specFile.listFiles()?.toList()?.map {
                        ContractPathData("",  it.absolutePath)
                    } ?: emptyList(),
                    specmaticConfig = specmaticConfig,
                    externalDataDirPaths = externalDataDirPaths,
                    cachedFeatures = cachedFeatures,
                    processedInvalidSpecs = processedInvalidSpecs
                )
            }
            else -> emptyList()
        }
    }
}

fun implicitDirsForSpecifications(contractPath: File) =
    listOf(implicitContractDataDir(contractPath.path)).plus(
        if (customImplicitStubBase() != null) listOf(
            implicitContractDataDir(contractPath.path, customImplicitStubBase())
        ) else emptyList()
    ).sorted()

fun hasOpenApiFileExtension(contractPath: String): Boolean =
    OPENAPI_FILE_EXTENSIONS.any { contractPath.trim().endsWith(".$it") }

private fun logIgnoredFiles(implicitDataDir: File) {
    val ignoredFiles = implicitDataDir.listFiles()?.toList()?.filter { it.extension != "json" }?.filter { it.isFile } ?: emptyList()
    if (ignoredFiles.isNotEmpty()) {
        consoleLog(StringLog("Ignoring the following files:".prependIndent(INDENT)))
        for (file in ignoredFiles) {
            consoleLog(StringLog(file.absolutePath.prependIndent("    ")))
        }
    }
}

fun loadContractStubsFromFiles(
    contractPathDataList: List<ContractPathData>,
    dataDirPaths: List<String>,
    specmaticConfig: SpecmaticConfig,
    strictMode: Boolean = false,
    withImplicitStubs: Boolean = false
): List<Pair<Feature, List<ScenarioStub>>> {
    val contactPathsString = contractPathDataList.joinToString(System.lineSeparator()) { "- ${it.path}".prependIndent(INDENT) }
    logger.newLine()
    consoleLog(StringLog("Loading the following spec files:${System.lineSeparator()}$contactPathsString${System.lineSeparator()}"))

    val invalidContractPaths = contractPathDataList.filter { File(it.path).exists().not() }.map { it.path }
    if (invalidContractPaths.isNotEmpty() && strictMode) {
        val exitMessage = "Error loading the following contracts since they do not exist:${System.lineSeparator()}${
            invalidContractPaths.joinToString(System.lineSeparator())
        }"
        throw Exception(exitMessage)
    }

    val features = contractPathDataList.mapNotNull { contractPathData ->
        loadIfOpenAPISpecification(contractPathData, specmaticConfig)
    }.overrideInlineExamplesWithSameNameFrom(dataDirFiles(dataDirPaths))

    dataDirPaths.forEach { dataDirPath ->
        val dataFiles = dataDirFiles(listOf(dataDirPath))
        consoleLog("")
        if(dataFiles.isEmpty()) {
            debugLogNonExistentDataFiles(dataDirPaths)
        } else {
            consoleLog(dataFilesLogForStubScan(dataFiles, listOf(dataDirPath).relativePaths()))
        }
    }

    val explicitStubs = loadImplicitExpectationsFromDataDirsForFeature(
        features,
        dataDirPaths,
        specmaticConfig,
        strictMode,
        contractPathDataList
    ).ifEmpty {
        logger.debug(featuresLogForStubScan(features))
        loadExpectationsForFeatures(
            features,
            dataDirPaths,
            strictMode
        )
    }
    if(withImplicitStubs.not()) return explicitStubs

    val implicitStubs = loadContractStubsFromImplicitPaths(
        contractPathDataList = contractPathDataList,
        specmaticConfig = specmaticConfig,
        externalDataDirPaths = dataDirPaths,
        cachedFeatures = features.map { it.second },
        processedInvalidSpecs = contractPathDataList.filter { isInvalidOpenAPISpecification(it.path) }.map { it.path }
    )

    return explicitStubs.plus(implicitStubs)
}

private fun debugLogNonExistentDataFiles(dataDirPaths: List<String>) {
    consoleDebug(StringLog("Skipped the non-existent example directories:${System.lineSeparator()}${dataDirPaths.withAbsolutePaths()}"))
}

fun loadExpectationsForFeatures(
    features: List<Pair<String, Feature>>,
    dataDirPaths: List<String>,
    strictMode: Boolean = false,
    dirsToBeSkipped: Set<String> = emptySet()
): List<Pair<Feature, List<ScenarioStub>>> {
    val dataFiles = dataDirFiles(dataDirPaths, dirsToBeSkipped)
    logStubScanForDebugging(features, dataFiles, dataDirPaths)

    val mockData = dataFiles.mapNotNull {
        try {
            Pair(it.path, ScenarioStub.readFromFile(it))
        } catch (e: Throwable) {
            logger.log(e, "    Could not load stub file ${it.canonicalPath}")
            null
        }
    }

    return loadContractStubs(features, mockData, strictMode)
}

private fun List<String>.withAbsolutePaths(): String {
    return this.joinToString(System.lineSeparator()) { "- $it (absolute path ${File(it).canonicalPath})".prependIndent(INDENT) }
}

private fun List<String>.relativePaths(): List<String> {
    return this.map {
        File(it).canonicalFile.relativeTo(File(".").canonicalFile).path
    }.map { ".${File.separator}$it" }
}

private fun  List<Pair<String, Feature>>.overrideInlineExamplesWithSameNameFrom(dataFiles: List<File>): List<Pair<String, Feature>> {
    val externalExampleNames = dataFiles.map { it.nameWithoutExtension }.toSet()
    return this.map {
        val (_, feature) = it
        it.copy(
            second = feature.overrideInlineExamples(externalExampleNames)
        )
    }
}

private fun dataDirFiles(
    dataDirPaths: List<String>,
    dirsToBeSkipped: Set<String> = emptySet()
): List<File> {
    return allDirsInTree(dataDirPaths).filter {
        it.path !in dirsToBeSkipped
    }.flatMap {
        logIgnoredFiles(it)
        it.listFiles()?.toList()?.sorted() ?: emptyList<File>()
    }.filter { it.extension == "json" }
}

fun loadImplicitExpectationsFromDataDirsForFeature(
    features: List<Pair<String, Feature>>,
    dataDirPaths: List<String>,
    specmaticConfig: SpecmaticConfig,
    strictMode: Boolean = false,
    contractPathDataList: List<ContractPathData> = emptyList()
): List<Pair<Feature, List<ScenarioStub>>> {
    return specPathToImplicitDataDirPaths(specmaticConfig, dataDirPaths, contractPathDataList).flatMap { (specPath, implicitOriginalDataDirPairList) ->
        val associatedFeature = features.firstOrNull { (specPathAssociatedToFeature, _) ->
            File(specPathAssociatedToFeature).canonicalPath == File(specPath).canonicalPath
        }

        if(associatedFeature == null) {
            logger.debug("Skipping the loading of examples for '$specPath' as it is not a OpenAPI specification")
            return@flatMap emptyList()
        }
        logger.debug(featuresLogForStubScan(listOf(associatedFeature)))

        implicitOriginalDataDirPairList.flatMap { (implicitDataDir, originalDataDir) ->
            val implicitStubs = loadExpectationsForFeatures(
                features = listOf(associatedFeature),
                dataDirPaths = listOf(implicitDataDir),
                strictMode = strictMode
            )
            if(implicitStubs.all { (_, stubs) -> stubs.isEmpty() }) {
                loadExpectationsForFeatures(
                    features = listOf(associatedFeature),
                    dataDirPaths = listOf(originalDataDir),
                    strictMode = strictMode,
                    dirsToBeSkipped = setOf(implicitDataDir)
                )
            } else {
                implicitStubs
            }
        }
    }
}

private fun specPathToImplicitDataDirPaths(
    specmaticConfig: SpecmaticConfig,
    dataDirPaths: List<String>,
    contractPathDataList: List<ContractPathData>
): List<Pair<String, List<ImplicitOriginalDataDirPair>>> {
    return specmaticConfig.loadSources().flatMap { contractSource ->
        contractSource.stubDirectoryToContractPath(contractPathDataList)
    }.mapNotNull { (stubDirectory, stubContractPath) ->
        if (stubContractPath.isContractFile().not()) {
            return@mapNotNull null
        }

        val implicitExamplesPath = stubContractPath.substringBeforeLast(".").plus("_examples")

        val stubContractPathWithDirectory =
            if (stubDirectory.isNotBlank()) "$stubDirectory${File.separator}$stubContractPath" else stubContractPath
        stubContractPathWithDirectory to dataDirPaths.map { dataDirPath ->
            ImplicitOriginalDataDirPair(
                implicitDataDir = "$dataDirPath${File.separator}$implicitExamplesPath",
                dataDir = dataDirPath
            )
        }
    }
}

data class ImplicitOriginalDataDirPair(
    val implicitDataDir: String,
    val dataDir: String
)

private fun logStubScanForDebugging(
    features: List<Pair<String, *>>,
    dataFiles: List<File>,
    dataDirPaths: List<String>
) {
    if (dataFiles.isNotEmpty()) {
        consoleDebug(dataFilesLogForStubScan(dataFiles, dataDirPaths.relativePaths(), true))
    } else {
        val existingDataFiles = dataFiles.groupBy { it.exists() }
        if (existingDataFiles.isNotEmpty()) {
            logger.debug(featuresLogForStubScan(features))
            logger.debug(System.lineSeparator())
            logger.debug(
                "${
                    "Skipped example loading since no example directories/files found within:".prependIndent(
                        INDENT
                    )
                }${System.lineSeparator()}${dataFiles.map { it.canonicalPath }.withAbsolutePaths()}"
            )
        }

        val nonExistentDataFiles = dataFiles.groupBy { it.exists().not() }
        if (nonExistentDataFiles.isNotEmpty()) {
            logger.debug(featuresLogForStubScan(features))
            logger.debug(System.lineSeparator())
            logger.debug(
                "${"Skipped example loading since the example directories do not exist:".prependIndent(INDENT)}${System.lineSeparator()}${
                    dataFiles.map { it.canonicalPath }.withAbsolutePaths()
                }"
            )
        }
    }
}

private fun featuresLogForStubScan(features: List<Pair<String, *>>): String {
    if(features.size == 1) {
        val feature = features.single()
        return buildString {
            append(System.lineSeparator())
            append("Scanning for stub expectations for '${feature.first}'".prependIndent(" "))
        }
    }
    return buildString {
        append(System.lineSeparator())
        append("Scanning for stub expectations for specs:".prependIndent(" "))
        append(System.lineSeparator())
        append(features.joinToString(System.lineSeparator()) { "- ${it.first}".prependIndent(INDENT) })
    }
}

private fun dataFilesLogForStubScan(
    dataFiles: List<File>,
    dataDirPaths: List<String>,
    debugMode: Boolean = false
): StringLog {
    if(dataFiles.isEmpty()) {
        return StringLog("")
    }

    val dataFilesString = dataFiles.joinToString(System.lineSeparator()) { file ->
        "- ${file.canonicalPath}".prependIndent(INDENT)
    }

    return StringLog(buildString {
        val messagePrefix = if(debugMode) "Scanning example files from" else "Example files in"
        append("$messagePrefix '${dataDirPaths.joinToString(", ")}'".prependIndent(" "))
        append(System.lineSeparator())
        append(dataFilesString)
    })
}

class StubMatchExceptionReport(val request: HttpRequest, val e: NoMatchingScenario) {
    fun withoutFluff(fluffLevel: Int): StubMatchExceptionReport {
        return StubMatchExceptionReport(request, e.withoutFluff(fluffLevel))
    }

    fun hasErrors(): Boolean {
        return e.results.hasResults()
    }

    val message: String
        get() = e.report(request)
}

data class StubMatchErrorReport(val exceptionReport: StubMatchExceptionReport, val contractFilePath: String) {
    fun withoutFluff(fluffLevel: Int): StubMatchErrorReport {
        return this.copy(exceptionReport = exceptionReport.withoutFluff(fluffLevel))
    }

    fun hasErrors(): Boolean {
        return exceptionReport.hasErrors()
    }
}
data class StubMatchResults(val feature: Feature?, val errorReport: StubMatchErrorReport?)

fun stubMatchErrorMessage(
    matchResults: List<StubMatchResults>,
    stubFile: String,
    specs: List<String> = emptyList()
): String {
    val errorReports = stubMatchErrorReports(matchResults)

    if(errorReports.isEmpty() || matchResults.isEmpty())
        return stubMatchErrorMessageForEmpty(matchResults, stubFile, specs)

    return stubMatchErrorMessageForNonEmpty(errorReports, stubFile)
}

private fun stubMatchErrorMessageForEmpty(
    matchResults: List<StubMatchResults>,
    stubFile: String,
    specs: List<String>
): String {
    return "$stubFile didn't match any of the contracts from ${specs.joinToString(", ")}\n${matchResults.firstOrNull()?.errorReport?.exceptionReport?.request?.requestNotRecognized()?.prependIndent(INDENT)}".trim()
}

private fun stubMatchErrorMessageForNonEmpty(
    errorReports: List<StubMatchErrorReport>,
    stubFile: String
): String {
    return errorReports.joinToString("${System.lineSeparator()}${System.lineSeparator()}") { (exceptionReport, contractFilePath) ->
        "$stubFile didn't match $contractFilePath${System.lineSeparator()}${
            exceptionReport.message.prependIndent(
                "  "
            )
        }"
    }
}

private fun stubMatchErrorReports(matchResults: List<StubMatchResults>): List<StubMatchErrorReport> {
    val matchResultsWithErrorReports = matchResults.mapNotNull { it.errorReport }

    val errorReports: List<StubMatchErrorReport> = matchResultsWithErrorReports.map {
        it.withoutFluff(0)
    }.filter {
        it.hasErrors()
    }.ifEmpty {
        matchResultsWithErrorReports.map {
            it.withoutFluff(1)
        }.filter {
            it.hasErrors()
        }
    }
    return errorReports
}

fun loadContractStubs(
    features: List<Pair<String, Feature>>,
    stubData: List<Pair<String, ScenarioStub>>,
    strictMode: Boolean = false,
    logIgnoredFiles: Boolean = false
): List<Pair<Feature, List<ScenarioStub>>> {
    val contractInfoFromStubs: List<Pair<Feature, List<ScenarioStub>>> = stubData.flatMap { (stubFile, stub) ->
        val matchResults = features.map { (specFile, feature) ->
            try {
                feature.matchingStub(stub, ContractAndStubMismatchMessages)
                StubMatchResults(feature, null)
            } catch (e: NoMatchingScenario) {
                StubMatchResults(null, StubMatchErrorReport(StubMatchExceptionReport(stub.partial?.request ?: stub.request, e), specFile))
            }
        }

        if(matchResults.all { it.feature == null }) {
            val specs = features.map { it.first }
            val errorReports = stubMatchErrorReports(matchResults)

            if (strictMode) throw Exception(stubMatchErrorMessage(matchResults, stubFile, specs))
            else {
                if (logIgnoredFiles) {
                    val errorMessage = stubMatchErrorMessage(matchResults, stubFile, specs)
                    val message = ">> Error loading stub expectation file '${stubFile}':${System.lineSeparator()} $errorMessage"
                    logger.newLine()
                    logger.log(message.prependIndent(INDENT))
                    logger.newLine()
                }
                else {
                    logPartialErrorMessages(errorReports, stubFile, matchResults, specs)
                }
            }
            return@flatMap emptyList()
        }

        matchResults.mapNotNull { matchResult ->
            if (matchResult.feature == null) {
                null
            } else {
                logger.debug("Successfully loaded the stub expectation from '${stub.filePath.orEmpty()}".prependIndent(" "))
                Pair(matchResult.feature, stub)
            }
        }
    }.groupBy { it.first }.mapValues { (_, value) -> value.map { it.second } }.entries.map { Pair(it.key, it.value) }

    val stubbedFeatures = contractInfoFromStubs.map { it.first }
    val missingFeatures = features.map { it.second }.filter { it !in stubbedFeatures }

    return contractInfoFromStubs.plus(missingFeatures.map { Pair(it, emptyList()) })
}

private fun logPartialErrorMessages(
    errorReports: List<StubMatchErrorReport>,
    stubFile: String,
    matchResults: List<StubMatchResults>,
    specs: List<String>
) {
    val errorMessage = stubMatchErrorMessageForNonEmpty(errorReports, stubFile).takeIf { errorReports.isNotEmpty() }
    if (errorMessage != null) {
        val errorMessagePrefix = ">> Error loading stub expectation file '${stubFile}':".prependIndent(INDENT)
        val message = "$errorMessagePrefix${System.lineSeparator()}${errorMessage.prependIndent(INDENT.plus(" "))}"
        logger.newLine()
        logger.log(message)
        logger.newLine()
    }
    if (matchResults.isEmpty() || errorReports.isEmpty()) {
        val errorMessageForDebugLog = "Skipped loading the stub expectation from '${stubFile}' as it didn't match the spec(s) '${specs.joinToString(", ")}'".prependIndent(INDENT)
        logger.debug(errorMessageForDebugLog)
    }
}

fun allDirsInTree(
    dataDirPaths: List<String>
): List<File> =
        dataDirPaths.map { File(it) }.filter {
            it.exists() && it.isDirectory
        }.flatMap {
            val fileList: List<File> = it.listFiles()?.toList()?.filterNotNull() ?: emptyList()
            pathToFileListRecursive(fileList).plus(it)
        }

private fun pathToFileListRecursive(
    dataDirFiles: List<File>
): List<File> =
        dataDirFiles.filter {
            it.isDirectory
        }.map {
            val fileList: List<File> = it.listFiles()?.toList()?.filterNotNull() ?: emptyList()
            pathToFileListRecursive(fileList).plus(it)
        }.flatten()

private fun filesInDir(implicitDataDir: File): List<File>? {
    val files = implicitDataDir.listFiles()?.map {
        when {
            it.isDirectory -> {
                filesInDir(it) ?: emptyList()
            }
            it.isFile -> {
                listOf(it)
            }
            else -> {
                logger.debug("Could not recognise ${it.absolutePath}, ignoring it.")
                emptyList()
            }
        }
    }

    return files?.flatten()
}


fun implicitContractDataDirs(contractPaths: List<String>, customBase: String? = null) =
        contractPaths.map { implicitContractDataDir(it, customBase).absolutePath }

fun customImplicitStubBase(): String? = System.getenv("SPECMATIC_CUSTOM_IMPLICIT_STUB_BASE") ?: System.getProperty("customImplicitStubBase")

fun implicitContractDataDir(contractPath: String, customBase: String? = null): File {
    val contractFile = File(contractPath)

    return if(customBase == null)
        examplesDirFor("${contractFile.absoluteFile.parent}/${contractFile.name}", DATA_DIR_SUFFIX)
    else {
        val gitRoot: String = File(SystemGit().inGitRootOf(contractPath).workingDirectory).canonicalPath
        val fullContractPath = File(contractPath).canonicalPath

        val relativeContractPath = File(fullContractPath).relativeTo(File(gitRoot))
        File(gitRoot).resolve(customBase).resolve(relativeContractPath).let {
            examplesDirFor("${it.parent}/${it.name}", DATA_DIR_SUFFIX)
        }
    }
}

fun loadIfOpenAPISpecification(contractPathData: ContractPathData, specmaticConfig: SpecmaticConfig): Pair<String, Feature>? {
    if(!File(contractPathData.path).exists()) {
        logger.log("Skipping the file '${contractPathData.path}' as it does not exist")
        return null
    }

    return try {
        Pair(contractPathData.path, parseContractFileToFeature(contractPathData.path, CommandHook(HookName.stub_load_contract), contractPathData.provider, contractPathData.repository, contractPathData.branch, contractPathData.specificationPath).copy(specmaticConfig = specmaticConfig))
    } catch (e: Throwable) {
        logger.log(exceptionCauseMessage(e))
        null
    }
}

fun isInvalidOpenAPISpecification(specPath: String): Boolean {
    return hasOpenApiFileExtension(specPath).not() || isOpenAPI(specPath).not()
}

fun isOpenAPI(path: String): Boolean =
    try {
        Yaml().load<MutableMap<String, Any?>>(File(path).reader()).contains("openapi")
    } catch(e: Throwable) {
        logger.log(e, "Could not parse $path")
        false
    }
