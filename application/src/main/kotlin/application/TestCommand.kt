package application

import io.specmatic.core.APPLICATION_NAME_LOWER_CASE
import io.specmatic.core.Configuration
import io.specmatic.core.DEFAULT_TIMEOUT_IN_MILLISECONDS
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_TEST_PARALLELISM
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_TEST_TIMEOUT
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import io.specmatic.core.utilities.exitWithMessage
import io.specmatic.core.utilities.newXMLBuilder
import io.specmatic.core.utilities.xmlToString
import io.specmatic.test.SpecmaticJUnitSupport
import io.specmatic.test.SpecmaticJUnitSupport.Companion.CONTRACT_PATHS
import io.specmatic.test.SpecmaticJUnitSupport.Companion.ENV_NAME
import io.specmatic.test.SpecmaticJUnitSupport.Companion.FILTER
import io.specmatic.test.SpecmaticJUnitSupport.Companion.FILTER_NAME_PROPERTY
import io.specmatic.test.SpecmaticJUnitSupport.Companion.FILTER_NOT_NAME_PROPERTY
import io.specmatic.test.SpecmaticJUnitSupport.Companion.HOST
import io.specmatic.test.SpecmaticJUnitSupport.Companion.INLINE_SUGGESTIONS
import io.specmatic.test.SpecmaticJUnitSupport.Companion.OVERLAY_FILE_PATH
import io.specmatic.test.SpecmaticJUnitSupport.Companion.PORT
import io.specmatic.test.SpecmaticJUnitSupport.Companion.STRICT_MODE
import io.specmatic.test.SpecmaticJUnitSupport.Companion.SUGGESTIONS_PATH
import io.specmatic.test.SpecmaticJUnitSupport.Companion.TEST_BASE_URL
import io.specmatic.test.SpecmaticJUnitSupport.Companion.VARIABLES_FILE_NAME
import io.specmatic.test.listeners.ContractExecutionListener
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
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

@Command(name = "test",
        mixinStandardHelpOptions = true,
        description = ["Run contract as tests"])
class TestCommand(private val junitLauncher: Launcher = LauncherFactory.create()) : Callable<Unit> {

    @CommandLine.Parameters(arity = "0..*", description = ["Contract file paths"])
    var contractPaths: List<String> = emptyList()

    @Option(names = ["--host"], description = ["The host to bind to, e.g. localhost or some locally bound IP"], defaultValue = "localhost")
    lateinit var host: String

    @Option(names = ["--port"], description = ["The port to bind to"])
    var port: Int = 0

    @Option(names = ["--testBaseURL"], description = ["The base URL, use this instead of host and port"], defaultValue = "")
    lateinit var testBaseURL: String

    @Option(names = ["--suggestionsPath"], description = ["Location of the suggestions file"], defaultValue = "")
    lateinit var suggestionsPath: String

    @Option(names = ["--suggestions"], description = ["A json value with scenario name and multiple suggestions"], defaultValue = "")
    var suggestions: String = ""

    @Option(names = ["--filter-name"], description = ["Run only tests with this value in their name"], defaultValue = "\${env:SPECMATIC_FILTER_NAME}", hidden = true)
    var filterName: String = ""

    @Option(names = ["--filter-not-name"], description = ["Run only tests which do not have this value in their name"], defaultValue = "\${env:SPECMATIC_FILTER_NOT_NAME}", hidden = true)
    var filterNotName: String = ""

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
    var filter: String = ""

    @Option(names = ["--env"], description = ["Environment name"])
    var envName: String = ""

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

    @Option(names = ["--variables"], description = ["Variables file name ($APPLICATION_NAME_LOWER_CASE.json by default)"])
    var variablesFileName: String? = null

    @Option(names = ["--debug"], description = ["Debug logs"])
    var verboseMode: Boolean = false

    @Option(names = ["--examples"], description = ["Directories containing JSON examples"], required = false)
    var exampleDirs: List<String> = mutableListOf()

    @Option(names = ["--overlay-file"], description = ["Overlay file for the specification"], required = false)
    var overlayFilePath: String? = null

    @Option(
        names = ["--strict"],
        description = ["If true, tests will only run if all the examples are valid"],
        required = false
    )
    var strictMode: Boolean = false

    override fun call() = try {
        setParallelism()

        if(verboseMode) {
            logger = Verbose()
        }

        configFileName?.let {
            Configuration.configFilePath = it
            System.setProperty(CONFIG_FILE_PATH, it)
        }

        if(port == 0) {
            port = when {
                useHttps -> 443
                else -> 9000
            }
        }

        val protocol = when {
            port == 443 -> "https"
            useHttps -> "https"
            else -> "http"
        }

        val timeoutInMs = timeoutInMs ?: timeout?.times(1000) ?: DEFAULT_TIMEOUT_IN_MILLISECONDS

        System.setProperty(HOST, host)
        System.setProperty(PORT, port.toString())
        System.setProperty(SPECMATIC_TEST_TIMEOUT, timeoutInMs.toString())
        System.setProperty(SUGGESTIONS_PATH, suggestionsPath)
        System.setProperty(INLINE_SUGGESTIONS, suggestions)
        System.setProperty(ENV_NAME, envName)
        System.setProperty("protocol", protocol)
        System.setProperty(FILTER, filter)
        System.setProperty(OVERLAY_FILE_PATH, overlayFilePath.orEmpty())
        System.setProperty(STRICT_MODE, strictMode.toString())

        if(exampleDirs.isNotEmpty()) {
            System.setProperty(EXAMPLE_DIRECTORIES, exampleDirs.joinToString(","))
        }

        if(filterName.isNotBlank()) {
            System.setProperty(FILTER_NAME_PROPERTY, filterName)
        }

        if(filterNotName.isNotBlank()) {
            System.setProperty(FILTER_NOT_NAME_PROPERTY, filterNotName)
        }

        variablesFileName?.let {
            System.setProperty(VARIABLES_FILE_NAME, it)
        }

        if(testBaseURL.isNotEmpty())
            System.setProperty(TEST_BASE_URL, testBaseURL)

        if(contractPaths.isNotEmpty()) System.setProperty(CONTRACT_PATHS, contractPaths.joinToString(","))

        val request: LauncherDiscoveryRequest = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(SpecmaticJUnitSupport::class.java))
                .build()
        junitLauncher.discover(request)

        junitReportDirName?.let { dirName ->
            val reportListener = org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener(Paths.get(dirName), PrintWriter(System.out, true))
            junitLauncher.registerTestExecutionListeners(reportListener)
        }

        junitLauncher.execute(request)

        junitReportDirName?.let {
            val reportDirectory = File(it)
            val reportFile = reportDirectory.resolve("TEST-junit-jupiter.xml")

            if(reportFile.isFile) {
                val updatedJUnitXML = updateNamesInJUnitXML(reportFile.readText())
                reportFile.writeText(updatedJUnitXML)
            } else {
                throw ContractException("Was expecting a JUnit report file called TEST-junit-jupiter.xml inside $junitReportDirName but could not find it.")
            }
        }

        ContractExecutionListener.exitProcess()
    }
    catch (e: Throwable) {
        logger.log(e)
    }

    private fun setParallelism() {
        getStringValue(SPECMATIC_TEST_PARALLELISM)?.let { parallelism ->
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
            exitWithMessage("The value of the $SPECMATIC_TEST_PARALLELISM environment variable must be either 'true' or an integer value")
        }
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
