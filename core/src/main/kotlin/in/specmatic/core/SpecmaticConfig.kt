package `in`.specmatic.core

import `in`.specmatic.core.Configuration.Companion.globalConfigFileName
import `in`.specmatic.core.pattern.ContractException
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.serialization.Serializable

const val APPLICATION_NAME = "Specmatic"
const val APPLICATION_NAME_LOWER_CASE = "specmatic"
const val APPLICATION_NAME_LOWER_CASE_LEGACY = "qontract"
const val CONTRACT_EXTENSION = "spec"
const val LEGACY_CONTRACT_EXTENSION = "qontract"
val CONTRACT_EXTENSIONS = listOf(CONTRACT_EXTENSION, LEGACY_CONTRACT_EXTENSION, "yaml", "wsdl")
const val DATA_DIR_SUFFIX = "_data"
const val SPECMATIC_GITHUB_ISSUES = "https://github.com/znsio/specmatic/issues"
const val DEFAULT_WORKING_DIRECTORY = ".$APPLICATION_NAME_LOWER_CASE"

class WorkingDirectory(private val filePath: File, private val existsBefore: Boolean) {
    constructor(path: File): this(path, path.exists())
    constructor(path: String = DEFAULT_WORKING_DIRECTORY): this(File(path))

    val path: String
        get() {
            return filePath.path
        }

    fun delete() {
        if(!existsBefore)
            filePath.deleteRecursively()
    }
}

fun invalidContractExtensionMessage(filename: String): String {
    return "The file $filename does not seem like a contract file. Valid extensions for contract files are ${CONTRACT_EXTENSIONS.joinToString(", ")}"
}

fun String.isContractFile(): Boolean {
    return File(this).extension in CONTRACT_EXTENSIONS
}

fun String.loadContract(): Feature {
    if(!this.isContractFile())
        throw ContractException(invalidContractExtensionMessage(this))

    return parseContractFileToFeature(File(this))
}

@Serializable
data class Auth(@SerialName("bearer-file") val bearerFile: String = "bearer.txt", @SerialName("bearer-environment-variable") val bearerEnvironmentVariable: String? = null)

enum class PipelineProvider { azure }

@Serializable
data class Pipeline(
    val provider: PipelineProvider,
    val organization: String,
    val project: String,
    val definitionId: Int
)

@Serializable
data class Environment(
    val baseurls: Map<String, String>? = null,
    val variables: Map<String, String>? = null
)

enum class SourceProvider { git }

@Serializable
data class Source(
    val provider: SourceProvider,
    val repository: String? = null,
    val branch: String? = null,
    val test: List<String>? = null,
    val stub: List<String>? = null
)

@Serializable
data class SpecmaticConfigJson(
    val sources: List<Source>,
    val auth: Auth? = null,
    val pipeline: Pipeline? = null,
    val environments: Map<String, Environment>? = null,
    val hooks: Map<String, String> = emptyMap(),
    val repository: RepositoryInfo? = null,
    val report: ReportConfiguration? = null
) {
}

@Serializable
data class RepositoryInfo(
    val provider: String,
    val collectionName: String
)

@Serializable
data class ReportConfiguration(
    val formatters: List<ReportFormatter>? = null,
    val types: ReportTypes
)

@Serializable
data class ReportFormatter(
    val type: ReportFormatterType,
    val layout: ReportFormatterLayout
)

@Serializable
enum class ReportFormatterType {
    @SerialName("text")
    TEXT
}

@Serializable
enum class ReportFormatterLayout {
    @SerialName("table")
    TABLE
}

@Serializable
data class ReportTypes (
    @SerialName("APICoverage")
    val apiCoverage: APICoverage
)

@Serializable
data class APICoverage (
    @SerialName("OpenAPI")
    val openAPI: APICoverageConfiguration
)

@Serializable
data class APICoverageConfiguration(
    val successCriteria: SuccessCriteria,
    val excludedEndpoints: List<String> = emptyList()
)

@Serializable
data class SuccessCriteria(
    val minThresholdPercentage: Int,
    val maxMissedEndpointsInSpec: Int,
    val enforce: Boolean = false
)

val SpecmaticJsonFormat = Json {
    prettyPrint = true
}

fun loadSpecmaticJsonConfig(configFileName: String? = null): SpecmaticConfigJson {
    val configFile = File(configFileName ?: globalConfigFileName)
    if (!configFile.exists()) {
        throw ContractException("Could not find ${Configuration.DEFAULT_CONFIG_FILE_NAME} at path ${configFile.absolutePath}")
    }
    try {
        return SpecmaticJsonFormat.decodeFromString(configFile.readText())
    } catch (e: Throwable) {
        throw ContractException("Your specmatic.json file may have some missing configuration sections. Please ensure that the specmatic.json fie adheres to the schema described at: https://specmatic.in/documentation/specmatic_json.html#complete-sample-specmaticjson-with-all-attributes")
    }
}
