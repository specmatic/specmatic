package io.specmatic.core

import com.fasterxml.jackson.databind.DatabindException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.exc.IgnoredPropertyException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.InvalidNullException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import io.specmatic.core.Configuration.Companion.configFilePath
import io.specmatic.core.config.Auth
import io.specmatic.core.config.BackwardCompatibilityConfig
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.McpConfiguration
import io.specmatic.core.config.ReportConfiguration
import io.specmatic.core.config.ResiliencyTestSuite
import io.specmatic.core.config.SecurityConfiguration
import io.specmatic.core.config.SecuritySchemeConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticGlobalSettings
import io.specmatic.core.config.StubConfiguration
import io.specmatic.core.config.Switch
import io.specmatic.core.config.TestConfiguration
import io.specmatic.core.config.VirtualServiceConfiguration
import io.specmatic.core.config.WorkflowConfiguration
import io.specmatic.core.config.WorkflowDetails
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.core.config.v2.AttributeSelectionPatternDetails
import io.specmatic.core.config.v2.SpecmaticConfigV2Impl
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.ContractSource
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.model.SpecType
import java.io.File
import java.lang.Number
import java.nio.file.Path
import kotlin.collections.contains
import kotlin.collections.joinToString
import kotlin.collections.orEmpty

const val APPLICATION_NAME = "Specmatic"
const val APPLICATION_NAME_LOWER_CASE = "specmatic"
const val CONFIG_FILE_NAME_WITHOUT_EXT = "specmatic"
const val DEFAULT_TIMEOUT_IN_MILLISECONDS: Long = 6000L
const val CONTRACT_EXTENSION = "spec"
const val YAML = "yaml"
const val WSDL = "wsdl"
const val YML = "yml"
const val JSON = "json"
val CONFIG_EXTENSIONS = listOf(YAML, YML, JSON)
val OPENAPI_FILE_EXTENSIONS = listOf(YAML, YML, JSON)
val CONTRACT_EXTENSIONS = listOf(CONTRACT_EXTENSION, WSDL) + OPENAPI_FILE_EXTENSIONS
const val DATA_DIR_SUFFIX = "_data"
const val TEST_DIR_SUFFIX = "_tests"
const val EXAMPLES_DIR_SUFFIX = "_examples"
const val SPECMATIC_GITHUB_ISSUES = "https://github.com/specmatic/specmatic/issues"
const val DEFAULT_WORKING_DIRECTORY = ".$APPLICATION_NAME_LOWER_CASE"
const val SPECMATIC_DISABLE_TELEMETRY = "SPECMATIC_DISABLE_TELEMETRY"
const val MISSING_CONFIG_FILE_MESSAGE = "Config file does not exist. (Could not find file ./specmatic.json OR ./specmatic.yaml OR ./specmatic.yml)"

interface SpecmaticConfig {
    fun getVersion(): SpecmaticConfigVersion
    fun loadSources(useCurrentBranchForCentralRepo: Boolean = false): List<ContractSource>

    /* -------- Logging / telemetry -------- */
    fun getLogConfigurationOrDefault(): LoggingConfiguration
    fun isTelemetryDisabled(): Boolean

    /* -------- Test / Stub configs -------- */
    fun getTestConfiguration(specFile: File, specType: SpecType): TestConfiguration?
    fun getStubConfiguration(specFile: File, specType: SpecType): StubConfiguration?
    fun testConfigFor(file: File, specType: SpecType): Map<String, Any>
    fun stubConfigFor(file: File, specType: SpecType): Map<String, Any>
    fun getVirtualServiceConfiguration(): VirtualServiceConfiguration?

    /* -------- Common getters -------- */
    fun getCtrfSpecConfig(absoluteSpecPath: String, testType: String, key: String, value: String): CtrfSpecConfig?
    fun isExtensibleSchemaEnabledForTest(specFile: File, specType: SpecType): Boolean
    fun isExtensibleSchemaEnabledForMock(specFile: File, specType: SpecType): Boolean
    fun getAttributeSelectionPatternDetails(): AttributeSelectionPatternDetails
    fun parsedDefaultPatternValues(): Map<String, Value>
    fun getMatchBranchEnabled(): Boolean

