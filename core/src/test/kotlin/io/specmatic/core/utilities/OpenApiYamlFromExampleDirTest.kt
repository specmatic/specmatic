package io.specmatic.core.utilities

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.NamedStub
import io.specmatic.core.QueryParameters
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.StringValue
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
    fun `infers scalar request bodies without creating a dangling component reference`() {
        val openApi = openApiFromTraffic(
            "Scalar",
            listOf(namedStub(
                "echo",
                HttpRequest("POST", "/echo", mapOf("Content-Type" to "text/plain"), StringValue("hello")),
                HttpResponse.ok("world"),
            )),
        )!!

        val schema = openApi.paths["/echo"]!!.post.requestBody.content["text/plain"]!!.schema
        assertThat(schema.type).isEqualTo("string")
        assertThat(schema.`$ref`).isNull()
    }

    @Test
    fun `infers arrays nested inside json bodies as array schemas`() {
        val openApi = openApiFromTraffic(
            "Arrays",
            listOf(namedStub(
                "create",
                HttpRequest("POST", "/items", body = parsedJSON("""{"tags":["one","two"]}""")),
                HttpResponse.ok("created"),
            )),
        )!!

        val requestSchema = openApi.components.schemas.entries.single { (name, _) -> name.contains("RequestBody") }.value
        val tagsSchema = requestSchema.properties["tags"]!!
        assertThat(tagsSchema.type).isEqualTo("array")
        assertThat(tagsSchema.items!!.type).isEqualTo("string")
    }

    @Test
    fun `infers scalar types when example names collide across request locations`() {
        val request = HttpRequest(
            method = "POST",
            path = "/collisions",
            headers = mapOf("id" to "2"),
            body = parsedJSON("""{"id":3}"""),
            queryParams = QueryParameters(mapOf("id" to "1")),
        )

        val openApi = openApiFromTraffic(
            "Collisions",
            listOf(namedStub("collisions", request, HttpResponse.ok("done"))),
        )!!

        val headerSchema = openApi.paths["/collisions"]!!.post.parameters.single { it.`in` == "header" }.schema
        assertThat(headerSchema.type).isEqualTo("integer")
        assertThat(headerSchema.`$ref`).isNull()
    }

    @Test
    fun `infers form field schemas`() {
        val request = HttpRequest(
            method = "POST",
            path = "/login",
            formFields = mapOf("attempt" to "2", "remember" to "true"),
        )

        val openApi = openApiFromTraffic(
            "Forms",
            listOf(namedStub("login", request, HttpResponse.ok("accepted"))),
        )!!

        val schema = openApi.paths["/login"]!!.post.requestBody.content["application/x-www-form-urlencoded"]!!.schema
        assertThat(schema.properties["attempt"]!!.type).isEqualTo("integer")
        assertThat(schema.properties["remember"]!!.type).isEqualTo("boolean")
    }

    @Test
    fun `parses json response strings using the recorded content type`() {
        val response = HttpResponse(
            400,
            mapOf("content-type" to "application/problem+json; charset=utf-8"),
            StringValue("""{"message":"bad"}"""),
        )

        val openApi = openApiFromTraffic(
            "Errors",
            listOf(namedStub("error", HttpRequest("GET", "/errors"), response)),
        )!!

        val operationResponse = openApi.paths["/errors"]!!.get.responses["400"]!!
        assertThat(operationResponse.content).containsKey("application/problem+json")
        val responseSchema = openApi.components.schemas.entries.single { (name, _) -> name.contains("ResponseBody") }.value
        assertThat(responseSchema.properties["message"]!!.type).isEqualTo("string")
    }

    @Test
    fun `makes fields optional when they are absent from another recording`() {
        val openApi = openApiFromTraffic(
            "Orders",
            listOf(
                namedStub(
                    "create",
                    HttpRequest("POST", "/orders", body = parsedJSON("""{"name":"coffee","note":"hot"}""")),
                    HttpResponse(201, body = parsedJSON("""{"id":1,"state":"created"}""")),
                ),
                namedStub(
                    "create",
                    HttpRequest("POST", "/orders", body = parsedJSON("""{"name":"tea"}""")),
                    HttpResponse(201, body = parsedJSON("""{"id":2}""")),
                ),
            ),
        )!!

        val requestSchema = openApi.components.schemas.entries.single { (name, _) -> name.contains("RequestBody") }.value
        val responseSchema = openApi.components.schemas.entries.single { (name, _) -> name.contains("ResponseBody") }.value
        assertThat(requestSchema.required).contains("name").doesNotContain("note")
        assertThat(responseSchema.required).contains("id").doesNotContain("state")
    }

    @Test
    fun `rejects traffic without a request method`() {
        val exception = assertThrows(ContractException::class.java) {
            openApiFromTraffic("Invalid", listOf(namedStub("invalid", HttpRequest(path = "/items"), HttpResponse.ok("ok"))))
        }

        assertThat(exception.message).contains("without the http method")
    }

    @Test
    fun `rejects traffic without a request path`() {
        val exception = assertThrows(ContractException::class.java) {
            openApiFromTraffic("Invalid", listOf(namedStub("invalid", HttpRequest(method = "GET"), HttpResponse.ok("ok"))))
        }

        assertThat(exception.message).contains("without the url")
    }

    @Test
    fun `rejects traffic without a response status`() {
        val exception = assertThrows(ContractException::class.java) {
            openApiFromTraffic("Invalid", listOf(namedStub("invalid", HttpRequest("GET", "/items"), HttpResponse())))
        }

        assertThat(exception.message).contains("without a response status")
    }

    @Test
    fun `returns null when the example directory does not exist`(@TempDir tempDir: File) {
        val missingDirectory = tempDir.resolve("missing")

        assertThat(openApiYamlFromExampleDir(missingDirectory, sortOrder = emptyList())).isNull()
    }

    @Test
    fun `infers an openapi operation directly from recorded traffic`() {
        val request = HttpRequest(
            method = "POST",
            path = "/orders/(id:number)",
            headers = mapOf("Content-Type" to "application/json; charset=utf-8", "X-Retry" to "3"),
            body = parsedJSON("""{"name":"coffee","quantity":2}"""),
            queryParams = QueryParameters(mapOf("active" to "true")),
        )
        val response = HttpResponse(
            status = 201,
            headers = mapOf("Content-Type" to "application/json"),
            body = parsedJSON("""{"id":10,"accepted":true}"""),
        )

        val openApi = openApiFromTraffic(
            featureName = "Orders",
            namedStubs = listOf(NamedStub("create order", ScenarioStub(request, response))),
        )

        assertThat(openApi).isNotNull
        val operation = openApi!!.paths["/orders/{id}"]!!.post
        assertThat(operation.parameters.map { it.name }).contains("id", "active", "X-Retry")
        assertThat(operation.requestBody.content).containsKey("application/json")
        assertThat(operation.responses).containsKey("201")
        assertThat(operation.responses["201"]!!.content).containsKey("application/json")
        assertThat(openApi.components.schemas).isNotEmpty
    }

    @Test
    fun `returns null when there is no recorded traffic`() {
        assertThat(openApiFromTraffic("Empty", emptyList())).isNull()
    }

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

    private fun namedStub(name: String, request: HttpRequest, response: HttpResponse): NamedStub =
        NamedStub(name, ScenarioStub(request, response))

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
