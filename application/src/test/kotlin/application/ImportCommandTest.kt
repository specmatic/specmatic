package application

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.NamedStub
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.utilities.openApiFromTraffic
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import io.swagger.v3.core.util.Yaml
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ImportCommandTest {
    @Test
    fun `stub import writes the structured traffic OpenAPI to the requested path`(@TempDir tempDir: File) {
        val stub = ScenarioStub(
            request = HttpRequest(
                method = "POST",
                path = "/items",
                headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
                body = parsedJSON("""{"name":"tea"}"""),
            ),
            response = HttpResponse(
                status = 201,
                headers = mapOf("Content-Type" to "text/plain; charset=utf-8"),
                body = StringValue("created"),
            ),
        )
        val input = tempDir.resolve("item.json").apply { writeText(stub.toJSON().toStringLiteral()) }
        val output = tempDir.resolve("custom.yaml")

        convertStub(input.absolutePath, output.absolutePath)

        val expected = Yaml.pretty(openApiFromTraffic("New Feature", listOf(NamedStub("New scenario", stub))))
        assertThat(output).exists()
        assertThat(output.readText()).isEqualTo(expected)
        assertThat(output.readText()).contains("title: New Feature", "summary: New scenario")
    }

    @Test
    fun `Postman import writes the structured contract OpenAPI using the base URL tagged name`(@TempDir tempDir: File) {
        val postmanCollection = """
            {
              "info": {
                "name": "Imported Collection",
                "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
              },
              "item": [
                {
                  "name": "ignored live request",
                  "request": {
                    "method": "GET",
                    "header": [],
                    "url": { "raw": "http://localhost:65530/items" }
                  },
                  "response": [
                    {
                      "name": "saved response",
                      "originalRequest": {
                        "method": "GET",
                        "header": [],
                        "url": { "raw": "http://localhost:65530/items" }
                      },
                      "code": 201,
                      "header": [
                        { "key": "Content-Type", "value": "application/json" }
                      ],
                      "body": "{\"id\":10}"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val input = tempDir.resolve("collection.postman_collection.json").apply { writeText(postmanCollection) }
        val requestedOutput = tempDir.resolve("postman.yaml")
        val expectedStub = ScenarioStub(
            request = HttpRequest(method = "GET", path = "/items"),
            response = HttpResponse(
                status = 201,
                headers = mapOf("Content-Type" to "application/json"),
                body = parsedJSON("""{"id":10}"""),
            ),
        )

        convertPostman(input.absolutePath, requestedOutput.absolutePath)

        val output = tempDir.resolve("postman-localhost-65530.yaml")
        val expected = Yaml.pretty(
            openApiFromTraffic("Imported Collection", listOf(NamedStub("saved response", expectedStub)))
        )
        assertThat(output).exists()
        assertThat(output.readText()).isEqualTo(expected)
    }
}