    /* -------- Feature flags -------- */
    fun getIgnoreInlineExamples(): Boolean
    fun getIgnoreInlineExampleWarnings(): Boolean
    fun getAllPatternsMandatory(): Boolean
    fun getSchemaExampleDefault(): Boolean
    fun getFuzzyMatchingEnabled(): Boolean
    fun getExtensibleQueryParams(): Boolean
    fun getEscapeSoapAction(): Boolean
    fun getPrettyPrint(): Boolean

    /* -------- Test execution -------- */
    fun getWorkflowConfiguration(): WorkflowConfiguration?
    fun getWorkflowDetails(): WorkflowDetails?
    fun getTestBaseUrl(): String?
    fun getCoverageReportBaseUrl(): String?
    fun getTestTimeoutInMilliseconds(): Long?
    fun getMaxTestRequestCombinations(): Int?
    fun getTestStrictMode(): Boolean?
    fun getTestLenientMode(): Boolean?
    fun getTestParallelism(): String?
    fun getTestsDirectory(): String?
    fun getMaxTestCount(): Int?
    fun getTestFilter(): String?
    fun getTestFilterName(): String?
    fun getTestFilterNotName(): String?
    fun getTestOverlayFilePath(): String?
    fun isResponseValueValidationEnabled(): Boolean
    fun getAdditionalExampleParamsFilePath(): File?
    fun getResiliencyTestsEnabled(): ResiliencyTestSuite
    fun isResiliencyTestingEnabled(): Boolean
    fun isOnlyPositiveTestingEnabled(): Boolean
    fun getTestSwaggerUIBaseUrl(): String?
    fun getTestSwaggerUrl(): String?
    fun getActuatorUrl(): String?
    fun getSecurityConfiguration(): SecurityConfiguration?

    /* -------- Stub execution -------- */
    fun getHotReload(): Switch?
    fun getDefaultBaseUrl(): String
    fun getStubDelayInMilliseconds(): Long?
    fun getStubStrictMode(): Boolean?
    fun getStubFilter(): String?
    fun getStubGenerative(): Boolean
    fun getStubDictionary(): String?
    fun getStubHttpsConfiguration(): HttpsConfiguration?
    fun getStubGracefulRestartTimeoutInMilliseconds(): Long?
    fun getStubIncludeMandatoryAndRequestedKeysInResponse(): Boolean
    fun getStubStartTimeoutInMilliseconds(): Long
    fun getCustomImplicitStubBase(): String?
    fun stubToBaseUrlList(defaultBaseUrl: String): List<Pair<String, String>>
    fun stubBaseUrlPathAssociatedTo(url: String, defaultBaseUrl: String): String

    /* -------- Security / auth -------- */
    fun getAuth(): Auth?
    fun getAuthBearerFile(): String?
    fun getAuthBearerEnvironmentVariable(): String?
    fun getAuthPersonalAccessToken(): String?

    fun getOpenAPISecurityConfigurationScheme(scheme: String): SecuritySchemeConfiguration?
    fun getBasicAuthSecurityToken(schemeName: String, securitySchemeConfiguration: SecuritySchemeConfiguration?): String?
    fun getBearerSecurityToken(schemeName: String, securitySchemeConfiguration: SecuritySchemeConfiguration?): String?
    fun getApiKeySecurityToken(schemeName: String, securitySchemeConfiguration: SecuritySchemeConfiguration?): String?

    /* -------- Reports -------- */
    fun getTestJunitReportDir(): String?
    fun getReport(): ReportConfiguration?
    fun getReportDirPath(suffix: String? = null): Path

    /* -------- Misc -------- */
    fun getExamples(): List<String>
    fun getEnvironment(envName: String): JSONObjectValue
    fun getGlobalSettingsOrDefault(): SpecmaticGlobalSettings
    fun getHooks(): Map<String, String>
    fun getProxyConfig(): ProxyConfig?
    fun getBackwardCompatibilityConfig(): BackwardCompatibilityConfig?
    fun getMcpConfiguration(): McpConfiguration?
    fun getLicensePath(): Path?

    /* -------- Modifiers -------*/
    fun copyResiliencyTestsConfig(onlyPositive: Boolean): SpecmaticConfig
    fun dropExcludedEndpointsAfterVersion1(version: SpecmaticConfigVersion): SpecmaticConfig
    fun updateReportConfiguration(reportConfiguration: ReportConfiguration): SpecmaticConfig
    fun withTestFilter(filter: String): SpecmaticConfig
    fun enableResiliencyTests(): SpecmaticConfig
    fun withTestModes(strictMode: Boolean?, lenientMode: Boolean): SpecmaticConfig
    fun withExamples(exampleDirectories: List<String>): SpecmaticConfig
    fun withTestTimeout(timeoutInMilliSeconds: Long): SpecmaticConfig
    fun withUseCurrentBranchForCentralRepo(useCurrentBranchForCentralRepo: Boolean): SpecmaticConfig
    fun withStubModes(strictMode: Boolean): SpecmaticConfig
    fun withStubFilter(filter: String): SpecmaticConfig
    fun withGlobalMockDelay(delay: Long): SpecmaticConfig

