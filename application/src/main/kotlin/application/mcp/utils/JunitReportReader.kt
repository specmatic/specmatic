package application.mcp.utils

import application.mcp.server.tools.FailedTest
import application.mcp.server.tools.TestSummary
import io.specmatic.core.utilities.newXMLBuilder
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File

class JunitReportReader() {
     internal fun parseJUnitSummary(reportFile: File): TestSummary? {
        if (!reportFile.isFile) return null

        val root = newXMLBuilder().parse(reportFile).documentElement ?: return null
        if (root.tagName !in setOf("testsuite", "testsuites")) return null

        val testCases = root.descendantElements("testcase")
        val failedTests = testCases.mapNotNull { testCase ->
            val failure = testCase.firstChildElement("failure") ?: testCase.firstChildElement("error") ?: return@mapNotNull null
            FailedTest(
                scenario = testCase.getAttribute("name"),
                message = failure.getAttribute("message").ifBlank { failure.textContent.orEmpty().trim() }
            )
        }

        val total = root.intAttribute("tests") ?: testCases.size
        val failures = root.intAttribute("failures")
        val errors = root.intAttribute("errors")
        val failed = if (failures != null || errors != null) (failures ?: 0) + (errors ?: 0) else failedTests.size
        val skipped = root.intAttribute("skipped") ?: testCases.count { it.firstChildElement("skipped") != null }
        val passed = total - failed - skipped

        return TestSummary(
            total = total,
            passed = passed,
            failed = failed,
            failedTests = failedTests,
            reportLabel = "JUnit",
            reportPath = reportFile.canonicalPath
        )
    }

    private fun Element.intAttribute(name: String): Int? {
        return getAttribute(name).takeIf { it.isNotBlank() }?.toIntOrNull()
    }

    private fun Element.descendantElements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.firstChildElement(tagName: String): Element? {
        return (0 until childNodes.length)
            .map { childNodes.item(it) }
            .firstOrNull { it.nodeType == Node.ELEMENT_NODE && it.nodeName == tagName } as? Element
    }
}
