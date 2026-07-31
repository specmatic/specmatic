package io.specmatic.core.utilities

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.MultiPartContentValue
import io.specmatic.core.MultiPartFileValue
import io.specmatic.core.MultiPartFormDataValue
import io.specmatic.core.NamedStub
import io.specmatic.core.NoBodyValue
import io.specmatic.core.QueryParameters
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.mock.ScenarioStub
import io.specmatic.proxy.ProxyOperation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.OpenAPIV3Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

class OpenApiYamlFromExampleDirTest {
    @Test
    fun `omits a request body for the default empty body`() {
        val openApi = openApiFromTraffic(
            "Empty body",
            listOf(namedStub("empty", HttpRequest("GET", "/empty"), HttpResponse.ok("done"))),
        )!!

        assertThat(openApi.paths["/empty"]!!.get.requestBody).isNull()
    }

    @Test
    fun `omits response content for the default empty body`() {
        val openApi = openApiFromTraffic(
            "Empty body",
            listOf(namedStub("empty", HttpRequest("GET", "/empty"), HttpResponse(204))),
        )!!

        assertThat(openApi.paths["/empty"]!!.get.responses["204"]!!.content).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("topLevelArrayFieldCases")
    fun `converges optional fields across objects in a top level array`(description: String, body: String) {
        val openApi = openApiFromTraffic(
            "Items",
            listOf(namedStub(
                "create items",
                HttpRequest("POST", "/items", body = parsedJSON(body)),
                HttpResponse.ok("created"),
            )),
        )!!

        val requestSchema = openApi.paths["/items"]!!.post.requestBody.content["application/json"]!!.schema
        val itemSchemaName = requestSchema.items.`$ref`.substringAfterLast('/')
        val itemSchema = openApi.components.schemas[itemSchemaName]!!
        assertThat(requestSchema.type).isEqualTo("array")
        assertThat(itemSchema.properties.keys).containsExactly("id", "enabled")
        assertThat(itemSchema.required).contains("id").doesNotContain("enabled")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nestedArrayCases")
    fun `converges empty and populated nested arrays in either order`(description: String, body: String) {
        val openApi = openApiFromTraffic(
            "Groups",
            listOf(namedStub(
                "create groups",
                HttpRequest("POST", "/groups", body = parsedJSON(body)),
                HttpResponse.ok("created"),
            )),
        )!!

        val requestSchema = openApi.paths["/groups"]!!.post.requestBody.content["application/json"]!!.schema
        val groupSchema = openApi.components.schemas[requestSchema.items.`$ref`.substringAfterLast('/')]!!
        val membersSchema = groupSchema.properties["members"]!!
        val memberSchema = openApi.components.schemas[membersSchema.items.`$ref`.substringAfterLast('/')]!!
        assertThat(membersSchema.type).isEqualTo("array")
        assertThat(memberSchema.properties.keys).containsExactly("id")
    }

    @Test
    fun `keeps repeated nested component names distinct in a request body`() {
        val openApi = openApiFromTraffic(
            "Entries",
            listOf(namedStub(
                "create entries",
                HttpRequest(
                    "POST",
                    "/data",
                    body = parsedJSON("""{"entries":[{"name":"James"}],"data":{"entries":[{"id":10}]}}"""),
                ),
                HttpResponse.ok(parsedJSON("""{"operationId":10}""")),
            )),
        )!!

        val requestSchema = openApi.components.schemas.entries.single { (name, _) -> name.contains("RequestBody") }.value
        val outerEntriesReference = requestSchema.properties["entries"]!!.items.`$ref`
        val dataSchema = openApi.components.schemas[requestSchema.properties["data"]!!.`$ref`.substringAfterLast('/')]!!
        val nestedEntriesReference = dataSchema.properties["entries"]!!.items.`$ref`
        val outerEntriesSchema = openApi.components.schemas[outerEntriesReference.substringAfterLast('/')]!!
        val nestedEntriesSchema = openApi.components.schemas[nestedEntriesReference.substringAfterLast('/')]!!

        assertThat(outerEntriesReference).isNotEqualTo(nestedEntriesReference)
        assertThat(outerEntriesSchema.properties.keys).containsExactly("name")
        assertThat(nestedEntriesSchema.properties.keys).containsExactly("id")
    }

    @Test
    fun `keeps repeated nested component names distinct in a response body`() {
        val openApi = openApiFromTraffic(
            "Entries",
            listOf(namedStub(
                "list entries",
                HttpRequest("POST", "/data"),
                HttpResponse.ok(parsedJSON("""{"entries":[{"name":"James"}],"data":{"entries":[{"id":10}]}}""")),
            )),
        )!!

        val responseSchema = openApi.components.schemas.entries.single { (name, _) -> name.contains("ResponseBody") }.value
        val outerEntriesReference = responseSchema.properties["entries"]!!.items.`$ref`
        val dataSchema = openApi.components.schemas[responseSchema.properties["data"]!!.`$ref`.substringAfterLast('/')]!!
        val nestedEntriesReference = dataSchema.properties["entries"]!!.items.`$ref`
        val outerEntriesSchema = openApi.components.schemas[outerEntriesReference.substringAfterLast('/')]!!
        val nestedEntriesSchema = openApi.components.schemas[nestedEntriesReference.substringAfterLast('/')]!!

        assertThat(outerEntriesReference).isNotEqualTo(nestedEntriesReference)
        assertThat(outerEntriesSchema.properties.keys).containsExactly("name")
        assertThat(nestedEntriesSchema.properties.keys).containsExactly("id")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullableObjectPropertyCases")
    fun `converges null and concrete object properties in recorded order`(
        description: String,
        body: String,
        expectedOptional: Boolean,
    ) {
        val openApi = openApiFromTraffic(
            "Nullable",
            listOf(namedStub(
                "nullable objects",
                HttpRequest("POST", "/nullable", body = parsedJSON(body)),
                HttpResponse.ok("done"),
            )),
        )!!

        val requestSchema = openApi.paths["/nullable"]!!.post.requestBody.content["application/json"]!!.schema
        val itemSchema = openApi.components.schemas[requestSchema.items.`$ref`.substringAfterLast('/')]!!
        val nameSchema = itemSchema.properties["name"]!!

        assertThat(nameSchema.type).isEqualTo("string")
        assertThat(nameSchema.nullable).isTrue()
        assertThat(itemSchema.required?.contains("name") == true).isEqualTo(!expectedOptional)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompatibleArrayElementCases")
    fun `uses the first array element type when element shapes are incompatible`(
        description: String,
        body: String,
        expectedType: String,
    ) {
        val openApi = openApiFromTraffic(
            "Incompatible elements",
            listOf(namedStub(
                "incompatible elements",
                HttpRequest("POST", "/values", body = parsedJSON(body)),
                HttpResponse.ok("done"),
            )),
        )!!

        val requestSchema = openApi.paths["/values"]!!.post.requestBody.content["application/json"]!!.schema
        assertThat(requestSchema.items.type).isEqualTo(expectedType)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompatibleArrayPropertyCases")
    fun `uses the first property shape when array object properties are incompatible`(
        description: String,
        body: String,
        expectedType: String,
    ) {
        val openApi = openApiFromTraffic(
            "Incompatible properties",
            listOf(namedStub(
                "incompatible properties",
                HttpRequest("POST", "/values", body = parsedJSON(body)),
                HttpResponse.ok("done"),
            )),
        )!!

        val requestSchema = openApi.paths["/values"]!!.post.requestBody.content["application/json"]!!.schema
        val itemSchema = openApi.components.schemas[requestSchema.items.`$ref`.substringAfterLast('/')]!!
        assertThat(itemSchema.properties["value"]!!.type).isEqualTo(expectedType)
    }

    @Test
    fun `removes the query suffix from the operation summary and response description`() {
        val openApi = openApiFromTraffic(
            "Query suffix",
            listOf(namedStub(
                "http://localhost?a=b",
                HttpRequest("GET", "/data", queryParametersMap = mapOf("id" to "10")),
                HttpResponse.OK,
            )),
        )!!

        val operation = openApi.paths["/data"]!!.get
        val queryParameter = operation.parameters.single { it.`in` == "query" && it.name == "id" }

        assertThat(operation.summary).isEqualTo("http://localhost")
        assertThat(operation.responses["200"]!!.description).isEqualTo("http://localhost")
        assertThat(queryParameter.schema.type).isEqualTo("integer")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("multipartCases")
    fun `currently rejects multipart traffic when generating OpenAPI`(
        description: String,
        multiPartFormDataValue: MultiPartFormDataValue,
    ) {
        val request = HttpRequest(
            method = "POST",
            path = "/customer",
            multiPartFormData = listOf(multiPartFormDataValue),
        )

        val exception = assertThrows(NotImplementedError::class.java) {
            openApiFromTraffic(
                "Multipart",
                listOf(namedStub("multipart", request, HttpResponse.ok("done"))),
            )
        }

        assertThat(exception.message).isEqualTo("multipart form data not yet supported")
    }

    @Test
    fun `infers empty arrays without creating a dangling component reference`() {
        val openApi = openApiFromTraffic(
            "Items",
            listOf(namedStub(
                "create items",
                HttpRequest("POST", "/items", body = parsedJSON("[]")),
                HttpResponse.ok("created"),
            )),
        )!!

        val schema = openApi.paths["/items"]!!.post.requestBody.content["application/json"]!!.schema
        assertThat(schema.type).isEqualTo("array")
        assertThat(schema.items.type).isEqualTo("string")
        assertThat(schema.`$ref`).isNull()
    }

    @Test
    fun `omits a request body for NoBodyValue`() {
        val openApi = openApiFromTraffic(
            "No body",
            listOf(namedStub(
                "without body",
                HttpRequest("POST", "/actions", body = NoBodyValue),
                HttpResponse.ok("done"),
            )),
        )!!

        assertThat(openApi.paths["/actions"]!!.post.requestBody).isNull()
    }

    @Test
    fun `preserves the recorded response behavior for NoBodyValue`() {
        val openApi = openApiFromTraffic(
            "No body",
            listOf(namedStub(
                "without response body",
                HttpRequest("GET", "/actions"),
                HttpResponse(204, headers = emptyMap(), body = NoBodyValue, externalisedResponseCommand = ""),
            )),
        )!!

        val schema = openApi.paths["/actions"]!!.get.responses["204"]!!.content["text/plain"]!!.schema
        assertThat(schema.enum).containsExactly("No body")
    }

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

    @ParameterizedTest
    @MethodSource("scalarBodyCases")
    fun `infers each scalar request body type`(body: Value, expectedType: String) {
        val openApi = openApiFromTraffic(
            "Scalar",
            listOf(namedStub(
                "echo",
                HttpRequest("POST", "/echo", body = body),
                HttpResponse.ok("done"),
            )),
        )!!

        val schema = openApi.paths["/echo"]!!.post.requestBody.content["text/plain"]!!.schema
        assertThat(schema.type).isEqualTo(expectedType)
        assertThat(schema.`$ref`).isNull()
    }

    @Test
    fun `infers decimal request bodies as number schemas`() {
        val openApi = openApiFromTraffic(
            "Decimal",
            listOf(namedStub(
                "decimal",
                HttpRequest("POST", "/decimal", body = NumberValue(1.5)),
                HttpResponse.ok("done"),
            )),
        )!!

        val schema = openApi.paths["/decimal"]!!.post.requestBody.content["text/plain"]!!.schema
        assertThat(schema.type).isEqualTo("number")
    }

    @Test
    fun `infers null object fields as nullable schemas`() {
        val openApi = openApiFromTraffic(
            "Nullable",
            listOf(namedStub(
                "nullable object",
                HttpRequest("POST", "/nullable", body = parsedJSON("""{"id":1,"note":null}""")),
                HttpResponse.ok("done"),
            )),
        )!!

        val requestSchema = openApi.components.schemas.entries.single { (name, _) -> name.contains("RequestBody") }.value
        assertThat(requestSchema.properties["note"]!!.nullable).isTrue()
    }

    @Test
    fun `infers arrays containing null and a value as nullable item schemas`() {
        val openApi = openApiFromTraffic(
            "Nullable",
            listOf(namedStub(
                "nullable array",
                HttpRequest("POST", "/nullable", body = parsedJSON("""[null,"value"]""")),
                HttpResponse.ok("done"),
            )),
        )!!

        val schema = openApi.paths["/nullable"]!!.post.requestBody.content["application/json"]!!.schema
        assertThat(schema.items.nullable).isTrue()
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
    fun `infers non-string scalar types for header and query parameters`() {
        val request = HttpRequest(
            method = "GET",
            path = "/search",
            headers = mapOf(
                "X-Count" to "10",
                "X-Ratio" to "1.5",
                "X-Enabled" to "true",
            ),
            queryParams = QueryParameters(
                mapOf(
                    "count" to "10",
                    "ratio" to "1.5",
                    "enabled" to "true",
                ),
            ),
        )

        val openApi = openApiFromTraffic(
            "Search",
            listOf(namedStub("search", request, HttpResponse.ok("done"))),
        )!!
        val parameters = openApi.paths["/search"]!!.get.parameters

        assertThat(parameters.single { it.`in` == "header" && it.name == "X-Count" }.schema.type).isEqualTo("integer")
        assertThat(parameters.single { it.`in` == "header" && it.name == "X-Ratio" }.schema.type).isEqualTo("number")
        assertThat(parameters.single { it.`in` == "header" && it.name == "X-Enabled" }.schema.type).isEqualTo("boolean")
        assertThat(parameters.single { it.`in` == "query" && it.name == "count" }.schema.type).isEqualTo("integer")
        assertThat(parameters.single { it.`in` == "query" && it.name == "ratio" }.schema.type).isEqualTo("number")
        assertThat(parameters.single { it.`in` == "query" && it.name == "enabled" }.schema.type).isEqualTo("boolean")
    }

    @Test
    fun `infers header optionality and preserves optional query parameters across recordings`() {
        val firstRequest = HttpRequest(
            method = "GET",
            path = "/search",
            headers = mapOf("X-Mandatory" to "one", "X-Optional" to "present"),
            queryParams = QueryParameters(mapOf("mandatory" to "one", "optional" to "present")),
        )
        val secondRequest = HttpRequest(
            method = "GET",
            path = "/search",
            headers = mapOf("X-Mandatory" to "two"),
            queryParams = QueryParameters(mapOf("mandatory" to "two")),
        )

        val openApi = openApiFromTraffic(
            "Search",
            listOf(
                namedStub("search", firstRequest, HttpResponse.ok("done")),
                namedStub("search", secondRequest, HttpResponse.ok("done")),
            ),
        )!!
        val parameters = openApi.paths["/search"]!!.get.parameters

        assertThat(parameters.single { it.name == "X-Mandatory" }.required).isTrue()
        assertThat(parameters.single { it.name == "X-Optional" }.required).isFalse()
        assertThat(parameters.single { it.name == "mandatory" }.required != true).isTrue()
        assertThat(parameters.single { it.name == "optional" }.required != true).isTrue()
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
    fun `preserves the root path in the generated operation`() {
        val openApi = openApiFromTraffic(
            "Root",
            listOf(namedStub("root", HttpRequest("GET", "/"), HttpResponse.ok("done"))),
        )!!

        assertThat(openApi.paths.keys).containsExactly("/")
        assertThat(openApi.paths["/"]!!.get).isNotNull()
    }

    @ParameterizedTest
    @MethodSource("scalarBodyCases")
    fun `infers each scalar response body type`(body: Value, expectedType: String) {
        val openApi = openApiFromTraffic(
            "Scalar",
            listOf(namedStub(
                "result",
                HttpRequest("GET", "/result"),
                HttpResponse(200, body = body),
            )),
        )!!

        val schema = openApi.paths["/result"]!!.get.responses["200"]!!.content["text/plain"]!!.schema
        assertThat(schema.type).isEqualTo(expectedType)
        assertThat(schema.`$ref`).isNull()
    }

    @Test
    fun `infers a top level array response body`() {
        val openApi = openApiFromTraffic(
            "Items",
            listOf(namedStub(
                "items",
                HttpRequest("GET", "/items"),
                HttpResponse(200, body = parsedJSON("""[{"id":1},{"id":2}]""")),
            )),
        )!!

        val responseSchema = openApi.paths["/items"]!!.get.responses["200"]!!.content["application/json"]!!.schema
        val itemSchema = openApi.components.schemas[responseSchema.items.`$ref`.substringAfterLast('/')]!!
        assertThat(responseSchema.type).isEqualTo("array")
        assertThat(itemSchema.type).isEqualTo("object")
        assertThat(itemSchema.properties["id"]!!.type).isEqualTo("integer")
    }

    @Test
    fun `infers mandatory and optional response headers across recordings`() {
        val firstResponse = HttpResponse(
            200,
            headers = mapOf("X-Mandatory" to "one", "X-Optional" to "present"),
        )
        val secondResponse = HttpResponse(
            200,
            headers = mapOf("X-Mandatory" to "two"),
        )

        val openApi = openApiFromTraffic(
            "Results",
            listOf(
                namedStub("result", HttpRequest("GET", "/result"), firstResponse),
                namedStub("result", HttpRequest("GET", "/result"), secondResponse),
            ),
        )!!
        val headers = openApi.paths["/result"]!!.get.responses["200"]!!.headers

        assertThat(headers["X-Mandatory"]!!.required).isTrue()
        assertThat(headers["X-Optional"]!!.required).isFalse()
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

    companion object {
        @JvmStatic
        private fun topLevelArrayFieldCases(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "field is present in the second object",
                """[{"id":1},{"id":2,"enabled":true}]""",
            ),
            Arguments.of(
                "field is present in the first object",
                """[{"id":1,"enabled":true},{"id":2}]""",
            ),
        )

        @JvmStatic
        private fun nestedArrayCases(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "empty array is observed first",
                """[{"members":[]},{"members":[{"id":1}]}]""",
            ),
            Arguments.of(
                "populated array is observed first",
                """[{"members":[{"id":1}]},{"members":[]}]""",
            ),
        )

        @JvmStatic
        private fun nullableObjectPropertyCases(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "concrete string followed by null",
                """[{"name":"John Doe"},{"name":null}]""",
                false,
            ),
            Arguments.of(
                "null followed by concrete string",
                """[{"name":null},{"name":"John Doe"}]""",
                false,
            ),
            Arguments.of(
                "null followed by concrete string followed by null",
                """[{"name":null},{"name":"John Doe"},{"name":null}]""",
                false,
            ),
            Arguments.of(
                "missing field followed by null and concrete string",
                """[{},{"name":null},{"name":"John Doe"}]""",
                true,
            ),
        )

        @JvmStatic
        private fun incompatibleArrayElementCases(): Stream<Arguments> = Stream.of(
            Arguments.of("string is observed before integer", """["one",2]""", "string"),
            Arguments.of("integer is observed before string", """[2,"one"]""", "integer"),
        )

        @JvmStatic
        private fun incompatibleArrayPropertyCases(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "empty array property is observed before integer property",
                """[{"value":[]},{"value":2}]""",
                "array",
            ),
            Arguments.of(
                "integer property is observed before empty array property",
                """[{"value":2},{"value":[]}]""",
                "integer",
            ),
        )

        @JvmStatic
        private fun multipartCases(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "multipart content",
                MultiPartContentValue("name", StringValue("John Doe")),
            ),
            Arguments.of(
                "multipart file",
                MultiPartFileValue("customer_csv", "customer.csv", "text/csv", "identity"),
            ),
        )

        @JvmStatic
        private fun scalarBodyCases(): Stream<Arguments> = Stream.of(
            Arguments.of(StringValue("hello"), "string"),
            Arguments.of(NumberValue(10), "integer"),
            Arguments.of(BooleanValue(true), "boolean"),
        )
    }
}