    companion object {
        fun default(): SpecmaticConfig = SpecmaticConfigV2Impl()
        fun SpecmaticConfig?.orDefault() = this ?: default()
    }
}

fun loadSpecmaticConfigOrDefault(configFileName: String? = null): SpecmaticConfig {
    return loadSpecmaticConfigOrNull(configFileName) ?: SpecmaticConfigV2Impl()
}

fun loadSpecmaticConfigOrNull(configFileName: String? = null, explicitlySpecifiedByUser: Boolean = false): SpecmaticConfig? {
    if (configFileName == null) return SpecmaticConfigV2Impl()
    return try { loadSpecmaticConfig(configFileName) } catch (e: ContractException) {
        val message = exceptionCauseMessage(e)
        val configFile = File(configFileName)
        if (!configFile.exists() && !explicitlySpecifiedByUser) {
            logger.debug(message)
        } else {
            logger.log(message)
        }
        null
    }
}

fun loadSpecmaticConfig(configFileName: String? = null): SpecmaticConfig {
    val configFile = File(configFileName ?: configFilePath)

    if (!configFile.exists()) {
        throw ContractException("Could not find the Specmatic configuration at path ${configFile.canonicalPath}")
    }

    try {
        return configFile.toSpecmaticConfig()
    } catch(e: DatabindException) {
        throw Exception(configErrorMessage(e))
    } catch (e: Throwable) {
        throw Exception("Error parsing config: ${e.message}")
    }
}

fun loadSpecmaticConfigIfAvailableElseDefault(configFileName: String? = null): SpecmaticConfig {
    val configFile = File(configFileName ?: configFilePath).canonicalFile
    return loadSpecmaticConfigOrDefault(configFile.canonicalPath)
}

fun configErrorMessage(e: DatabindException): String {
    val location = if(e.location?.lineNr != null && e.location?.columnNr != null) {
        " (line ${e.location.lineNr}, column ${e.location.columnNr})"
    } else {
        ""
    }

    val errorPrefix = "Error parsing config$location"
    return when (e) {
        is InvalidNullException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            "$errorPrefix: $fieldPath must not be null, but found null."
        }

        is InvalidFormatException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            val actualValueClause = e.value?.let { " ($it is invalid)" }
            "$errorPrefix: $fieldPath accepts ${expectedType(e)}$actualValueClause"
        }

        is IgnoredPropertyException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            "$errorPrefix: $fieldPath is not a valid property in the configuration file."
        }

        is UnrecognizedPropertyException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            val knownProperties = e.knownPropertyIds.sortedBy { it.toString() }.joinToString(", ")
            "$errorPrefix: $fieldPath is not a valid property in the configuration file. Known properties are: $knownProperties."
        }

        is MismatchedInputException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            "$errorPrefix: $fieldPath accepts ${expectedType(e)}"
        }

        is JsonMappingException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            "$errorPrefix at $fieldPath: ${e.originalMessage}"
        }

        else -> {
            "$errorPrefix: ${e.originalMessage}"
        }
    }
}

private fun expectedType(e: MismatchedInputException): String {
    val targetType = e.targetType ?: return "an object"
    return if (targetType.isEnum) {
        "one of the following: ${targetType.enumConstants.orEmpty().joinToString(", ") { it.toString() }}"
    } else if (targetType.isPrimitive) {
        targetType.simpleName.orEmpty().lowercase()
    } else if(targetType.genericSuperclass in listOf(Number::class.java, java.lang.String::class.java, java.lang.Boolean::class.java)) {
        targetType.simpleName.orEmpty().lowercase()
    } else if (targetType.simpleName == "ArrayList") {
        "a list"
    } else {
        "an object"
    }
}

private fun readablePath(path: MutableList<JsonMappingException.Reference>): String {
    return path.joinToString(".") { it.fieldName ?: "[${it.index}]" }.replace(".[", "[")
}
