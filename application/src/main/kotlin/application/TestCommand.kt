package application

import io.specmatic.core.*
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.LoggingConfiguration.Companion.LoggingFromOpts
import io.specmatic.core.log.LoggingConfigSource
import io.specmatic.core.log.configureLogging
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.exitWithMessage
import io.specmatic.core.utilities.newXMLBuilder
import io.specmatic.core.utilities.xmlToString
import io.specmatic.license.core.cli.Category
import io.specmatic.test.ContractTestSettings
import io.specmatic.test.DeprecatedArguments
import io.specmatic.test.SpecmaticJUnitSupport
import io.specmatic.test.listeners.ContractExecutionListener
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.io.PrintWriter
import java.io.StringReader
import java.nio.file.Paths
import java.util.concurrent.Callable

private const val SYSTEM_OUT_TESTCASE_TAG = "system-out"

private const val DISPLAY_NAME_PREFIX_IN_SYSTEM_OUT_TAG_TEXT = "display-name: "

@Command(name = "test", mixinStandardHelpOptions = true, description = ["Run contract tests"])
@Category("Specmatic core")
class TestCommand(private val junitLauncher: Launcher = LauncherFactory.create()) : Callable<Int> {
    @CommandLine.Parameters(arity = "0..*", description = ["Contract file paths"])
    var contractPaths: List<String>? = null

    @Option(names = ["--host"], description = ["The host to bind to, e.g. localhost or some locally bound IP"], defaultValue = "localhost")
    lateinit var host: String

    @Option(names = ["--port"], description = ["The port to bind to"])
    var port: Int = 0

    @Option(names = ["--testBaseURL"], description = ["The base URL, use this instead of host and port"])
    var testBaseURL: String? = null

    @Option(names = ["--suggestionsPath"], description = ["Location of the suggestions file"], defaultValue = "", hidden = true)
    var suggestionsPath: String? = null

    @Option(names = ["--suggestions"], description = ["A json value with scenario name and multiple suggestions"], defaultValue = "", hidden = true)
    var suggestions: String? = null

    @Option(names = ["--filter-name"], description = ["Run only tests with this value in their name"], defaultValue = "\${env:SPECMATIC_FILTER_NAME}", hidden = true)
    var filterName: String? = null

    @Option(names = ["--filter-not-name"], description = ["Run only tests which do not have this value in their name"], defaultValue = "\${env:SPECMATIC_FILTER_NOT_NAME}", hidden = true)
    var filterNotName: String? = null

    @Option(
        names= ["--filter"],
        description = [
            """Filter tests matching the specified filtering criteria

You can filter tests based on the following keys:
- `METHOD`: HTTP methods (e.g., GET, POST)
- `PATH`: Request paths (e.g., /users, /product)
- `STATUS`: HTTP response status codes (e.g., 200, 400)

You can find all available filters and their usage at:
https://docs.specmatic.io/documentation/contract_tests.html#supported-filters--operators"""
        ],
        required = false
    )
    var filter: String? = null

    @Option(names = ["--env"], description = ["Environment name"], hidden = true)
    var envName: String? = null

    @Option(names = ["--https"], description = ["Use https instead of the default http"], required = false)
    var useHttps: Boolean = false

    @Option(names = ["--timeout"], description = ["Specify a timeout in seconds for the test requests. Default value is ${DEFAULT_TIMEOUT_IN_MILLISECONDS / 1000}"], required = false)
    var timeout: Long? = null

    @Option(names = ["--timeout-in-ms"], description = ["Specify a timeout in milliseconds for the test requests, Defaults to $DEFAULT_TIMEOUT_IN_MILLISECONDS"], required = false)
    var timeoutInMs: Long? = null

    @Option(names = ["--junitReportDir"], description = ["Create junit xml reports in this directory"])
    var junitReportDirName: String? = null

    @Option(names = ["--config"], description = ["Configuration file name ($APPLICATION_NAME_LOWER_CASE.json by default)"])
    var configFileName: String? = null

