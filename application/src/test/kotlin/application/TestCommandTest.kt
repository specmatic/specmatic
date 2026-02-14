package application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.specmatic.core.CONTRACT_EXTENSION
import io.specmatic.core.log.ThreadSafeLog
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.newXMLBuilder
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.test.ContractTestSettings
import io.specmatic.test.SpecmaticJUnitSupport
import io.specmatic.test.listeners.ContractExecutionListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.TestExecutionListener
import org.w3c.dom.Document
import org.xml.sax.InputSource
import picocli.CommandLine
import java.io.StringReader
import java.util.*
import java.util.stream.Stream
import java.io.File


internal class TestCommandTest {
    private var specmaticConfig: SpecmaticConfig = mockk()
    private var junitLauncher: Launcher = mockk()
    private val factory: CommandLine.IFactory = CommandLine.defaultFactory()
    private val testCommand: TestCommand = TestCommand(junitLauncher)

    private val contractsToBeRunAsTests = arrayListOf("/config/path/to/contract_1.$CONTRACT_EXTENSION",
            "/config/path/to/another_contract_1.$CONTRACT_EXTENSION")

    @BeforeEach
    fun `clean up test command`() {
        testCommand.contractPaths = arrayListOf()
        testCommand.junitReportDirName = null
    }

    @Test
    fun `when contract files are not given it should not load from specmatic config`() {
        every { specmaticConfig.contractTestPaths() }.returns(contractsToBeRunAsTests)

        CommandLine(testCommand, factory).execute()

        verify(exactly = 0) { specmaticConfig.contractTestPaths() }
    }

    @Test
    fun `when contract files are given it should not load from specmatic config`() {
        CommandLine(testCommand, factory).execute(contractsToBeRunAsTests[0], contractsToBeRunAsTests[1])
        verify(exactly = 0) { specmaticConfig.contractTestPaths() }
        assertThat(SpecmaticJUnitSupport.settingsStaging.get()?.contractPaths).isEqualTo(contractsToBeRunAsTests.joinToString(","))
    }

    @Test
    fun `when an explicit contract path does not exist test command should return non-zero`(@TempDir tempDir: File) {
        val missingSpecPath = tempDir.resolve("missing.$CONTRACT_EXTENSION").absolutePath

        val (output, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
            CommandLine(TestCommand()).execute(missingSpecPath)
        }

        assertThat(exitCode).isEqualTo(1)
        assertThat(output).contains("does not exist").contains("missing.$CONTRACT_EXTENSION")
    }

    @Test
    fun `ContractExecutionListener should be registered`() {
        val registeredListeners = ServiceLoader.load(TestExecutionListener::class.java)
            .map { it.javaClass.name }
            .toMutableList()

        assertThat(registeredListeners).contains(ContractExecutionListener::class.java.name)
    }

    @Test
    fun `when junit report directory is set it should also register legacy XML test execution listener`() {
        every { junitLauncher.discover(any()) }.returns(mockk())
        every { junitLauncher.registerTestExecutionListeners(any<ContractExecutionListener>()) }.returns(mockk())
        every { junitLauncher.registerTestExecutionListeners(any<org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener>()) }.returns(mockk())

        CommandLine(testCommand, factory).execute("api_1.$CONTRACT_EXTENSION", "--junitReportDir", "reports/junit")

        verify(exactly = 1) { junitLauncher.discover(any()) }
        verify { junitLauncher.registerTestExecutionListeners(any<ContractExecutionListener>()) }
        verify { junitLauncher.registerTestExecutionListeners(any<org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener>()) }
        verify(exactly = 1) { junitLauncher.execute(any<LauncherDiscoveryRequest>()) }
    }

    @Test
    fun `when junit report directory is set in config it should also register legacy XML test execution listener`(@TempDir tempDir: File) {
        every { junitLauncher.discover(any()) }.returns(mockk())
        every { junitLauncher.registerTestExecutionListeners(any<ContractExecutionListener>()) }.returns(mockk())
        every { junitLauncher.registerTestExecutionListeners(any<org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener>()) }.returns(mockk())

        val configFile = writeSpecmaticYaml(tempDir, """
        version: 2
        contracts:
        - provides:
            - api_1.$CONTRACT_EXTENSION
        test:
          junitReportDir: reports/junit
        """.trimIndent())

        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            CommandLine(testCommand, factory).execute("api_1.$CONTRACT_EXTENSION")
        }

