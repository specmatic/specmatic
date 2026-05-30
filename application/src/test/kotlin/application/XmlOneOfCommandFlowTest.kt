package application

import application.validate.OpenApiValidator
import application.validate.ValidateCommand
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.config.Switch
import io.specmatic.core.utilities.StubServerWatcher
import io.specmatic.core.utilities.newXMLBuilder
import io.specmatic.core.value.XMLNode
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.test.SpecmaticJUnitSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.xml.sax.InputSource
import picocli.CommandLine
import java.io.File
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections

class XmlOneOfCommandFlowTest {
    @AfterEach
    fun cleanup() {
        SpecmaticJUnitSupport.settingsStaging.remove()
    }

    @Test
    fun `StubCommand loads inline and external xml oneOf examples as stubs`(@TempDir tempDir: File) {
        val specFile = writeXmlOneOfSpec(
            tempDir.resolve("items.yaml"),
            inlineExamples = listOf(DOCUMENT_EXAMPLE)
        )
        val examplesDir = tempDir.resolve("explicit_examples").apply(File::mkdirs)
        writeXmlOneOfExample(examplesDir, PARCEL_EXAMPLE)

        val httpStubEngine = mockk<HTTPStubEngine>()
        val watcher = mockk<StubServerWatcher>(relaxUnitFun = true)
        val watchMaker = mockk<WatchMaker> {
            every { make(any()) } returns watcher
        }
        val capturedStubs = mutableListOf<List<Pair<io.specmatic.core.Feature, List<ScenarioStub>>>>()
        every {
            httpStubEngine.runHTTPStub(
                stubs = capture(capturedStubs),
                host = any(),
                port = any(),
                keyDataRegistry = any(),
                incomingMtlsRegistry = any(),
                strictMode = any(),
                passThroughTargetBase = any(),
                specmaticConfigSource = any(),
                httpClientFactory = any(),
                workingDirectory = any(),
                gracefulRestartTimeoutInMs = any(),
                specToBaseUrlMap = any(),
                listeners = any(),
                requestHandlers = any()
            )
        } returns mockk<HttpStub> { every { close() } returns Unit }

        val command = StubCommand(
            httpStubEngine = httpStubEngine,
            stubLoaderEngine = StubLoaderEngine(),
            watchMaker = watchMaker
        ).apply {
            registerShutdownHook = false
            hotReload = Switch.enabled
        }

        val exitCode = CommandLine(command).execute(
            specFile.canonicalPath,
            "--examples", examplesDir.canonicalPath,
            "--port", freePort().toString()
        )

        assertThat(exitCode).isZero()
        assertThat(capturedStubs).anySatisfy { invocation ->
            val inlineRootNames = invocation.flatMap { (feature, _) ->
                feature.inlineNamedStubs.map { namedStub -> namedStub.stub.requestRootName() }
            }
            val externalRootNames = invocation.flatMap { (_, stubs) ->
                stubs.map { stub -> stub.requestRootName() }
            }

            assertThat(inlineRootNames).contains("document")
            assertThat(externalRootNames).contains("parcel")
        }
    }

    @Test
    fun `TestCommand runs inline xml oneOf examples as contract tests`(@TempDir tempDir: File) {
        val specFile = writeXmlOneOfSpec(
            tempDir.resolve("items.yaml"),
            inlineExamples = listOf(DOCUMENT_EXAMPLE, PARCEL_EXAMPLE)
        )

        val rootNamesSeen = runXmlEchoServer { baseUrl ->
            val (output, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
                CommandLine(TestCommand()).execute(
                    specFile.canonicalPath,
                    "--testBaseURL", baseUrl,
                    "--strict"
                )
            }

            assertThat(exitCode).withFailMessage(output).isZero()
        }

        assertThat(rootNamesSeen).containsExactlyInAnyOrder("document", "parcel")
    }

    @Test
    fun `TestCommand runs external xml oneOf examples as contract tests`(@TempDir tempDir: File) {
        val specFile = writeXmlOneOfSpec(tempDir.resolve("items.yaml"))
        val examplesDir = tempDir.resolve("items_examples").apply(File::mkdirs)
        writeXmlOneOfExample(examplesDir, DOCUMENT_EXAMPLE)
        writeXmlOneOfExample(examplesDir, PARCEL_EXAMPLE)

        val rootNamesSeen = runXmlEchoServer { baseUrl ->
            val (output, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
                CommandLine(TestCommand()).execute(
                    specFile.canonicalPath,
                    "--testBaseURL", baseUrl,
                    "--examples", examplesDir.canonicalPath,
                    "--strict"
                )
            }

            assertThat(exitCode).withFailMessage(output).isZero()
        }

        assertThat(rootNamesSeen).containsExactlyInAnyOrder("document", "parcel")
    }

    @Test
    fun `ValidateCommand validates inline and external xml oneOf examples`(@TempDir tempDir: File) {
        val specFile = writeXmlOneOfSpec(
            tempDir.resolve("items.yaml"),
            inlineExamples = listOf(DOCUMENT_EXAMPLE)
        )
        val examplesDir = tempDir.resolve("items_examples").apply(File::mkdirs)
        writeXmlOneOfExample(examplesDir, PARCEL_EXAMPLE)

        val (output, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
            CommandLine(validateCommand(tempDir)).execute("--spec-file", specFile.name)
        }

        assertThat(exitCode).withFailMessage(output).isZero()
        assertThat(output)
            .contains("Inline examples (Total: 1)")
            .contains("validating Inline-Example DOCUMENT")
            .contains("Specification examples (Total: 1)")
            .contains("parcel.json")
            .contains("[OK] OVERALL RESULT: PASSED")
    }

