package application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.specmatic.core.CONTRACT_EXTENSION
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_TEST_TIMEOUT
import io.specmatic.core.utilities.newXMLBuilder
import io.specmatic.test.SpecmaticJUnitSupport.Companion.CONTRACT_PATHS
import io.specmatic.test.SpecmaticJUnitSupport.Companion.HOST
import io.specmatic.test.SpecmaticJUnitSupport.Companion.PORT
import io.specmatic.test.listeners.ContractExecutionListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
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
        testCommand.verboseMode = false
        testCommand.configFileName = null
        System.clearProperty(CONTRACT_PATHS)
    }

    @Test
    fun `when contract files are not given it should not load from specmatic config`() {
        every { specmaticConfig.contractTestPaths() }.returns(contractsToBeRunAsTests)

        CommandLine(testCommand, factory).execute()

        verify(exactly = 0) { specmaticConfig.contractTestPaths() }
        assertThat(System.getProperty(CONTRACT_PATHS)).isNull()
    }

    @Test
    fun `when contract files are given it should not load from specmatic config`() {
        CommandLine(testCommand, factory).execute(contractsToBeRunAsTests[0], contractsToBeRunAsTests[1])
        verify(exactly = 0) { specmaticConfig.contractTestPaths() }
        assertThat(System.getProperty(CONTRACT_PATHS)).isEqualTo(contractsToBeRunAsTests.joinToString(","))
    }

    @Test
    fun `missing config is not logged without debug`() {
        every { junitLauncher.discover(any()) }.returns(mockk())
        every { junitLauncher.execute(any<LauncherDiscoveryRequest>()) }.returns(Unit)

        val exitCode = CommandLine(testCommand, factory).execute()

        assertThat(exitCode).isEqualTo(1)
    }

    @Test
    fun `missing config is debug logged with debug enabled`() {
        every { junitLauncher.discover(any()) }.returns(mockk())
        every { junitLauncher.execute(any<LauncherDiscoveryRequest>()) }.returns(Unit)

        val defaultLogger = logger
        val output = try {
            captureStandardOutput {
                CommandLine(testCommand, factory).execute("--debug")
            }.first
        } finally {
            logger = defaultLogger
        }

        assertThat(output).contains("Specmatic config file").contains("does not exist")
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

    @ParameterizedTest
    @MethodSource("commandLineArguments")
    fun `applies command line arguments`(
            optionName: String,
            optionValue: String,
            systemPropertyName: String,
            systemPropertyValue: String
    ) {
        every { specmaticConfig.contractTestPaths() }.returns(contractsToBeRunAsTests)
        try {
            CommandLine(testCommand, factory).execute(optionName, optionValue)
            assertThat(System.getProperty(systemPropertyName)).isEqualTo(systemPropertyValue)
        } finally {
            System.clearProperty(systemPropertyName)
        }
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

    private companion object {
        @JvmStatic
        fun commandLineArguments(): Stream<Arguments> = Stream.of(
                Arguments.of("--port", "9999", PORT, "9999"),
                Arguments.of("--host", "10.10.10.10", HOST, "10.10.10.10"),
                Arguments.of("--timeout", "3", SPECMATIC_TEST_TIMEOUT, "3000"),
                Arguments.of("--timeout-in-ms","12000", SPECMATIC_TEST_TIMEOUT, "12000"),
                Arguments.of("--examples", "test/data", Flags.EXAMPLE_DIRECTORIES, "test/data")
        )
    }

}