        verify(exactly = 1) { junitLauncher.discover(any()) }
        verify { junitLauncher.registerTestExecutionListeners(any<ContractExecutionListener>()) }
        verify { junitLauncher.registerTestExecutionListeners(any<org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener>()) }
        verify(exactly = 1) { junitLauncher.execute(any<LauncherDiscoveryRequest>()) }
    }

    @ParameterizedTest
    @MethodSource("commandLineArguments")
    fun `applies command line arguments`(testCase: TestCommandCase) {
        every { specmaticConfig.contractTestPaths() }.returns(contractsToBeRunAsTests)
        val arguments = listOfNotNull(testCase.argument, testCase.value).map { it.toString() }.toTypedArray()
        CommandLine(testCommand, factory).execute(*arguments)
        val settings = SpecmaticJUnitSupport.settingsStaging.get()!!
        val expectedValue = testCase.extract(settings)
        assertThat(expectedValue).isEqualTo(testCase.expected)
    }

    @Test
    fun `should replace the names of the tests and the top-level test suite in a JUnit XML text generated by the test command`() {
        val initialJUnitReport = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="JUnit Jupiter" tests="1" skipped="0" failures="0" errors="0" time="1.22" hostname="" timestamp="">
            <properties>
            <property name="contractPaths" value="service.yaml"/>
            </properties>
            <testcase name="contractTest()[1]" classname="io.specmatic.test.SpecmaticJUnitSupport" time="1.028">
            <system-out><![CDATA[
            unique-id: [engine:junit-jupiter]/[class:io.specmatic.test.SpecmaticJUnitSupport]/[test-factory:contractTest()]/[dynamic-test:#1]
            display-name:  Scenario: GET /pets/(petid:number) -> 200 — EX:200_OKAY
            ]]></system-out>
            </testcase>
            <system-out><![CDATA[
            unique-id: [engine:junit-jupiter]
            display-name: JUnit Jupiter
            ]]></system-out>
            </testsuite>
        """.trimIndent()

        val junitReportWithUpdatedNames = updateNamesInJUnitXML(initialJUnitReport)

        val builder = newXMLBuilder()
        val reportDocument: Document = builder.parse(InputSource(StringReader(junitReportWithUpdatedNames)))

        assertThat(reportDocument.documentElement.attributes.getNamedItem("name").nodeValue).isEqualTo("Contract Tests")

        val testCaseNode = findFirstChildNodeByName(reportDocument.documentElement.childNodes, "testcase") ?: fail("Could not find testcase node in the updated JUnit report")

        assertThat(testCaseNode.attributes.getNamedItem("name").nodeValue).isEqualTo("Scenario: GET /pets/(petid:number) -> 200 — EX:200_OKAY")
    }

    companion object {
        data class TestCommandCase(val argument: String?, val value: Any?, val extract: (ContractTestSettings) -> Any?, val expected: Any)
        @JvmStatic
        fun commandLineArguments(): Stream<TestCommandCase> = listOf(
            // -------- positional arguments --------
            TestCommandCase("contract1.yaml", null, { it.contractPaths }, "contract1.yaml"),

            // -------- host / port / base url --------
            TestCommandCase("--host", "10.10.10.10", { it.host }, "10.10.10.10"),
            TestCommandCase("--port", "9999", { it.port }, "9999"),
            TestCommandCase("--port", "-1", { it.port }, "9000"),
            TestCommandCase("--testBaseURL", "https://example.com", { it.testBaseURL }, "https://example.com"),

            // -------- timeouts --------
            TestCommandCase("--timeout", "3", { it.timeoutInMilliSeconds }, 3000L),
            TestCommandCase("--timeout", "0", { it.timeoutInMilliSeconds }, 0L),
            TestCommandCase("--timeout-in-ms", "12000", { it.timeoutInMilliSeconds }, 12000L),

            // -------- filtering --------
            TestCommandCase("--filter", "METHOD=GET", { it.filter }, "METHOD=GET"),
            TestCommandCase("--filter-name", "Foo", { it.filterName }, "Foo"),
            TestCommandCase("--filter-not-name", "Bar", { it.filterNotName }, "Bar"),

            // -------- examples / suggestions --------
            TestCommandCase("--examples", "test/data", { it.getSpecmaticConfig().getExamples() }, listOf("test/data")),
            TestCommandCase("--suggestionsPath", "suggestions.json", { it.suggestionsPath }, "suggestions.json"),
            TestCommandCase("--suggestions", """{"scenario":"s1","suggestions":["a","b"]}""", { it.inlineSuggestions }, """{"scenario":"s1","suggestions":["a","b"]}"""),

            // -------- environment / config --------
            TestCommandCase("--env", "staging", { it.envName }, "staging"),
            TestCommandCase("--config", "specmatic-test.json", { it.configFile }, "specmatic-test.json"),
            TestCommandCase("--variables", "vars.json", { it.variablesFileName }, "vars.json"),
            TestCommandCase("--overlay-file", "overlay.yaml", { it.overlayFilePath?.path }, "overlay.yaml"),

            // -------- reporting --------
            TestCommandCase("--junitReportDir", "build/reports", { it.reportBaseDirectory }, "build/reports"),

            // -------- execution modes --------
            TestCommandCase("--strict", null, { it.strictMode }, true),
            TestCommandCase("--lenient", null, { it.lenientMode }, true),
            TestCommandCase("--match-branch", null, { it.otherArguments?.useCurrentBranchForCentralRepo }, true),
            TestCommandCase("--debug", null, { logger is ThreadSafeLog && (logger as ThreadSafeLog).logger is Verbose }, true),

            // -------- https / protocol mutations --------
            TestCommandCase(null, null, { it.protocol }, "http"),
            TestCommandCase("--https", true, { it.protocol }, "https"),
        ).stream()
    }

    private fun writeSpecmaticYaml(dir: File, content: String): File =
        dir.resolve("specmatic.yaml").also { it.writeText(content) }
}
