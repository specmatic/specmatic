package io.specmatic.conversions

import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.NoBodyValue
import io.specmatic.core.Resolver
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.HasException
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class ExampleFromFileTest {
    private fun createTempFile(content: String): File {
        val tempFile = Files.createTempFile("test-example", ".json").toFile()
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        return tempFile
    }

    @Test
    fun `should parse valid example file`() {
        val jsonContent = """
            {
                "name": "test-example",
                "http-request": {
                    "method": "GET",
                    "path": "/api/users?id=123",
                    "headers": {
                        "Content-Type": "application/json"
                    },
                    "body": {
                        "name": "John"
                    }
                },
                "http-response": {
                    "status": 200,
                    "headers": {
                        "Content-Type": "application/json"
                    },
                    "body": {
                        "id": 123,
                        "name": "John"
                    }
                }
            }
        """.trimIndent()

        val file = createTempFile(jsonContent)
        val example = ExampleFromFile.fromFile(file)

        assertThat(example).isInstanceOf(HasValue::class.java)
        val exampleFromFile = (example as HasValue).value

        assertThat(exampleFromFile.testName).isEqualTo("test-example")
        assertThat(exampleFromFile.requestMethod).isEqualTo("GET")
        assertThat(exampleFromFile.requestPath).isEqualTo("/api/users")
        assertThat(exampleFromFile.responseStatus).isEqualTo(200)
        assertThat(exampleFromFile.requestContentType).isEqualTo("application/json")
        assertThat(exampleFromFile.responseContentType).isEqualTo("application/json")
        assertThat(exampleFromFile.queryParams).containsExactlyEntriesOf(mapOf("id" to "123"))
        assertThat(exampleFromFile.request.headers).containsExactlyEntriesOf(mapOf("Content-Type" to "application/json"))
        assertThat(exampleFromFile.isPartial()).isFalse()
        assertThat(exampleFromFile.isInvalid()).isFalse()
    }

    @Test
    fun `should handle partial example`() {
        val jsonContent = """
            {
                "name": "partial-example",
                "partial": {
                    "http-request": {
                        "method": "GET",
                        "path": "/api/users"
                    },
                    "http-response": {
                        "status": 200
                    }
                }
            }
        """.trimIndent()

        val file = createTempFile(jsonContent)
        val example = ExampleFromFile.fromFile(file)

        assertThat(example).isInstanceOf(HasValue::class.java)
        val exampleFromFile = (example as HasValue).value

        assertThat(exampleFromFile.isPartial()).isTrue()
        assertThat(exampleFromFile.requestMethod).isEqualTo("GET")
        assertThat(exampleFromFile.requestPath).isEqualTo("/api/users")
    }

    @Test
    fun `should detect invalid example`() {
        val jsonContent = """
            {
                "name": "invalid-example",
                "http-request": {
                    "headers": {
                        "Content-Type": "application/json"
                    }
                }
            }
        """.trimIndent()

        val file = createTempFile(jsonContent)
        val example = ExampleFromFile.fromFile(file)

        assertThat(example).isInstanceOf(HasException::class.java); example as HasException
        assertThat(example.toFailure().reportString()).isEqualToNormalizingWhitespace("""
        >> ${file.canonicalPath}
        Error loading example due to invalid format. Please correct the format to proceed
        Example should contain http-response/mock-http-response as a top level key.
        """.trimIndent())
    }

    @Test
    fun `should handle empty response`() {
        val jsonContent = """
            {
                "name": "empty-response",
                "http-request": {
                    "method": "GET",
                    "path": "/api/users"
                },
                "http-response": {
                    "status": 204
                }
            }
        """.trimIndent()

        val file = createTempFile(jsonContent)
        val example = ExampleFromFile.fromFile(file)

        assertThat(example).isInstanceOf(HasValue::class.java)
        val exampleFromFile = (example as HasValue).value

        val response = exampleFromFile.response
        assertThat(response.status).isEqualTo(204)
        assertThat(response.body).isInstanceOf(NoBodyValue::class.java)
        assertThat(response.headers).isEmpty()
    }

    @Test
    fun `should handle JSON request and response bodies`() {
        val jsonContent = """
            {
                "name": "json-bodies",
                "http-request": {
                    "method": "POST",
                    "path": "/api/users",
                    "body": {
                        "name": "John",
                        "age": 30,
                        "active": true
                    }
                },
                "http-response": {
                    "status": 201,
                    "body": {
                        "id": 123,
                        "name": "John",
                        "created": true
                    }
                }
            }
        """.trimIndent()

        val file = createTempFile(jsonContent)
        val example = ExampleFromFile.fromFile(file)

        assertThat(example).isInstanceOf(HasValue::class.java)
        val exampleFromFile = (example as HasValue).value

        assertThat(exampleFromFile.requestBody).isNotNull
        assertThat(exampleFromFile.responseBody).isNotNull
    }

    @Test
    fun `should handle string request and response bodies`() {
        val jsonContent = """
            {
                "name": "string-bodies",
                "http-request": {
                    "method": "POST",
                    "path": "/api/users",
                    "body": "plain text request"
                },
                "http-response": {
                    "status": 200,
                    "body": "plain text response"
                }
            }
        """.trimIndent()

        val file = createTempFile(jsonContent)
        val example = ExampleFromFile.fromFile(file)

        assertThat(example).isInstanceOf(HasValue::class.java)
        val exampleFromFile = (example as HasValue).value

        assertThat(exampleFromFile.requestBody).isInstanceOf(StringValue::class.java)
        assertThat(exampleFromFile.responseBody).isInstanceOf(StringValue::class.java)
    }

    @Test
    fun `should handle malformed JSON`() {
        val jsonContent = """
            {
                "name": "malformed-json",
                "http-request": {
                    "method": "GET",
                    "path": "/api/users",
                    "body": {
                        "invalid": json
                    }
                }
            }
        """.trimIndent()

        val file = createTempFile(jsonContent)

        assertThatThrownBy {
            ExampleFromFile(file)
        }.satisfies({
            assertThat(it).isInstanceOf(ContractException::class.java)
        })
    }

    @Test
    fun `should convert to row with SpecmaticConfig`() {
        val jsonContent = """
            {
                "name": "row-conversion",
                "http-request": {
                    "method": "GET",
                    "path": "/api/users?id=123",
                    "headers": {
                        "Content-Type": "application/json"
                    }
                },
                "http-response": {
                    "status": 200,
                    "headers": {
                        "Content-Type": "application/json"
                    },
                    "body": {
                        "id": 123
                    }
                }
            }
        """.trimIndent()

        val file = createTempFile(jsonContent)
        val example = ExampleFromFile.fromFile(file)

        assertThat(example).isInstanceOf(HasValue::class.java)
        val exampleFromFile = (example as HasValue).value

        val specmaticConfig = SpecmaticConfig()
        val row = exampleFromFile.toRow(specmaticConfig)

        assertThat(row.name).isEqualTo("row-conversion")
        assertThat(row.fileSource).isEqualTo(file.canonicalPath)
        assertThat(row.isPartial).isFalse()
    }

    @Test
    fun `it should query params from the url if present`() {
        val example = """
            {
                "http-request": {
                    "method": "GET",
                    "path": "/hello?world=true"
                },
                "http-response": {
                    "status": 200,
                    "body": "ok"
                }
            }
        """.trimIndent().let {

            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        assertThat(example.queryParams).containsExactlyEntriesOf(mapOf("world" to "true"))
    }

    @Test
    fun `it should combine query params from the url if present with those from the block`() {
        val example = """
            {
                "http-request": {
                    "method": "GET",
                    "path": "/query?hello=true",
                    "query": {
                        "world": "true"
                    }
                },
                "http-response": {
                    "status": 200,
                    "body": "ok"
                }
            }
        """.trimIndent().let {

            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        assertThat(example.queryParams).containsExactlyEntriesOf(mapOf("hello" to "true", "world" to "true"))
    }

    @Test
    fun `it should return no body and empty headers if response body and headers are null`() {
        val example = """
        {
            "http-request": {
                "method": "GET",
                "path": "/hello?world=true"
            },
            "http-response": {
                "status": 200
            }
        }
    """.trimIndent().let {
            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        val response = example.response

        assertThat(response.body).isEqualTo(NoBodyValue)
        assertThat(response.headers).isEmpty()
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `it should return a valid HttpRequest with the correct properties`() {
        val example = """
        {
            "http-request": {
                "method": "POST",
                "path": "/api/resource?filter=active",
                "headers": {
                    "Content-Type": "application/json"
                },
                "body": {
                    "key": "value"
                }
            },
            "http-response": {
                "status": 200,
                "body": "ok"
            }
        }
    """.trimIndent().let {
            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        val request = example.request

        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/resource")
        assertThat(request.headers).containsEntry("Content-Type", "application/json")
        assertThat(request.body).isEqualTo(parsedJSONObject("""{"key":"value"}"""))
        assertThat(request.queryParams.asMap()).containsExactlyEntriesOf(mapOf("filter" to "active"))
    }

    @Test
    fun `should load extra header params from additional params file`() {
        val example = """
        {
            "http-request": {
                "method": "POST",
                "path": "/api/resource?filter=active",
                "headers": {
                    "Content-Type": "application/json"
                },
                "body": {
                    "key": "value"
                }
            },
            "http-response": {
                "status": 200,
                "body": "ok"
            }
        }
    """.trimIndent().let {
            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        val row =
            example.toRow(SpecmaticConfig(additionalExampleParamsFilePath = "src/test/resources/additionalParamsFile.json"))

        assertThat(row.requestExample?.headers.orEmpty()).containsEntry("X-Tra", "info")

        val generatedHeaders = HttpHeadersPattern().newBasedOn(row, Resolver()).first().value.generate(Resolver())
        assertThat(generatedHeaders).containsEntry("X-Tra", "info")
    }

    @Test
    fun `should handle query params only in block when no query in URL`() {
        val example = """
            {
                "http-request": {
                    "method": "GET",
                    "path": "/api/users",
                    "query": {
                        "page": "1",
                        "limit": "10"
                    }
                },
                "http-response": {
                    "status": 200,
                    "body": "ok"
                }
            }
        """.trimIndent().let {
            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        assertThat(example.queryParams).containsExactlyEntriesOf(mapOf(
            "page" to "1",
            "limit" to "10"
        ))
    }

    @Test
    fun `should handle empty query block`() {
        val example = """
            {
                "http-request": {
                    "method": "GET",
                    "path": "/api/users?filter=active",
                    "query": {}
                },
                "http-response": {
                    "status": 200,
                    "body": "ok"
                }
            }
        """.trimIndent().let {
            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        assertThat(example.queryParams).containsExactlyEntriesOf(mapOf("filter" to "active"))
    }

    @Test
    fun `should handle empty request headers block`() {
        val example = """
            {
                "http-request": {
                    "method": "GET",
                    "path": "/api/users",
                    "headers": {}
                },
                "http-response": {
                    "status": 200,
                    "body": "ok"
                }
            }
        """.trimIndent().let {
            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        assertThat(example.request.headers).isEmpty()
    }

    @Test
    fun `should handle empty response headers block`() {
        val example = """
            {
                "http-request": {
                    "method": "GET",
                    "path": "/api/users"
                },
                "http-response": {
                    "status": 200,
                    "headers": {},
                    "body": "ok"
                }
            }
        """.trimIndent().let {
            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        val response = example.response
        assertThat(response.headers).isEmpty()
    }

    @Test
    fun `should handle non-content-type response headers`() {
        val example = """
            {
                "http-request": {
                    "method": "GET",
                    "path": "/api/users"
                },
                "http-response": {
                    "status": 200,
                    "headers": {
                        "X-RateLimit-Limit": "100",
                        "X-RateLimit-Remaining": "99",
                        "Cache-Control": "no-cache"
                    },
                    "body": "ok"
                }
            }
        """.trimIndent().let {
            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        val response = example.response
        assertThat(response.headers).containsExactlyEntriesOf(mapOf(
            "X-RateLimit-Limit" to "100",
            "X-RateLimit-Remaining" to "99",
            "Cache-Control" to "no-cache"
        ))
    }

    @Test
    fun `should handle both request and response headers`() {
        val example = """
            {
                "http-request": {
                    "method": "GET",
                    "path": "/api/users",
                    "headers": {
                        "Authorization": "Bearer token123",
                        "Accept": "application/json"
                    }
                },
                "http-response": {
                    "status": 200,
                    "headers": {
                        "X-RateLimit-Limit": "100",
                        "X-RateLimit-Remaining": "99"
                    },
                    "body": "ok"
                }
            }
        """.trimIndent().let {
            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        assertThat(example.request.headers).containsExactlyEntriesOf(mapOf(
            "Authorization" to "Bearer token123",
            "Accept" to "application/json"
        ))

        val response = example.response
        assertThat(response.headers).containsExactlyEntriesOf(mapOf(
            "X-RateLimit-Limit" to "100",
            "X-RateLimit-Remaining" to "99"
        ))
    }

    @Test
    fun `should handle empty headers blocks in both request and response`() {
        val example = """
            {
                "http-request": {
                    "method": "GET",
                    "path": "/api/users",
                    "headers": {}
                },
                "http-response": {
                    "status": 200,
                    "headers": {},
                    "body": "ok"
                }
            }
        """.trimIndent().let {
            ExampleFromFile(parsedJSONObject(it), File("./data.json"))
        }

        assertThat(example.request.headers).isEmpty()
        val response = example.response
        assertThat(response.headers).isEmpty()
    }
}