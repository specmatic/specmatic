package application.mcp.utils

import application.mcp.server.tools.FailedTest
import application.mcp.server.tools.TestSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JunitReportReaderTest {

    @TempDir
    lateinit var tempDir: File

    private val reader = JunitReportReader()

    @Test
    fun `parseJUnitSummary should correctly parse a valid JUnit report`() {
        val reportFile = tempDir.resolve("TEST-junit-jupiter.xml")
        reportFile.writeText("""
            <testsuite name="Contract Tests" tests="10" failures="1" errors="1" skipped="0">
              <testcase name="Scenario 1" classname="Contract Tests" time="0.1"/>
              <testcase name="Scenario 2" classname="Contract Tests" time="0.1">
                <failure message="Error message 2"/>
              </testcase>
              <testcase name="Scenario 3" classname="Contract Tests" time="0.1">
                <error message="Error message 3"/>
              </testcase>
            </testsuite>
        """.trimIndent())

        val summary = reader.parseJUnitSummary(reportFile)

        assertThat(summary).isNotNull
        assertThat(summary!!.total).isEqualTo(10)
        assertThat(summary.passed).isEqualTo(8)
        assertThat(summary.failed).isEqualTo(2)
        assertThat(summary.failedTests).hasSize(2)
        assertThat(summary.failedTests[0].scenario).isEqualTo("Scenario 2")
        assertThat(summary.failedTests[0].message).isEqualTo("Error message 2")
        assertThat(summary.failedTests[1].scenario).isEqualTo("Scenario 3")
        assertThat(summary.failedTests[1].message).isEqualTo("Error message 3")
    }

    @Test
    fun `parseJUnitSummary should return null if report file does not exist`() {
        val summary = reader.parseJUnitSummary(File("non-existent.xml"))
        assertThat(summary).isNull()
    }

    @Test
    fun `parseJUnitSummary should return null for non junit xml root`() {
        val reportFile = tempDir.resolve("not-junit.xml")
        reportFile.writeText("<report><testcase name=\"ignored\"/></report>")

        val summary = reader.parseJUnitSummary(reportFile)

        assertThat(summary).isNull()
    }

    @Test
    fun `parseJUnitSummary should handle empty failed tests list`() {
        val reportFile = tempDir.resolve("TEST-junit-jupiter.xml")
        reportFile.writeText("""
            <testsuite name="Contract Tests" tests="5" failures="0" errors="0" skipped="0">
              <testcase name="Scenario 1" classname="Contract Tests" time="0.1"/>
              <testcase name="Scenario 2" classname="Contract Tests" time="0.1"/>
              <testcase name="Scenario 3" classname="Contract Tests" time="0.1"/>
              <testcase name="Scenario 4" classname="Contract Tests" time="0.1"/>
              <testcase name="Scenario 5" classname="Contract Tests" time="0.1"/>
            </testsuite>
        """.trimIndent())

        val summary = reader.parseJUnitSummary(reportFile)

        assertThat(summary).isNotNull
        assertThat(summary!!.total).isEqualTo(5)
        assertThat(summary.passed).isEqualTo(5)
        assertThat(summary.failed).isEqualTo(0)
        assertThat(summary.failedTests).isEmpty()
    }

    @Test
    fun `parseJUnitSummary should correctly parse a valid JUnit report with multiple suites`() {
        val reportFile = tempDir.resolve("TEST-junit-jupiter.xml")
        reportFile.writeText("""
            <testsuites>
                <testsuite name="Suite 1" tests="2" failures="1" errors="0" skipped="0">
                  <testcase name="S1 Scenario 1" classname="Suite 1" time="0.1"/>
                  <testcase name="S1 Scenario 2" classname="Suite 1" time="0.1">
                    <failure message="Error 1"/>
                  </testcase>
                </testsuite>
                <testsuite name="Suite 2" tests="2" failures="0" errors="1" skipped="0">
                  <testcase name="S2 Scenario 1" classname="Suite 2" time="0.1"/>
                  <testcase name="S2 Scenario 2" classname="Suite 2" time="0.1">
                    <error message="Error 2"/>
                  </testcase>
                </testsuite>
            </testsuites>
        """.trimIndent())

        val summary = reader.parseJUnitSummary(reportFile)

        assertThat(summary).isNotNull
        assertThat(summary!!.total).isEqualTo(4)
        assertThat(summary.passed).isEqualTo(2)
        assertThat(summary.failed).isEqualTo(2)
        assertThat(summary.failedTests).hasSize(2)
        assertThat(summary.failedTests.map { it.scenario }).containsExactlyInAnyOrder("S1 Scenario 2", "S2 Scenario 2")
    }

    @Test
    fun `parseJUnitSummary should handle skipped tests correctly`() {
        val reportFile = tempDir.resolve("TEST-junit-jupiter.xml")
        reportFile.writeText("""
            <testsuite name="Contract Tests" tests="5" failures="1" errors="0" skipped="2">
              <testcase name="Scenario 1" classname="Contract Tests"/>
              <testcase name="Scenario 2" classname="Contract Tests">
                <failure message="fail"/>
              </testcase>
              <testcase name="Scenario 3" classname="Contract Tests">
                <skipped/>
              </testcase>
              <testcase name="Scenario 4" classname="Contract Tests">
                <skipped/>
              </testcase>
              <testcase name="Scenario 5" classname="Contract Tests"/>
            </testsuite>
        """.trimIndent())

        val summary = reader.parseJUnitSummary(reportFile)

        assertThat(summary).isNotNull
        assertThat(summary!!.total).isEqualTo(5)
        assertThat(summary.passed).isEqualTo(2)
        assertThat(summary.failed).isEqualTo(1)
    }

    @Test
    fun `parseJUnitSummary should use failure text when message attribute is absent`() {
        val reportFile = tempDir.resolve("TEST-junit-jupiter.xml")
        reportFile.writeText("""
            <testsuite name="Contract Tests" tests="1" failures="1" errors="0" skipped="0">
              <testcase name="Scenario without message" classname="Contract Tests">
                <failure>
                  Expected status 200
                  but got status 500
                </failure>
              </testcase>
            </testsuite>
        """.trimIndent())

        val summary = reader.parseJUnitSummary(reportFile)

        assertThat(summary).isNotNull
        assertThat(summary!!.failedTests).hasSize(1)
        assertThat(summary.failedTests.first().scenario).isEqualTo("Scenario without message")
        assertThat(summary.failedTests.first().message).contains("Expected status 200")
        assertThat(summary.failedTests.first().message).contains("but got status 500")
    }
}