    @Test
    fun `ValidateCommand rejects external xml example that matches no oneOf branch`(@TempDir tempDir: File) {
        val specFile = writeXmlOneOfSpec(tempDir.resolve("items.yaml"))
        val examplesDir = tempDir.resolve("items_examples").apply(File::mkdirs)
        writeXmlOneOfExample(
            examplesDir,
            XmlExample("invoice", "INVOICE", "<invoice><id>10</id></invoice>")
        )

        val (output, exitCode) = captureStandardOutput(redirectStdErrToStdout = true) {
            CommandLine(validateCommand(tempDir)).execute("--spec-file", specFile.name)
        }

        assertThat(exitCode).isEqualTo(1)
        assertThat(output)
            .contains("Example has issues")
            .contains("invoice.json")
            .contains("[FAIL] OVERALL RESULT: FAILED")
    }

    private fun validateCommand(tempDir: File): ValidateCommand {
        return ValidateCommand(
            validator = OpenApiValidator(),
            specmaticConfig = io.specmatic.core.SpecmaticConfig(),
            currentDirectoryProvider = { tempDir.canonicalFile }
        )
    }

    private fun runXmlEchoServer(block: (String) -> Unit): List<String> {
        val rootNamesSeen = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestMethod != "POST" || exchange.requestURI.path != "/items") {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }

            val requestBody = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            rootNamesSeen.add(rootElementName(requestBody))

            val responseBytes = requestBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/xml")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }

        try {
            server.start()
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }

        return rootNamesSeen.toList()
    }

    private fun rootElementName(xml: String): String {
        return newXMLBuilder().parse(InputSource(StringReader(xml))).documentElement.nodeName
    }

    private fun ScenarioStub.requestRootName(): String {
        return (requestElsePartialRequest().body as XMLNode).name
    }

    private fun writeXmlOneOfSpec(file: File, inlineExamples: List<XmlExample> = emptyList()): File {
        file.parentFile.mkdirs()
        val spec = """
            openapi: 3.0.3
            info:
              title: XML oneOf API
              version: '1.0'
            paths:
              /items:
                post:
                  requestBody:
                    required: true
                    content:
                      application/xml:
                        schema:
                          oneOf:
                            - ${"$"}ref: '#/components/schemas/document'
                            - ${"$"}ref: '#/components/schemas/parcel'
            {{REQUEST_EXAMPLES}}
                  responses:
                    '200':
                      description: OK
                      content:
                        application/xml:
                          schema:
                            oneOf:
                              - ${"$"}ref: '#/components/schemas/document'
                              - ${"$"}ref: '#/components/schemas/parcel'
            {{RESPONSE_EXAMPLES}}
            components:
              schemas:
                document:
                  type: object
                  xml:
                    name: document
                  required:
                    - id
                  properties:
                    id:
                      type: integer
                parcel:
                  type: object
                  xml:
                    name: parcel
                  required:
                    - trackingNumber
                  properties:
                    trackingNumber:
                      type: string
            """.trimIndent()
            .replace("{{REQUEST_EXAMPLES}}", inlineExamples.toYamlExamplesSection(12))
            .replace("{{RESPONSE_EXAMPLES}}", inlineExamples.toYamlExamplesSection(14))

        file.writeText(spec)
        return file
    }

    private fun List<XmlExample>.toYamlExamplesSection(indent: Int): String {
        if (isEmpty()) return ""

        val indentation = " ".repeat(indent)
        val nestedIndentation = " ".repeat(indent + 2)
        val valueIndentation = " ".repeat(indent + 4)
        return buildString {
            appendLine("${indentation}examples:")
            this@toYamlExamplesSection.forEach { example ->
                appendLine("$nestedIndentation${example.inlineName}:")
                appendLine("${valueIndentation}value: |")
                appendLine("$valueIndentation  ${example.xml}")
            }
        }.trimEnd()
    }

    private fun writeXmlOneOfExample(examplesDir: File, example: XmlExample) {
        examplesDir.resolve("${example.fileName}.json").writeText(
            """
            {
              "http-request": {
                "method": "POST",
                "path": "/items",
                "headers": {
                  "Content-Type": "application/xml"
                },
                "body": "${example.xml}"
              },
              "http-response": {
                "status": 200,
                "headers": {
                  "Content-Type": "application/xml"
                },
                "body": "${example.xml}"
              }
            }
            """.trimIndent()
        )
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private data class XmlExample(val fileName: String, val inlineName: String, val xml: String)

    private companion object {
        val DOCUMENT_EXAMPLE = XmlExample("document", "DOCUMENT", "<document><id>10</id></document>")
        val PARCEL_EXAMPLE = XmlExample("parcel", "PARCEL", "<parcel><trackingNumber>ABC123</trackingNumber></parcel>")
    }
}
