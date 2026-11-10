package io.specmatic.core.wsdl

import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.utilities.contractStubPaths
import io.specmatic.core.utilities.contractTestPathsFrom
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.FeatureStubsResult
import io.specmatic.stub.HttpStub
import io.specmatic.stub.loadContractStubsFromFilesAsResults
import io.specmatic.test.HttpClient
import io.specmatic.test.TestExecutor
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.validation.SchemaFactory

class WSDLReferenceSchemaContextTest {
    @TempDir
    lateinit var directory: File

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `referenced complex element uses its schema even when the referring prefix is absent`(targetDeclaresPrefix: Boolean) {
        val wsdl = writeSpecification(targetDeclaresPrefix)
        val scenario = parseContractFileToFeature(wsdl).scenarios.single()
        val request = scenario.generateHttpRequest()

        validatePayload(request.body.toStringLiteral())
        val result = scenario.httpRequestPattern.matches(request, scenario.resolver)
        assertThat(result.isSuccess()).describedAs(result.reportString()).isTrue()
        assertThat(request.path).isEqualTo("/reference")
    }

    @ParameterizedTest
    @ValueSource(strings = ["alias", "rebound", "inline-complex", "primitive"])
    fun `references retain caller identity and target type namespace bindings`(variant: String) {
        val wsdl = writeSpecification()
        if (variant == "alias") {
            val types = directory.resolve("types.xsd")
            types.writeText(types.readText().replace("xmlns:c=", "xmlns:caller=").replace("ref=\"c:", "ref=\"caller:"))
        } else if (variant == "rebound") {
            val common = directory.resolve("detail.xsd")
            common.writeText(common.readText()
                .replace("xmlns:xs=", "xmlns:c=\"http://www.w3.org/2001/XMLSchema\" xmlns:own=\"urn:reference:detail\" xmlns:xs=")
                .replace("type=\"xs:string\"", "type=\"c:string\"")
                .replace("type=\"NotesType\"", "type=\"own:NotesType\""))
        } else {
            val common = directory.resolve("detail.xsd")
            val declaration = if (variant == "primitive") """<xs:element name="Notes" type="xs:string"/>""" else """
                <xs:element name="Notes"><xs:complexType><xs:sequence>
                    <xs:element name="Note" type="xs:string" maxOccurs="unbounded"/>
                </xs:sequence></xs:complexType></xs:element>
            """
            common.writeText(common.readText().replace("""<xs:element name="Notes" type="NotesType"/>""", declaration))
        }
        val scenario = parseContractFileToFeature(wsdl).scenarios.single()
        val request = scenario.generateHttpRequest()

        validatePayload(request.body.toStringLiteral())
        val result = scenario.httpRequestPattern.matches(request, scenario.resolver)
        assertThat(result.isSuccess()).describedAs(result.reportString()).isTrue()
    }

    @Test
    fun `undefined reference prefix in the referring schema is still rejected`() {
        val wsdl = writeSpecification()
        val types = directory.resolve("types.xsd")
        types.writeText(types.readText().replace("xmlns:c=\"urn:reference:detail\"", ""))

        val error = assertThrows<ContractException> { parseContractFileToFeature(wsdl) }

        assertThat(error.message).contains("c", "Notes")
    }