    @Option(names = ["--variables"], description = ["Variables file name ($APPLICATION_NAME_LOWER_CASE.json by default)"], hidden = true)
    var variablesFileName: String? = null

    @Option(names = ["--debug"], description = ["Debug logs"])
    var verboseMode: Boolean? = null

    @Option(names = ["--examples"], description = ["Directories containing JSON examples"], required = false)
    var exampleDirs: List<String> = mutableListOf()

    @Option(names = ["--overlay-file"], description = ["Overlay file for the specification"], required = false)
    var overlayFilePath: String? = null

    @Option(
        names = ["--strict"],
        description = ["If true, positive and negative tests will not be generated for endpoints without examples"],
        required = false
    )
    var strictMode: Boolean? = null

    @Option(
        names = ["--match-branch"],
        description = ["Use the current branch name for contract source branch when not on default branch"],
        required = false
    )
    var useCurrentBranchForCentralRepo: Boolean? = null

    @Option(names = ["--lenient"], description = ["Parse the OpenAPI Specification with leniency"], required = false, hidden = true)
    var lenientMode: Boolean = false

    private val specmaticConfig: SpecmaticConfig by lazy(LazyThreadSafetyMode.NONE) {
        configFileName?.let { Configuration.configFilePath = it }
        val resolvedConfigPath = configFileName ?: Configuration.configFilePath
        loadSpecmaticConfigOrNull(resolvedConfigPath, explicitlySpecifiedByUser = configFileName != null).orDefault()
    }

    override fun call(): Int = try {
        configureLogging(
            LoggingFromOpts(debug = verboseMode),
            LoggingConfigSource.FromConfig(specmaticConfig.getLogConfigurationOrDefault()))
        setParallelism(specmaticConfig)
        setTestThreadLocalSettings()

        val request: LauncherDiscoveryRequest = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(SpecmaticJUnitSupport::class.java))
                .build()

        junitLauncher.discover(request)
        resolvedJunitReportDir()?.let { dirName ->
            val reportListener = LegacyXmlReportGeneratingListener(Paths.get(dirName), PrintWriter(System.out, true))
            junitLauncher.registerTestExecutionListeners(reportListener)
        }

        junitLauncher.execute(request)
        resolvedJunitReportDir()?.let { reportDir ->
            val reportDirectory = File(reportDir)
            val reportFile = reportDirectory.resolve("TEST-junit-jupiter.xml")

            if(reportFile.isFile) {
                val updatedJUnitXML = updateNamesInJUnitXML(reportFile.readText())
                reportFile.writeText(updatedJUnitXML)
            } else {
                throw ContractException("Was expecting a JUnit report file called TEST-junit-jupiter.xml inside $reportDir but could not find it.")
            }
        }

