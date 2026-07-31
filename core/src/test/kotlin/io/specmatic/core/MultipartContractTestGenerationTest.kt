package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.StringValue
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class MultipartContractTestGenerationTest {
    @Nested
    inner class SpecificationOnly {
        @Test
        fun `binary part generated from specification has no filename`() {
            val part = generatedRequest(feature(format = "binary")).multiPartFormData.single()

            assertThat(part.filename).isNull()
            assertThat(part.contentType).isEqualTo("application/octet-stream")
            assertThat(part.content.toStringLiteral()).isNotBlank()
        }

        @Test
        fun `string part generated from specification has no filename`() {
            val request = generatedRequest(feature())

            assertThat(request.multiPartFormData.single().filename).isNull()
            assertThat(request.multiPartFormData.single().content).isInstanceOf(StringValue::class.java)
        }
    }

    @Nested
    inner class ExternalExamples {
        @Test
        fun `content example generates exact content without a filename`(@TempDir tempDir: File) {
            val loadedFeature = featureWithExternalExample(
                tempDir = tempDir,
                examplePart = MultiPartContentValue(
                    name = "data",
                    content = StringValue("hello"),
                    specifiedContentType = "text/plain",
                ),
            )

            val part = generatedRequest(loadedFeature).multiPartFormData.single()

            assertThat(part.filename).isNull()
            assertThat(part.content).isEqualTo(StringValue("hello"))
            assertThat(part.contentType).isEqualTo("text/plain")
        }

        @Test
        fun `file example generates exact bytes and retains its source path`(@TempDir tempDir: File) {
            val source = tempDir.resolve("fixtures/data.bin").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(0, -1, 10, 13))
            }
            val loadedFeature = featureWithExternalExample(
                tempDir = tempDir,
                format = "binary",
                examplePart = MultiPartContentValue(
                    name = "data",
                    content = BinaryValue(),
                    specifiedContentType = "application/octet-stream",
                    filename = source.absolutePath,
                ),
            )

            val part = generatedRequest(loadedFeature).multiPartFormData.single()

            assertThat(part.filename).isEqualTo(source.absolutePath)
            assertThat(part.content).isEqualTo(BinaryValue(byteArrayOf(0, -1, 10, 13)))
            assertThat(part.contentType).isEqualTo("application/octet-stream")
        }

        @Test
        fun `string filename example generates a filename without replacing specification content`(@TempDir tempDir: File) {
            val loadedFeature = featureWithExternalExample(
                tempDir = tempDir,
                format = "binary",
                examplePart = MultiPartContentValue(
                    name = "data",
                    content = BinaryValue(),
                    specifiedContentType = "application/octet-stream",
                    filename = "(string)",
                ),
            )

            val part = generatedRequest(loadedFeature).multiPartFormData.single()

            assertThat(part.filename).isNotBlank()
            assertThat(part.contentType).isEqualTo("application/octet-stream")
            assertThat(part.content.toStringLiteral()).isNotBlank()
        }
    }

    private fun generatedRequest(feature: Feature): HttpRequest {
        val requests = mutableListOf<HttpRequest>()
        val results = feature.generateContractTests().map { test ->
            test.runTest(
                object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        requests.add(request)
                        return HttpResponse.OK
                    }
                },
            ).result
        }.toList()

        assertThat(results).allSatisfy { assertThat(it.isSuccess()).isTrue() }
        assertThat(requests).hasSize(1)
        return requests.single()
    }

    private fun featureWithExternalExample(
        tempDir: File,
        examplePart: MultiPartContentValue,
        format: String? = null,
    ): Feature {
        val specification = tempDir.resolve("api.yaml").apply {
            writeText(openApi(format))
        }
        tempDir.resolve("api_examples").apply {
            mkdirs()
            val serializedPart = examplePart.toJSONObject().toStringLiteral()
            resolve("example.json").writeText(
                """
                {
                  "http-request": {
                    "method": "POST",
                    "path": "/data",
                    "multipart-formdata": [$serializedPart]
                  },
                  "http-response": {
                    "status": 200
                  }
                }
                """.trimIndent(),
            )
        }

        return OpenApiSpecification.fromFile(specification.path)
            .toFeature()
            .loadExternalisedExamples()
    }

    private fun feature(format: String? = null): Feature =
        OpenApiSpecification.fromYAML(openApi(format), "").toFeature()

    private fun openApi(format: String?): String {
        val formatLine = format?.let { "\n                              format: $it" }.orEmpty()
        return """
            openapi: 3.0.1
            info:
              title: Multipart contract tests
              version: "1"
            paths:
              /data:
                post:
                  requestBody:
                    required: true
                    content:
                      multipart/form-data:
                        schema:
                          type: object
                          required: [data]
                          properties:
                            data:
                              type: string$formatLine
                  responses:
                    "200":
                      description: OK
        """.trimIndent()
    }
}
