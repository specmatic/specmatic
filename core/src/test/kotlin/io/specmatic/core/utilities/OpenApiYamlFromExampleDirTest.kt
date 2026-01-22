package io.specmatic.core.utilities

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.mock.ScenarioStub
import io.specmatic.proxy.ProxyOperation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.OpenAPIV3Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OpenApiYamlFromExampleDirTest {
    @Test
    fun `openapi spec should respect proxy operation path sort order`(@TempDir tempDir: File) {
        writeStub(tempDir, "a.json", httpMethod = "GET", path = "/orders")
        writeStub(tempDir, "m.json", httpMethod = "POST", path = "/users")
        writeStub(tempDir, "z.json", httpMethod = "GET", path = "/users")

        val sortOrder = listOf(proxyOperation("POST", "/users"), proxyOperation("GET", "/users"), proxyOperation("GET", "/orders"))
        val yaml = openApiYamlFromExampleDir(examplesDir = tempDir, featureName = "Ordered Feature", sortOrder = sortOrder)
        assertThat(yaml).isNotNull; yaml as String

        val openApi = parseYamlToOpenApi(yaml)
        val actualOrder = extractOperationsInOrder(openApi)

        val expectedOrder = listOf("get /users", "post /users", "get /orders")
        assertThat(actualOrder).isEqualTo(expectedOrder)
    }

    @Test
    fun `stubs not matching any proxy operation should be ordered last`(@TempDir tempDir: File) {
        writeStub(tempDir, "a.json", "GET", "/orders")
        writeStub(tempDir, "m.json", "GET", "/unmatched")
        writeStub(tempDir, "z.json", "GET", "/users")

        val sortOrder = listOf(proxyOperation("GET", "/users"), proxyOperation("GET", "/orders"))
        val yaml = openApiYamlFromExampleDir(examplesDir = tempDir, featureName = "Unmatched Feature", sortOrder = sortOrder)
        assertThat(yaml).isNotNull; yaml as String

        val openApi = parseYamlToOpenApi(yaml)
        val actualOrder = extractOperationsInOrder(openApi)

        val expectedOrderedPrefix = listOf("get /users", "get /orders")
        assertThat(actualOrder.take(expectedOrderedPrefix.size))
            .withFailMessage("Matched operations should appear first in sortOrder sequence")
            .isEqualTo(expectedOrderedPrefix)

        assertThat(actualOrder.last())
            .withFailMessage("Unmatched stubs should be placed after all matched operations")
            .isEqualTo("get /unmatched")
    }

    private fun writeStub(dir: File, fileName: String, httpMethod: String, path: String) {
        val stub = ScenarioStub(request = HttpRequest(method = httpMethod, path = path), response = HttpResponse.ok("ok"))
        dir.resolve(fileName).writeText(stub.toJSON().toStringLiteral())
    }

    private fun proxyOperation(method: String, path: String): ProxyOperation =
        ProxyOperation(method = method, pathPattern = OpenApiPath.from(path).toHttpPathPattern())

    private fun parseYamlToOpenApi(yaml: String): OpenAPI {
        println(yaml)
        return OpenAPIV3Parser().readContents(yaml).openAPI
    }

    private fun extractOperationsInOrder(openApi: OpenAPI): List<String> {
        val result = mutableListOf<String>()
        openApi.paths.forEach { (path, pathItem) ->
            pathItem.readOperationsMap().forEach { (method, _) ->
                result.add("${method.name.lowercase()} $path")
            }
        }

        return result
    }
}