    @ParameterizedTest
    @CsvSource("none,true", "repeated,true", "wrong-parent,false", "wrong-child,false", "empty,false")
    fun `referenced element retains namespace and occurrence validation`(variant: String, valid: Boolean) {
        val scenario = parseContractFileToFeature(writeSpecification()).scenarios.single()
        val notes = when (variant) {
            "none" -> ""
            "empty" -> "<c:Notes/>"
            "wrong-parent" -> "<wrong:Notes><c:Note>first</c:Note></wrong:Notes>"
            "wrong-child" -> "<c:Notes><wrong:Note>first</wrong:Note></c:Notes>"
            else -> "<c:Notes><c:Note>first</c:Note><c:Note>second</c:Note></c:Notes>"
        }
        val xml = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            xmlns:t="urn:reference:payload" xmlns:c="urn:reference:detail" xmlns:wrong="urn:wrong">
            <s:Body><t:Request><t:key>example-id</t:key>$notes</t:Request></s:Body>
        </s:Envelope>"""

        if (valid) validatePayload(xml) else assertThrows<SAXException> { validatePayload(xml) }
        val request = scenario.generateHttpRequest().copy(body = StringValue(xml))
        val result = scenario.httpRequestPattern.matches(request, scenario.resolver)
        assertThat(result.isSuccess()).describedAs(result.reportString()).isEqualTo(valid)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `qualification without an attached schema still resolves its QName`(qualified: Boolean) {
        val file = writeSpecification()
        val wsdl = WSDL(toXMLNode(file.readText()), file.canonicalPath)
        val form = if (qualified) "qualified" else "unqualified"
        val element = toXMLNode("""<element xmlns:t="urn:reference:payload" name="Probe" form="$form"/>""")

        val qualification = wsdl.getQualification(element, "t:Probe")

        assertThat(qualification.nodeName.substringAfter(':')).isEqualTo("Probe")
        if (qualified) {
            assertThat(wsdl.allNamespaces()[qualification.namespacePrefix.single()]).isEqualTo("urn:reference:payload")
        } else {
            assertThat(qualification.namespacePrefix).isEmpty()
        }
    }

    @ParameterizedTest
    @CsvSource("missing:Probe,Namespace missing not found", "Probe,did not have a schema attached")
    fun `qualification without a schema retains missing namespace errors`(name: String, errorMessage: String) {
        val file = writeSpecification()
        val wsdl = WSDL(toXMLNode(file.readText()), file.canonicalPath)

        val error = assertThrows<ContractException> { wsdl.getQualification(toXMLNode("<element name=\"Probe\"/>"), name) }

        assertThat(error.message).contains(errorMessage)
    }

    @Test
    fun `v3 mock and contract test share an example containing the referenced complex element`() {
        writeSpecification()
        val port = testPort()
        val baseUrl = "http://127.0.0.1:$port"
        val requestXml = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            xmlns:t="urn:reference:payload" xmlns:c="urn:reference:detail">
            <s:Body><t:Request><t:key>example-id</t:key><c:Notes>
                <c:Note>first</c:Note><c:Note>second</c:Note>
            </c:Notes></t:Request></s:Body></s:Envelope>"""
        val responseXml = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:t="urn:reference:payload">
            <s:Body><t:Response><t:result>EXAMPLE_RESPONSE</t:result></t:Response></s:Body></s:Envelope>"""
        val examples = directory.resolve("examples").apply { mkdirs() }
        examples.resolve("notes.json").writeText(ScenarioStub(
            HttpRequest("POST", "/reference", mapOf("SOAPAction" to "\"/reference/submit\"", "Content-Type" to "text/xml"), StringValue(requestXml)),
            HttpResponse(200, mapOf("Content-Type" to "text/xml"), StringValue(responseXml))
        ).withName("reference-schema-context").toJSON().toStringLiteral())
        val service = """
            definitions:
              - definition:
                  source:
                    filesystem:
                      directory: ${directory.invariantSeparatorsPath}
                  specs:
                    - spec:
                        id: notes
                        path: service.wsdl
            data:
              examples:
                - directories:
                    - ${examples.invariantSeparatorsPath}
            runOptions:
              wsdl:
                specs:
                  - spec:
                      id: notes
                      baseUrl: $baseUrl
        """.trimIndent()
        val configFile = directory.resolve("specmatic.yaml").apply {
            writeText("version: 3\nsystemUnderTest:\n  service:\n${service.prependIndent("    ")}\n" +
                "dependencies:\n  services:\n    - service:\n${service.prependIndent("        ")}\n")
        }
        val config = loadSpecmaticConfig(configFile.canonicalPath)
        val mockPath = contractStubPaths(configFile.canonicalPath).single()
        val testPath = contractTestPathsFrom(configFile.canonicalPath, ".").single()
        assertThat(mockPath.baseUrl).isEqualTo(baseUrl)
        assertThat(testPath.baseUrl).isEqualTo(baseUrl)
        val mock = loadContractStubsFromFilesAsResults(listOf(mockPath), emptyList(), specmaticConfig = config, withImplicitStubs = false)
            .filterIsInstance<FeatureStubsResult.Success>().single()
        val feature = parseContractFileToFeature(testPath.path, specmaticConfig = config,
            exampleDirPaths = testPath.exampleDirPaths.orEmpty()).loadExternalisedExamples()
        val requests = mutableListOf<HttpRequest>()
        val responses = mutableListOf<HttpResponse>()

        val result = HttpStub(mock.feature, mock.scenarioStubs, port = 0, strictMode = true,
            specToStubBaseUrlMap = mapOf(mock.feature.path to mockPath.baseUrl!!)).use {
            HttpClient(testPath.baseUrl!!).use { client ->
                feature.executeTests(object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        requests.add(request)
                        return client.execute(request).also { responses.add(it) }
                    }
                })
            }
        }

        assertThat(result.success()).describedAs(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
        assertThat(requests).hasSize(1)
        assertThat(requests.single().path).isEqualTo("/reference")
        val sent = requests.single().body.toStringLiteral()
        assertThat(sent).contains("example-id", "first", "second")
        validatePayload(sent)
        assertThat(responses).hasSize(1)
        assertThat(responses.single().body.toStringLiteral()).contains("EXAMPLE_RESPONSE")
        assertThat(responses.single().headers["X-Specmatic-Type"]).isNotEqualTo("random")
        validatePayload(responses.single().body.toStringLiteral())
    }

    private fun writeSpecification(targetDeclaresPrefix: Boolean = false): File {
        val prefix = if (targetDeclaresPrefix) "xmlns:c=\"urn:reference:detail\"" else ""
        directory.resolve("detail.xsd").writeText("""
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:reference:detail"
                targetNamespace="urn:reference:detail" elementFormDefault="qualified" $prefix>
                <xs:element name="Notes" type="NotesType"/>
                <xs:complexType name="NotesType"><xs:sequence>
                    <xs:element name="Note" type="xs:string" maxOccurs="unbounded"/>
                </xs:sequence></xs:complexType>
            </xs:schema>
        """.trimIndent())
        directory.resolve("types.xsd").writeText("""
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:reference:payload"
                xmlns:c="urn:reference:detail" targetNamespace="urn:reference:payload" elementFormDefault="qualified">
                <xs:import namespace="urn:reference:detail" schemaLocation="detail.xsd"/>
                <xs:element name="Request"><xs:complexType><xs:sequence>
                    <xs:element name="key" type="xs:string"/>
                    <xs:element ref="c:Notes" minOccurs="0"/>
                </xs:sequence></xs:complexType></xs:element>
                <xs:element name="Response"><xs:complexType><xs:sequence>
                    <xs:element name="result" type="xs:string"/>
                </xs:sequence></xs:complexType></xs:element>
            </xs:schema>
        """.trimIndent())
        val fixture = WSDLNamespaceFixture("urn:reference:payload", "/reference")
        return directory.resolve("service.wsdl").apply {
            writeText(fixture.wsdl("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                    <xs:import namespace="urn:reference:payload" schemaLocation="types.xsd"/>
                </xs:schema>
            """.trimIndent()))
        }
    }

    private fun validatePayload(xml: String) {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
        val body = document.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body").item(0)
        val payload = (0 until body.childNodes.length).map { body.childNodes.item(it) }.filterIsInstance<Element>().single()
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(directory.resolve("types.xsd"))
            .newValidator().validate(DOMSource(payload))
    }
}