        ContractExecutionListener.exitCode()
    }
    catch (e: Throwable) {
        logger.log(e)
        1
    }

    private fun setParallelism(specmaticConfig: SpecmaticConfig) {
        specmaticConfig.getTestParallelism()?.let { parallelism ->
            validateParallelism(parallelism)

            System.setProperty("junit.jupiter.execution.parallel.enabled", "true")

            when (parallelism) {
                "auto" -> {
                    logger.log("Running contract tests in parallel (dynamically determined number of threads)")
                    System.setProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
                }

                else -> {
                    logger.log("Running contract tests in parallel in $parallelism threads")
                    System.setProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
                    System.setProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", parallelism)
                }
            }
        }
    }

    private fun validateParallelism(parallelism: String) {
        if(parallelism == "auto")
            return

        try {
            parallelism.toInt()
        } catch(e: Throwable) {
            exitWithMessage("Parallelism is set to $parallelism. It must be either 'auto' or an integer value")
        }
    }

    private fun setTestThreadLocalSettings() {
        val port = port.takeUnless { it == 0 || it == -1 } ?: if (useHttps) 443 else 9000
        val protocol = when {
            port == 443 -> "https"
            useHttps -> "https"
            else -> "http"
        }

        val otherArguments = DeprecatedArguments(
            host = host,
            envName = envName,
            protocol = protocol,
            port = port.toString(),
            overlayFilePath = overlayFilePath?.let(::File),
            exampleDirectories = exampleDirs.takeIf { it.isNotEmpty() },
            filterName = filterName.takeIf(::isNotNullOrBlank),
            useCurrentBranchForCentralRepo = useCurrentBranchForCentralRepo,
            filterNotName = filterNotName.takeIf(::isNotNullOrBlank),
            inlineSuggestions = suggestions.takeIf(::isNotNullOrBlank),
            suggestionsPath = suggestionsPath.takeIf(::isNotNullOrBlank),
            variablesFileName = variablesFileName.takeIf(::isNotNullOrBlank),
        )

        val settings = ContractTestSettings(
            strictMode = strictMode,
            lenientMode = lenientMode,
            otherArguments = otherArguments,
            reportBaseDirectory = resolvedJunitReportDir(),
            filter = filter.takeIf(::isNotNullOrBlank),
            testBaseURL = testBaseURL.takeIf(::isNotNullOrBlank),
            configFile = configFileName.takeIf(::isNotNullOrBlank),
            timeoutInMilliSeconds = timeoutInMs ?: timeout?.times(1000),
            contractPaths = contractPaths?.joinToString(separator = ",").takeIf(::isNotNullOrBlank),
        )

        SpecmaticJUnitSupport.settingsStaging.set(settings)
    }

    private fun resolvedJunitReportDir(): String? {
        return junitReportDirName ?: specmaticConfig.getTestJunitReportDir()
    }

    private fun isNotNullOrBlank(value: String?): Boolean {
        return value != null && value.isNotBlank()
    }
}

private const val ORIGINAL_JUNIT_TEST_SUITE_NAME = "JUnit Jupiter"
private const val UPDATED_JUNIT_TEST_SUITE_NAME = "Contract Tests"
private const val TEST_NAME_ATTRIBUTE = "name"

internal fun updateNamesInJUnitXML(junitReport: String): String {
    val junitReportWithUpdatedTestSuiteTitle = junitReport.replace(
        ORIGINAL_JUNIT_TEST_SUITE_NAME,
        UPDATED_JUNIT_TEST_SUITE_NAME
    )

    val builder = newXMLBuilder()
    val reportDocument: Document = builder.parse(InputSource(StringReader(junitReportWithUpdatedTestSuiteTitle)))

    for (i in 0..reportDocument.documentElement.childNodes.length.minus(1)) {
        val testCaseNode = reportDocument.documentElement.childNodes.item(i)

        if (testCaseNode.nodeName != "testcase") continue

        val systemOutChildNode = findFirstChildNodeByName(testCaseNode.childNodes, SYSTEM_OUT_TESTCASE_TAG) ?: continue
        val cdataChildNode = systemOutChildNode.childNodes.item(0) ?: continue
        val systemOutTextContent = cdataChildNode.textContent ?: continue

        val displayNameLine = systemOutTextContent.lines().find { line ->
            line.startsWith(DISPLAY_NAME_PREFIX_IN_SYSTEM_OUT_TAG_TEXT)
        } ?: continue

        val testName = displayNameLine.removePrefix(DISPLAY_NAME_PREFIX_IN_SYSTEM_OUT_TAG_TEXT).trim()

        testCaseNode.attributes.getNamedItem(TEST_NAME_ATTRIBUTE).nodeValue = testName
    }

    return xmlToString(reportDocument)
}

internal fun findFirstChildNodeByName(nodes: NodeList, nodeName: String): Node? {
    for(i in 0..nodes.length.minus(1)) {
        val childNode = nodes.item(i)

        if(childNode.nodeName == nodeName)
            return childNode
    }

    return null
}
