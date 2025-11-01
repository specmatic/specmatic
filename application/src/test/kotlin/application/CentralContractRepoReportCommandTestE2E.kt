package application

import io.specmatic.reports.CentralContractRepoReportJson
import io.specmatic.reports.OpenAPISpecificationOperation
import io.specmatic.reports.SpecificationOperation
import io.specmatic.reports.SpecificationRow
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File

class CentralContractRepoReportCommandTestE2E {

    private val centralContractRepoReportCommand = CentralContractRepoReportCommand()

    @Test
    fun `test generates report json file`() {
        centralContractRepoReportCommand.baseDir = ""
        centralContractRepoReportCommand.call()
        val reportJson: CentralContractRepoReportJson = Json.decodeFromString(reportFile.readText())

        val expectedSpecificationRow = SpecificationRow(
            osAgnosticPath("specifications/service1/service1.yaml"),
            "HTTP",
            listOf(
                OpenAPISpecificationOperation(
                    "/hello/{id}",
                    "GET",
                    200
                ),
                OpenAPISpecificationOperation(
                    "/hello/{id}",
                    "GET",
                    404
                ),
                OpenAPISpecificationOperation(
                    "/hello/{id}",
                    "GET",
                    400
                )
            )
        )

        assertThat(reportJson.specifications.contains(expectedSpecificationRow)).isTrue()
    }

    @Test
    fun `report only contains specifications within baseDir`() {
        createSpecFiles("./specifications/service2/service2.yaml")
        centralContractRepoReportCommand.baseDir = "specifications/service2"
        centralContractRepoReportCommand.call()
        val reportJson: CentralContractRepoReportJson = Json.decodeFromString(reportFile.readText())

        val expectedSpecificationRow = SpecificationRow(
            osAgnosticPath("specifications/service2/service2.yaml"),
            "HTTP",
            listOf(
                OpenAPISpecificationOperation(
                    "/hello/{id}",
                    "GET",
                    200
                ),
                OpenAPISpecificationOperation(
                    "/hello/{id}",
                    "GET",
                    404
                ),
                OpenAPISpecificationOperation(
                    "/hello/{id}",
                    "GET",
                    400
                )
            )
        )

        assertThat(reportJson.specifications).containsOnly(expectedSpecificationRow)
    }

    companion object {
        private val reportFile = File(osAgnosticPath("./build/reports/specmatic/central_contract_repo_report.json"))

        @JvmStatic
        @BeforeAll
        fun setupBeforeAll() {
            createSpecFiles("./specifications/service1/service1.yaml")
        }

        @JvmStatic
        @AfterAll
        fun tearDownAfterAll() {
            File(osAgnosticPath("./specifications")).deleteRecursively()
            reportFile.delete()
        }

        private fun createSpecFiles(specFilePath: String) {
            val service1spec = """
openapi: 3.0.0
info:
  title: Sample API
  description: Optional multiline or single-line description in [CommonMark](http://commonmark.org/help/) or HTML.
  version: 0.1.9
servers:
  - url: http://api.example.com/v1
    description: Optional server description, e.g. Main (production) server
  - url: http://staging-api.example.com
    description: Optional server description, e.g. Internal staging server for testing
paths:
  /hello/{id}:
    get:
      summary: hello world
      description: Optional extended description in CommonMark or HTML.
      parameters:
        - in: path
          name: id
          schema:
            type: integer
          required: true
          description: Numeric ID
      responses:
        '200':
          description: Says hello
          content:
            application/json:
              schema:
                type: string
        '404':
          description: Not Found
          content:
            application/json:
              schema:
                type: string
        '400':
          description: Bad Request
          content:
            application/json:
              schema:
                type: string
        """
            val service1File = File(osAgnosticPath(specFilePath))
            service1File.parentFile.mkdirs()
            service1File.writeText(service1spec)
        }
    }
}

fun osAgnosticPath(path: String): String {
    return path.replace("/", File.separator)
}
