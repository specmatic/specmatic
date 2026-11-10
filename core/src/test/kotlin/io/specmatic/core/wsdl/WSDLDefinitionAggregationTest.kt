package io.specmatic.core.wsdl

import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.pattern.ContractException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WSDLDefinitionAggregationTest {
    @TempDir
    lateinit var directory: File

    private val namespace = "urn:loader"
    private val fixture = WSDLNamespaceFixture(namespace, "/loader")

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `split WSDL retains binding and messages when namespaces are shared`(sharedNamespace: Boolean) {
        val full = fullDefinition()
        val functionalNamespace = if (sharedNamespace) namespace else "urn:functional"
        val start = full.indexOf("<wsdl:types>")
        val end = full.indexOf("<wsdl:binding")
        val functional = full.substring(0, start).replace("targetNamespace=\"$namespace\"", "targetNamespace=\"$functionalNamespace\"") +
            full.substring(start, end).replace("message=\"tns:", "message=\"f:") + "</wsdl:definitions>"
        write("functional.wsdl", functional.replace("xmlns:tns=", "xmlns:f=\"$functionalNamespace\" xmlns:tns="))
        val technical = full.substring(0, start).replace("xmlns:tns=", "xmlns:f=\"$functionalNamespace\" xmlns:tns=") +
            """<wsdl:import namespace="$functionalNamespace" location="functional.wsdl"/>""" +
            full.substring(end).replace("type=\"tns:Port\"", "type=\"f:Port\"")

        val scenario = parseContractFileToFeature(write("technical.wsdl", technical)).scenarios.single()
        val request = scenario.generateHttpRequest()
        assertThat(request.path).isEqualTo("/loader")
        assertPayload(request.body.toStringLiteral(), namespace, "Request")
    }

    @ParameterizedTest
    @CsvSource("binding,Binding", "portType,Port", "message,Input")
    fun `conflicting definitions report their identity and both source files`(kind: String, name: String) {
        val duplicate = write("duplicate.wsdl", definitions("""<wsdl:$kind name="$name"/>"""))
        val root = write("root.wsdl", fullDefinition().replace("<wsdl:types>", """
            <wsdl:import namespace="$namespace" location="duplicate.wsdl"/><wsdl:types>
        """))

        val error = assertThrows<ContractException> { parseContractFileToFeature(root) }

        assertThat(error.message).contains(kind, namespace, name, root.canonicalPath, duplicate.canonicalPath)
    }

    @Test
    fun `duplicate declarations within one document retain existing lookup behavior`() {
        val root = write("root.wsdl", fullDefinition().replace("<wsdl:service", """
            <wsdl:message name="Input"/><wsdl:service
        """))

        val scenario = parseContractFileToFeature(root).scenarios.single()

        assertPayload(scenario.generateHttpRequest().body.toStringLiteral(), namespace, "Request")
    }

    @ParameterizedTest
    @ValueSource(strings = ["diamond", "equivalent-path", "cycle", "nested"])
    fun `shared and circular WSDL imports retain each reachable document`(shape: String) {
        val full = fullDefinition()
        val declarations = full.substring(full.indexOf("<wsdl:message"), full.indexOf("<wsdl:binding"))
        write("common.wsdl", definitions(declarations))
        write("left.wsdl", definitions(importWsdl("common.wsdl")))
        val imports = when (shape) {
            "diamond" -> {
                write("right.wsdl", definitions(importWsdl("common.wsdl")))
                importWsdl("left.wsdl") + importWsdl("right.wsdl")
            }
            "equivalent-path" -> {
                directory.resolve("nested").mkdirs()
                importWsdl("common.wsdl") + importWsdl("nested/../common.wsdl")
            }
            "nested" -> {
                write("nested/left.wsdl", definitions(importWsdl("deep/common.wsdl")))
                write("nested/deep/common.wsdl", definitions(declarations))
                importWsdl("nested/left.wsdl")
            }
            else -> {
                write("common.wsdl", definitions(importWsdl("root.wsdl") + declarations))
                importWsdl("common.wsdl")
            }
        }
        val root = write("root.wsdl", full.replace(declarations, "").replace("<wsdl:types>",
            imports + "<wsdl:types>"))

        val scenario = parseContractFileToFeature(root).scenarios.single()

        assertPayload(scenario.generateHttpRequest().body.toStringLiteral(), namespace, "Request")
        val reloaded = parseContractFileToFeature(root).scenarios.single()
        assertPayload(reloaded.generateHttpRequest().body.toStringLiteral(), namespace, "Request")
    }

    private fun importWsdl(location: String): String = """<wsdl:import namespace="$namespace" location="$location"/>"""

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `three documents retain their own prefix bindings and the root endpoint`(localOverride: Boolean) {
        val payloadNamespace = "urn:payload"
        val schema = fixture.schema("""
            <xs:element name="Request" type="xs:string"/>
            <xs:element name="Response" type="xs:string"/>
        """).replace(namespace, payloadNamespace)
        val full = fullDefinition()
        val bindingStart = full.indexOf("<wsdl:binding")
        val serviceStart = full.indexOf("<wsdl:service")
        val abstractDeclarations = full.substring(full.indexOf("<wsdl:message"), bindingStart)
            .replace("element=\"tns:", "element=\"p:")
            .let { declarations ->
                if (localOverride) declarations.replace("<wsdl:part", """<wsdl:part xmlns:p="$payloadNamespace"""")
                else declarations
            }
        val functionalPrefixNamespace = if (localOverride) "urn:shadowed" else payloadNamespace
        write("functional.wsdl", definitions("<wsdl:types>$schema</wsdl:types>" + abstractDeclarations)
            .replace("xmlns:tns=", "xmlns:p=\"$functionalPrefixNamespace\" xmlns:tns="))
        val binding = full.substring(bindingStart, serviceStart).replace("type=\"tns:Port\"", "type=\"p:Port\"")
        write("bindings.wsdl", definitions(importWsdl("functional.wsdl") + binding).replace("xmlns:tns=", """
            xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/" xmlns:p="$namespace" xmlns:tns=
        """.trimIndent()))
        val root = write("root.wsdl", full.substring(0, full.indexOf("<wsdl:types>"))
            .replace("xmlns:tns=", "xmlns:p=\"urn:unused\" xmlns:tns=") +
            importWsdl("bindings.wsdl") + full.substring(serviceStart))

        val scenario = parseContractFileToFeature(root).scenarios.single()
        val request = scenario.generateHttpRequest()

        assertThat(request.path).isEqualTo("/loader")
        assertPayload(request.body.toStringLiteral(), payloadNamespace, "Request")
        val match = scenario.httpRequestPattern.matches(request, scenario.resolver)
        assertThat(match.isSuccess()).describedAs(match.reportString()).isTrue()
    }

    @ParameterizedTest
    @CsvSource("urn:other,Input", "urn:loader,Binding")
    fun `matching local names in different namespaces or definition kinds do not conflict`(otherNamespace: String, messageName: String) {
        write("other.wsdl", definitions("""<wsdl:message name="$messageName"/>""")
            .replace("targetNamespace=\"$namespace\"", "targetNamespace=\"$otherNamespace\""))
        val root = write("root.wsdl", fullDefinition().replace("<wsdl:types>", """
            <wsdl:import namespace="$otherNamespace" location="other.wsdl"/><wsdl:types>
        """))

        val scenario = parseContractFileToFeature(root).scenarios.single()

        assertPayload(scenario.generateHttpRequest().body.toStringLiteral(), namespace, "Request")
    }

    private fun fullDefinition(): String = fixture.wsdl(fixture.schema("""
        <xs:element name="Request" type="xs:string"/>
        <xs:element name="Response" type="xs:string"/>
    """))

    private fun definitions(content: String): String = """
        <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:tns="$namespace" targetNamespace="$namespace">
            $content
        </wsdl:definitions>
    """

    private fun write(name: String, content: String): File = directory.resolve(name).apply {
        parentFile.mkdirs()
        writeText(content)
    }

    private fun assertPayload(xml: String, namespace: String, name: String) {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
        assertThat(document.getElementsByTagNameNS(namespace, name).length).isEqualTo(1)
    }
}
