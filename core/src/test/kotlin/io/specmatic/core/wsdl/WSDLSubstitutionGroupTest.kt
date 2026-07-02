package io.specmatic.core.wsdl

import io.ktor.http.ContentType
import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.core.value.StringValue
import io.specmatic.stub.FeatureStubsResult
import io.specmatic.stub.HttpStub
import io.specmatic.stub.loadContractStubsFromFilesAsResults
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLSubstitutionGroupTest {
    @Test
    fun `mock accepts substitution members for an abstract imported head element`(@TempDir tempDir: File) {
        val wsdlFile = writePetService(tempDir, abstractHead = true)
        val feature = parseContractFileToFeature(wsdlFile)

        HttpStub(feature).use { stub ->
            val dogResponse = stub.client.execute(registerPetRequest("Dog", "Barkley", "Collie"))
            val catResponse = stub.client.execute(registerPetRequest("Cat", "Misty", "Tabby"))
            val petResponse = stub.client.execute(registerPetRequest("Pet", "Base", "Ignored"))

            assertThat(dogResponse.status).isEqualTo(200)
            assertThat(catResponse.status).isEqualTo(200)
            assertThat(petResponse.status).isEqualTo(400)
        }
    }

    @Test
    fun `mock rejects abstract substitution group head declared in the same schema`(@TempDir tempDir: File) {
        val wsdlFile = writeInlinePetService(tempDir, abstractHead = true)
        val feature = parseContractFileToFeature(wsdlFile)

        HttpStub(feature).use { stub ->
            val dogResponse = stub.client.execute(registerPetRequest("Dog", "Barkley", "Collie", "tns", """/pets/registerPet"""))
            val catResponse = stub.client.execute(registerPetRequest("Cat", "Misty", "Tabby", "tns", """/pets/registerPet"""))
            val petResponse = stub.client.execute(registerPetRequest("Pet", "Base", "Ignored", "tns", """/pets/registerPet"""))

            assertThat(dogResponse.status).isEqualTo(200)
            assertThat(catResponse.status).isEqualTo(200)
            assertThat(petResponse.status).isEqualTo(400)
        }
    }

    @Test
    fun `mock rejects abstract substitution group head when element and type have the same name`(@TempDir tempDir: File) {
        val wsdlFile = writeInlinePetService(tempDir, abstractHead = true, elementAndTypeShareNames = true)
        val feature = parseContractFileToFeature(wsdlFile)

        HttpStub(feature).use { stub ->
            val dogResponse = stub.client.execute(registerPetRequest("Dog", "Barkley", "Collie", "tns", """/pets/registerPet"""))
            val catResponse = stub.client.execute(registerPetRequest("Cat", "Misty", "Tabby", "tns", """/pets/registerPet"""))
            val petResponse = stub.client.execute(registerPetRequest("Pet", "Base", "Ignored", "tns", """/pets/registerPet"""))

            assertThat(dogResponse.status).isEqualTo(200)
            assertThat(catResponse.status).isEqualTo(200)
            assertThat(petResponse.status).isEqualTo(400)
        }
    }

    @Test
    fun `mock rejects abstract substitution group head in default namespace wsdl`(@TempDir tempDir: File) {
        val wsdlFile = writeDemoStylePetService(tempDir)
        val feature = parseContractFileToFeature(wsdlFile)

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(registerPetRequest("Pet", "Base", "Ignored", "tns", """/pets/registerPet""", lowerCaseChildElementNames = true))
        }

        assertThat(response.status).isEqualTo(400)
        assertThat(response.headers["X-Specmatic-Type"]).isNotEqualTo("random")
    }

    @Test
    fun `mock with external examples rejects abstract substitution group head`(@TempDir tempDir: File) {
        val wsdlFile = writeDemoStylePetService(tempDir)
        val examplesDir = tempDir.resolve("pet_examples").apply { mkdirs() }
        val dogRequest = registerPetRequest("Dog", "Barkley", "Collie", "tns", """/pets/registerPet""", lowerCaseChildElementNames = true)
        val catRequest = registerPetRequest("Cat", "Misty", "Tabby", "tns", """/pets/registerPet""", lowerCaseChildElementNames = true)
        val petRequest = registerPetRequest("Pet", "Base", "Ignored", "tns", """/pets/registerPet""", lowerCaseChildElementNames = true)
        writeScenarioStub(examplesDir.resolve("dog.json"), "registerDog", dogRequest, registerPetResponse(101))
        writeScenarioStub(examplesDir.resolve("cat.json"), "registerCat", catRequest, registerPetResponse(202))
        val loadedStubs = loadContractStubsFromFilesAsResults(
            listOf(ContractPathData("", wsdlFile.path, exampleDirPaths = listOf(examplesDir.path))),
            dataDirPaths = emptyList(),
            specmaticConfig = SpecmaticConfig(),
            withImplicitStubs = false,
        ).filterIsInstance<FeatureStubsResult.Success>().single()

        HttpStub(loadedStubs.feature, loadedStubs.scenarioStubs).use { stub ->
            val dogResponse = stub.client.execute(dogRequest)
            val catResponse = stub.client.execute(catRequest)
            val petResponse = stub.client.execute(petRequest)

            assertThat(dogResponse.status).isEqualTo(200)
            assertThat(catResponse.status).isEqualTo(200)
            assertThat(petResponse.status).isEqualTo(400)
            assertThat(petResponse.headers["X-Specmatic-Type"]).isNotEqualTo("random")
        }
    }

    @Test
    fun `mock accepts the head element when the substitution group head is not abstract`(@TempDir tempDir: File) {
        val wsdlFile = writePetService(tempDir, abstractHead = false)
        val feature = parseContractFileToFeature(wsdlFile)

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(registerPetRequest("Pet", "Base", "Ignored"))
        }

        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `invalid substitution member reports substitutionGroup in the request mismatch`(@TempDir tempDir: File) {
        val wsdlFile = writePetService(tempDir, abstractHead = true)
        val feature = parseContractFileToFeature(wsdlFile)
        val scenario = feature.scenarios.single()

        val result = scenario.httpRequestPattern.matches(
            registerPetRequest("Crocodile", "Snap", "Marsh"),
            Resolver(newPatterns = scenario.patterns)
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).toFailureReport().toText())
            .contains("substitutionGroup")
            .contains("{http://example.com/animals}Pet")
            .contains("Dog")
            .contains("Cat")
    }

    @Test
    fun `contract generation emits concrete substitution members for an abstract head`(@TempDir tempDir: File) {
        val wsdlFile = writePetService(tempDir, abstractHead = true)
        val feature = parseContractFileToFeature(wsdlFile)

        val generatedBodies = feature.scenarios.single()
            .generateTestScenarios(feature.flagsBased)
            .map { it.value.generateHttpRequest(feature.flagsBased).body.toStringLiteral() }
            .toList()

        assertThat(generatedBodies).hasSize(2)
        assertThat(generatedBodies).anySatisfy {
            assertThat(it).contains("Dog").doesNotContain("<animals:Pet")
        }
        assertThat(generatedBodies).anySatisfy {
            assertThat(it).contains("Cat").doesNotContain("<animals:Pet")
        }
    }

    @Test
    fun `abstract substitution group head without members fails clearly`(@TempDir tempDir: File) {
        val wsdlFile = writePetService(tempDir, abstractHead = true, includeSubstitutes = false)

        val exception = assertThrows<Exception> {
            parseContractFileToFeature(wsdlFile)
        }

        assertThat(exception.message)
            .contains("substitutionGroup")
            .contains("{http://example.com/animals}Pet")
    }

    private fun writePetService(
        tempDir: File,
        abstractHead: Boolean,
        includeSubstitutes: Boolean = true
    ): File {
        tempDir.resolve("animals.xsd").writeText(animalsSchema(abstractHead, includeSubstitutes))
        return tempDir.resolve("pet-service.wsdl").apply {
            writeText(petServiceWsdl())
        }
    }

    private fun registerPetRequest(
        elementName: String,
        petName: String,
        detail: String,
        petPrefix: String = "animals",
        soapAction: String = "http://example.com/pets/registerPet",
        lowerCaseChildElementNames: Boolean = false,
    ): HttpRequest {
        val nameElement = if (lowerCaseChildElementNames) "name" else "Name"
        val breedElement = if (lowerCaseChildElementNames) "breed" else "Breed"
        val colorElement = if (lowerCaseChildElementNames) "color" else "Color"
        val habitatElement = if (lowerCaseChildElementNames) "habitat" else "Habitat"
        val detailElement = when (elementName) {
            "Dog" -> "<$petPrefix:$breedElement>$detail</$petPrefix:$breedElement>"
            "Cat" -> "<$petPrefix:$colorElement>$detail</$petPrefix:$colorElement>"
            "Pet" -> ""
            else -> "<$petPrefix:$habitatElement>$detail</$petPrefix:$habitatElement>"
        }

        return HttpRequest(
            method = "POST",
            path = "/pets",
            headers = mapOf(
                "SOAPAction" to "\"$soapAction\"",
                CONTENT_TYPE to ContentType.Text.Xml.toString(),
            ),
            body = StringValue(
                """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tns="http://example.com/pets" xmlns:animals="http://example.com/animals">
                  <soapenv:Body>
                    <tns:RegisterPet>
                      <$petPrefix:$elementName>
                        <$petPrefix:$nameElement>$petName</$petPrefix:$nameElement>
                        $detailElement
                      </$petPrefix:$elementName>
                    </tns:RegisterPet>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.trimIndent()
            )
        )
    }

    private fun registerPetResponse(registrationId: Int): HttpResponse =
        HttpResponse(
            status = 200,
            headers = mapOf(CONTENT_TYPE to ContentType.Text.Xml.toString()),
            body = StringValue(
                """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                  <soapenv:Body>
                    <tns:RegisterPetResponse xmlns:tns="http://example.com/pets">
                      <tns:registrationId>$registrationId</tns:registrationId>
                      <tns:status>REGISTERED</tns:status>
                    </tns:RegisterPetResponse>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.trimIndent()
            )
        )

    private fun writeScenarioStub(file: File, name: String, request: HttpRequest, response: HttpResponse) {
        file.writeText(
            """
            {
              "name": "$name",
              "http-request": {
                "method": "${request.method}",
                "path": "${request.path}",
                "headers": {
                  "SOAPAction": "${request.headers.getValue("SOAPAction")}",
                  "Content-Type": "${request.headers.getValue(CONTENT_TYPE)}"
                },
                "body": ${request.body.toStringLiteral().jsonString()}
              },
              "http-response": {
                "status": ${response.status},
                "headers": {
                  "Content-Type": "${response.headers.getValue(CONTENT_TYPE)}"
                },
                "body": ${response.body.toStringLiteral().jsonString()}
              }
            }
            """.trimIndent()
        )
    }

    private fun String.jsonString(): String =
        "\"" + flatMap { char ->
            when (char) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> char.toString()
            }.toList()
        }.joinToString("") + "\""

    private fun petServiceWsdl(): String =
        """
        <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                          xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                          xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                          xmlns:tns="http://example.com/pets"
                          xmlns:animals="http://example.com/animals"
                          targetNamespace="http://example.com/pets">
            <wsdl:types>
                <xsd:schema targetNamespace="http://example.com/pets" elementFormDefault="qualified">
                    <xsd:import namespace="http://example.com/animals" schemaLocation="animals.xsd"/>
                    <xsd:element name="RegisterPet" type="tns:RegisterPetRequest"/>
                    <xsd:element name="RegisterPetResponse" type="xsd:string"/>
                    <xsd:complexType name="RegisterPetRequest">
                        <xsd:sequence>
                            <xsd:element ref="animals:Pet"/>
                        </xsd:sequence>
                    </xsd:complexType>
                </xsd:schema>
            </wsdl:types>
            <wsdl:message name="registerPetInputMessage">
                <wsdl:part name="parameters" element="tns:RegisterPet"/>
            </wsdl:message>
            <wsdl:message name="registerPetOutputMessage">
                <wsdl:part name="parameters" element="tns:RegisterPetResponse"/>
            </wsdl:message>
            <wsdl:portType name="petPortType">
                <wsdl:operation name="registerPet">
                    <wsdl:input message="tns:registerPetInputMessage"/>
                    <wsdl:output message="tns:registerPetOutputMessage"/>
                </wsdl:operation>
            </wsdl:portType>
            <wsdl:binding name="petBinding" type="tns:petPortType">
                <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="registerPet">
                    <soap:operation soapAction="http://example.com/pets/registerPet"/>
                    <wsdl:input><soap:body use="literal"/></wsdl:input>
                    <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
            </wsdl:binding>
            <wsdl:service name="petService">
                <wsdl:port name="petPort" binding="tns:petBinding">
                    <soap:address location="http://localhost:9000/pets"/>
                </wsdl:port>
            </wsdl:service>
        </wsdl:definitions>
        """.trimIndent()

    private fun writeInlinePetService(
        tempDir: File,
        abstractHead: Boolean,
        elementAndTypeShareNames: Boolean = false,
        lowerCaseChildElementNames: Boolean = false,
    ): File {
        return tempDir.resolve("pet-service.wsdl").apply {
            writeText(inlinePetServiceWsdl(abstractHead, elementAndTypeShareNames, lowerCaseChildElementNames))
        }
    }

    private fun inlinePetServiceWsdl(
        abstractHead: Boolean,
        elementAndTypeShareNames: Boolean,
        lowerCaseChildElementNames: Boolean
    ): String {
        val abstractAttribute = if (abstractHead) """ abstract="true"""" else ""
        val petTypeName = if (elementAndTypeShareNames) "Pet" else "PetType"
        val dogTypeName = if (elementAndTypeShareNames) "Dog" else "DogType"
        val catTypeName = if (elementAndTypeShareNames) "Cat" else "CatType"
        val nameElement = if (lowerCaseChildElementNames) "name" else "Name"
        val breedElement = if (lowerCaseChildElementNames) "breed" else "Breed"
        val colorElement = if (lowerCaseChildElementNames) "color" else "Color"

        return """
        <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                          xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                          xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                          xmlns:tns="http://example.com/pets"
                          targetNamespace="http://example.com/pets">
            <wsdl:types>
                <xsd:schema targetNamespace="http://example.com/pets" elementFormDefault="qualified">
                    <xsd:element name="RegisterPet" type="tns:RegisterPetRequest"/>
                    <xsd:element name="RegisterPetResponse" type="tns:RegisterPetResponse"/>
                    <xsd:element name="Pet" type="tns:$petTypeName"$abstractAttribute/>
                    <xsd:element name="Dog" type="tns:$dogTypeName" substitutionGroup="tns:Pet"/>
                    <xsd:element name="Cat" type="tns:$catTypeName" substitutionGroup="tns:Pet"/>
                    <xsd:complexType name="RegisterPetRequest">
                        <xsd:sequence>
                            <xsd:element ref="tns:Pet"/>
                        </xsd:sequence>
                    </xsd:complexType>
                    <xsd:complexType name="$petTypeName">
                        <xsd:sequence>
                            <xsd:element name="$nameElement" type="xsd:string"/>
                        </xsd:sequence>
                    </xsd:complexType>
                    <xsd:complexType name="$dogTypeName">
                        <xsd:complexContent>
                            <xsd:extension base="tns:$petTypeName">
                                <xsd:sequence>
                                    <xsd:element name="$breedElement" type="xsd:string"/>
                                </xsd:sequence>
                            </xsd:extension>
                        </xsd:complexContent>
                    </xsd:complexType>
                    <xsd:complexType name="$catTypeName">
                        <xsd:complexContent>
                            <xsd:extension base="tns:$petTypeName">
                                <xsd:sequence>
                                    <xsd:element name="$colorElement" type="xsd:string"/>
                                </xsd:sequence>
                            </xsd:extension>
                        </xsd:complexContent>
                    </xsd:complexType>
                    <xsd:complexType name="RegisterPetResponse">
                        <xsd:sequence>
                            <xsd:element name="registrationId" type="xsd:int"/>
                            <xsd:element name="status" type="xsd:string"/>
                        </xsd:sequence>
                    </xsd:complexType>
                </xsd:schema>
            </wsdl:types>
            <wsdl:message name="registerPetInputMessage">
                <wsdl:part name="parameters" element="tns:RegisterPet"/>
            </wsdl:message>
            <wsdl:message name="registerPetOutputMessage">
                <wsdl:part name="parameters" element="tns:RegisterPetResponse"/>
            </wsdl:message>
            <wsdl:portType name="petPortType">
                <wsdl:operation name="registerPet">
                    <wsdl:input message="tns:registerPetInputMessage"/>
                    <wsdl:output message="tns:registerPetOutputMessage"/>
                </wsdl:operation>
            </wsdl:portType>
            <wsdl:binding name="petBinding" type="tns:petPortType">
                <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="registerPet">
                    <soap:operation soapAction="/pets/registerPet"/>
                    <wsdl:input><soap:body use="literal"/></wsdl:input>
                    <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
            </wsdl:binding>
            <wsdl:service name="petService">
                <wsdl:port name="petPort" binding="tns:petBinding">
                    <soap:address location="http://localhost:9000/pets"/>
                </wsdl:port>
            </wsdl:service>
        </wsdl:definitions>
        """.trimIndent()
    }

    private fun writeDemoStylePetService(tempDir: File): File {
        return tempDir.resolve("pet.wsdl").apply {
            writeText(
                """
                <definitions name="PetService"
                             targetNamespace="http://example.com/pets"
                             xmlns="http://schemas.xmlsoap.org/wsdl/"
                             xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                             xmlns:tns="http://example.com/pets"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                    <types>
                        <xsd:schema targetNamespace="http://example.com/pets" elementFormDefault="qualified">
                            <xsd:complexType name="Pet">
                                <xsd:sequence>
                                    <xsd:element name="name" type="xsd:string"/>
                                </xsd:sequence>
                            </xsd:complexType>
                            <xsd:complexType name="Dog">
                                <xsd:complexContent>
                                    <xsd:extension base="tns:Pet">
                                        <xsd:sequence>
                                            <xsd:element name="breed" type="xsd:string"/>
                                        </xsd:sequence>
                                    </xsd:extension>
                                </xsd:complexContent>
                            </xsd:complexType>
                            <xsd:complexType name="Cat">
                                <xsd:complexContent>
                                    <xsd:extension base="tns:Pet">
                                        <xsd:sequence>
                                            <xsd:element name="color" type="xsd:string"/>
                                        </xsd:sequence>
                                    </xsd:extension>
                                </xsd:complexContent>
                            </xsd:complexType>
                            <xsd:element name="Pet" type="tns:Pet" abstract="true"/>
                            <xsd:element name="Dog" type="tns:Dog" substitutionGroup="tns:Pet"/>
                            <xsd:element name="Cat" type="tns:Cat" substitutionGroup="tns:Pet"/>
                            <xsd:complexType name="RegisterPet">
                                <xsd:sequence>
                                    <xsd:element ref="tns:Pet"/>
                                </xsd:sequence>
                            </xsd:complexType>
                            <xsd:element name="RegisterPet" type="tns:RegisterPet"/>
                            <xsd:complexType name="RegisterPetResponse">
                                <xsd:sequence>
                                    <xsd:element name="registrationId" type="xsd:int"/>
                                    <xsd:element name="status" type="xsd:string"/>
                                </xsd:sequence>
                            </xsd:complexType>
                            <xsd:element name="RegisterPetResponse" type="tns:RegisterPetResponse"/>
                        </xsd:schema>
                    </types>
                    <message name="RegisterPetInput">
                        <part name="parameters" element="tns:RegisterPet"/>
                    </message>
                    <message name="RegisterPetOutput">
                        <part name="parameters" element="tns:RegisterPetResponse"/>
                    </message>
                    <portType name="PetPortType">
                        <operation name="registerPet">
                            <input message="tns:RegisterPetInput"/>
                            <output message="tns:RegisterPetOutput"/>
                        </operation>
                    </portType>
                    <binding name="PetBinding" type="tns:PetPortType">
                        <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                        <operation name="registerPet">
                            <soap:operation soapAction="/pets/registerPet"/>
                            <input><soap:body use="literal"/></input>
                            <output><soap:body use="literal"/></output>
                        </operation>
                    </binding>
                    <service name="PetService">
                        <port name="PetPort" binding="tns:PetBinding">
                            <soap:address location="http://localhost:9000/pets"/>
                        </port>
                    </service>
                </definitions>
                """.trimIndent()
            )
        }
    }

    private fun animalsSchema(abstractHead: Boolean, includeSubstitutes: Boolean): String {
        val abstractAttribute = if (abstractHead) """ abstract="true"""" else ""
        val substitutes = if (includeSubstitutes) {
            """
            <xsd:element name="Dog" type="animals:DogType" substitutionGroup="animals:Pet"/>
            <xsd:element name="Cat" type="animals:CatType" substitutionGroup="animals:Pet"/>
            """.trimIndent()
        } else {
            ""
        }

        return """
        <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                    xmlns:animals="http://example.com/animals"
                    targetNamespace="http://example.com/animals"
                    elementFormDefault="qualified">
            <xsd:element name="Pet" type="animals:PetType"$abstractAttribute/>
            $substitutes
            <xsd:complexType name="PetType">
                <xsd:sequence>
                    <xsd:element name="Name" type="xsd:string"/>
                </xsd:sequence>
            </xsd:complexType>
            <xsd:complexType name="DogType">
                <xsd:complexContent>
                    <xsd:extension base="animals:PetType">
                        <xsd:sequence>
                            <xsd:element name="Breed" type="xsd:string"/>
                        </xsd:sequence>
                    </xsd:extension>
                </xsd:complexContent>
            </xsd:complexType>
            <xsd:complexType name="CatType">
                <xsd:complexContent>
                    <xsd:extension base="animals:PetType">
                        <xsd:sequence>
                            <xsd:element name="Color" type="xsd:string"/>
                        </xsd:sequence>
                    </xsd:extension>
                </xsd:complexContent>
            </xsd:complexType>
        </xsd:schema>
        """.trimIndent()
    }
}
