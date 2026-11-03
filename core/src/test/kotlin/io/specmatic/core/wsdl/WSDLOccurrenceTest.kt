package io.specmatic.core.wsdl

import io.specmatic.core.*
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WSDLOccurrenceTest {
    @Test
    fun `bounded elements enforce both limits in requests and responses`() {
        val declaration = """<xsd:element name="Request"><xsd:complexType><xsd:sequence>
            <xsd:element name="Item" type="xsd:string" minOccurs="2" maxOccurs="3"/>
        </xsd:sequence></xsd:complexType></xsd:element>"""
        val feature = wsdlFeature(declaration, declaration.replace("Request", "Response"))
        val scenario = feature.scenarios.single()
        for (count in 0..4) {
            val children = "<Item>value</Item>".repeat(count)
            val request = HttpRequest(method = "POST", path = PATH,
                headers = mapOf("SOAPAction" to "\"$SOAP_ACTION\"", "Content-Type" to "text/xml"),
                body = StringValue(envelope("<Request>$children</Request>")))
            val requestResult = scenario.matches(request, scenario.resolver)
            assertThat(requestResult.isSuccess()).describedAs("request count %s: %s", count, requestResult.reportString()).isEqualTo(count in 2..3)
            val responseResult = scenario.httpResponsePattern.matchesResponse(
                HttpResponse(status = 200, headers = mapOf("Content-Type" to "text/xml"), body = StringValue(envelope("<Response>$children</Response>"))),
                scenario.resolver)
            assertThat(responseResult.isSuccess()).describedAs("response count %s: %s", count, responseResult.reportString()).isEqualTo(count in 2..3)
        }
    }

    @Test
    fun `zero occurrences remove typed untyped and referenced declarations only`() {
        for (attributes in listOf("name=\"Gone\" type=\"xsd:string\"", "name=\"Gone\"", "ref=\"tns:Gone\"")) {
            val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType><xsd:sequence>
                <xsd:element $attributes minOccurs="0" maxOccurs="0"/>
                <xsd:element name="Keep" type="xsd:string"/>
            </xsd:sequence></xsd:complexType></xsd:element>""",
                additionalSchema = """<xsd:element name="Gone" type="xsd:string"/>""")
            val scenario = feature.scenarios.single()
            val generated = scenario.generateHttpRequest(feature.flagsBased)
            assertThat(generated.body.toStringLiteral()).contains("Keep").doesNotContain("Gone")
            assertThat(scenario.matches(request("<Keep>ok</Keep>"), scenario.resolver).isSuccess()).isTrue()
            assertThat(scenario.matches(request("<Gone>no</Gone><Keep>ok</Keep>"), scenario.resolver).isSuccess()).isFalse()
        }
    }

    @Test
    fun `a repeated sequence repeats the whole ordered group`() {
        val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType>
            <xsd:sequence minOccurs="2" maxOccurs="3"><xsd:element name="A" type="xsd:string"/><xsd:element name="B" type="xsd:string"/></xsd:sequence>
        </xsd:complexType></xsd:element>""")
        val scenario = feature.scenarios.single()
        for ((children, valid) in listOf(
            "<A>a</A><B>b</B>" to false,
            "<A>a</A><B>b</B>".repeat(2) to true,
            "<A>a</A><B>b</B>".repeat(3) to true,
            "<A>a</A><B>b</B>".repeat(4) to false,
            "<A>a</A><A>a</A><B>b</B><B>b</B>" to false)) {
            val result = scenario.matches(request(children), scenario.resolver)
            assertThat(result.isSuccess()).describedAs("%s: %s", children, result.reportString()).isEqualTo(valid)
        }
    }

    @Test
    fun `a required repeated choice can choose an empty alternative`() {
        val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType>
            <xsd:choice minOccurs="2" maxOccurs="unbounded">
                <xsd:element name="Gone" minOccurs="0" maxOccurs="0"/>
                <xsd:element name="B" type="xsd:string"/>
            </xsd:choice>
        </xsd:complexType></xsd:element>""")
        val scenario = feature.scenarios.single()
        for (children in listOf("", "<B>b</B>", "<B>b</B>".repeat(3))) {
            val result = scenario.matches(request(children), scenario.resolver)
            assertThat(result.isSuccess()).describedAs(result.reportString()).isTrue()
        }
        assertThat(scenario.matches(request("<Gone>no</Gone>"), scenario.resolver).isSuccess()).isFalse()
    }

    @Test
    fun `all allows either order while enforcing required and optional members`() {
        val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType><xsd:all>
            <xsd:element name="A" type="xsd:string"/>
            <xsd:element name="B" type="xsd:string" minOccurs="0"/>
            <xsd:element name="Gone" minOccurs="0" maxOccurs="0"/>
        </xsd:all></xsd:complexType></xsd:element>""")
        val scenario = feature.scenarios.single()
        for ((children, valid) in listOf("<A>a</A>" to true, "<A>a</A><B>b</B>" to true,
            "<B>b</B><A>a</A>" to true, "<B>b</B>" to false, "<A>a</A><A>a</A>" to false)) {
            val result = scenario.matches(request(children), scenario.resolver)
            assertThat(result.isSuccess()).describedAs("%s: %s", children, result.reportString()).isEqualTo(valid)
        }
    }

    @Test
    fun `named group references retain their occurrence range and sequence`() {
        val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType>
            <xsd:group ref="tns:Pair" minOccurs="2" maxOccurs="3"/>
        </xsd:complexType></xsd:element>""", additionalSchema = """
            <xsd:group name="Pair"><xsd:sequence><xsd:element name="A" type="xsd:string"/><xsd:element name="B" type="xsd:string"/></xsd:sequence></xsd:group>
        """)
        val scenario = feature.scenarios.single()
        for (count in 1..4) {
            val result = scenario.matches(request("<A>a</A><B>b</B>".repeat(count)), scenario.resolver)
            assertThat(result.isSuccess()).describedAs(result.reportString()).isEqualTo(count in 2..3)
        }
    }

    @Test
    fun `invalid bounds and all restrictions report the source during loading`() {
        val invalidParticles = listOf(
            """<xsd:sequence><xsd:element name="Item" type="xsd:string" minOccurs="1" maxOccurs="0"/></xsd:sequence>""",
            """<xsd:sequence><xsd:element name="Item" type="xsd:string" maxOccurs="0"/></xsd:sequence>""",
            """<xsd:choice minOccurs="oops"><xsd:element name="Item" type="xsd:string"/></xsd:choice>""",
            """<xsd:sequence minOccurs="-1"/>""",
            """<xsd:sequence><xsd:any minOccurs="oops"/></xsd:sequence>""",
            """<xsd:all maxOccurs="2"><xsd:element name="Item" type="xsd:string"/></xsd:all>""",
            """<xsd:all><xsd:element name="Item" type="xsd:string" maxOccurs="2"/></xsd:all>"""
        )
        for (particle in invalidParticles) {
            assertThatThrownBy {
                wsdlFeature("""<xsd:element name="Request"><xsd:complexType>$particle</xsd:complexType></xsd:element>""", wsdlPath = "invalid-occurrences.wsdl")
            }.isInstanceOf(io.specmatic.core.pattern.ContractException::class.java)
                .hasMessageContaining("invalid-occurrences.wsdl")
        }
    }

    @Test
    fun `generated request and response counts follow StringPattern policy and satisfy JDK schemas`() {
        for ((bounds, count) in listOf("" to 1, "minOccurs=\"0\" maxOccurs=\"0\"" to 0,
            "minOccurs=\"0\" maxOccurs=\"3\"" to 3, "minOccurs=\"2\" maxOccurs=\"8\"" to 5,
            "minOccurs=\"7\" maxOccurs=\"unbounded\"" to 7)) {
            fun declaration(name: String) = """<xsd:element name="$name"><xsd:complexType><xsd:sequence>
                <xsd:element name="Item" type="xsd:string" $bounds/>
            </xsd:sequence></xsd:complexType></xsd:element>"""
            val feature = wsdlFeature(declaration("Request"), declaration("Response"), schemaAttributes = "elementFormDefault=\"qualified\"")
            val scenario = feature.scenarios.single()
            val schema = """<xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="$NAMESPACE" elementFormDefault="qualified">
                ${declaration("Request")}${declaration("Response")}
            </xsd:schema>"""
            for (body in listOf(scenario.generateHttpRequest(feature.flagsBased).body, scenario.generateHttpResponse().body)) {
                val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                val document = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(body.toStringLiteral())))
                val soapBody = document.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body").item(0)
                val payload = (0 until soapBody.childNodes.length).map { soapBody.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>().single()
                assertThat(payload.getElementsByTagNameNS(NAMESPACE, "Item").length).describedAs(bounds).isEqualTo(count)
                javax.xml.validation.SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI)
                    .newSchema(javax.xml.transform.stream.StreamSource(java.io.StringReader(schema)))
                    .newValidator().validate(javax.xml.transform.dom.DOMSource(payload))
            }
        }
    }

    @Test
    fun `positive and negative generated cases exercise element group choice and wildcard boundaries`() {
        val particles = listOf(
            """<xsd:sequence><xsd:element name="Item" type="xsd:string" minOccurs="2" maxOccurs="8"/></xsd:sequence>""",
            """<xsd:sequence minOccurs="2" maxOccurs="8"><xsd:element name="Item" type="xsd:string"/></xsd:sequence>""",
            """<xsd:choice minOccurs="2" maxOccurs="8"><xsd:element name="Item" type="xsd:string"/></xsd:choice>""",
            """<xsd:sequence><xsd:any minOccurs="2" maxOccurs="8" processContents="skip"/></xsd:sequence>"""
        )
        for (particle in particles) {
            val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType>$particle</xsd:complexType></xsd:element>""")
            val scenario = feature.scenarios.single()
            val body = scenario.httpRequestPattern.body
            fun count(pattern: io.specmatic.core.pattern.Pattern): Int {
                val xml = pattern.generate(scenario.resolver).toStringLiteral()
                val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                val payload = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml))).getElementsByTagNameNS("*", "Request").item(0)
                return (0 until payload.childNodes.length).count { payload.childNodes.item(it) is org.w3c.dom.Element }
            }
            val positives = body.newBasedOn(io.specmatic.core.pattern.Row(), scenario.resolver).map { count(it.value) }.toList()
            assertThat(positives).describedAs(particle).contains(2, 5, 8).allMatch { it in 2..8 }
            val negatives = body.negativeBasedOn(io.specmatic.core.pattern.Row(), scenario.resolver).map { count(it.value) }.toList()
            assertThat(negatives).describedAs(particle).contains(1, 9)
        }
    }

    @Test
    fun `response compatibility compares ranges and permits additional optional members`() {
        fun response(children: String) = wsdlFeature("""<xsd:element name="Request" type="xsd:string"/>""",
            """<xsd:element name="Response"><xsd:complexType><xsd:sequence>$children</xsd:sequence></xsd:complexType></xsd:element>""").scenarios.single()
        val wider = response("""<xsd:element name="Item" type="xsd:string" minOccurs="0" maxOccurs="5"/>""")
        val narrower = response("""<xsd:element name="Item" type="xsd:string" minOccurs="2" maxOccurs="3"/>""")
        assertThat(wider.httpResponsePattern.encompasses(narrower.httpResponsePattern, wider.resolver, narrower.resolver).isSuccess()).isTrue()
        assertThat(narrower.httpResponsePattern.encompasses(wider.httpResponsePattern, narrower.resolver, wider.resolver).isSuccess()).isFalse()
        val extraOptional = response("""<xsd:element name="Other" type="xsd:string" minOccurs="0"/><xsd:element name="Item" type="xsd:string" minOccurs="0" maxOccurs="5"/>""")
        val result = extraOptional.httpResponsePattern.encompasses(narrower.httpResponsePattern, extraOptional.resolver, narrower.resolver)
        assertThat(result.isSuccess()).describedAs(result.reportString()).isTrue()
    }

    @Test
    fun `complex content extension and restriction preserve group bounds`() {
        for (derivation in listOf("extension", "restriction")) {
            val base = if (derivation == "restriction") """<xsd:sequence minOccurs="0" maxOccurs="unbounded"><xsd:element name="Item" type="xsd:string"/></xsd:sequence>""" else "<xsd:sequence/>"
            val feature = wsdlFeature("""<xsd:element name="Request" type="tns:Derived"/>""", additionalSchema = """
                <xsd:complexType name="Base">$base</xsd:complexType>
                <xsd:complexType name="Derived"><xsd:complexContent><xsd:$derivation base="tns:Base">
                    <xsd:sequence minOccurs="2" maxOccurs="3"><xsd:element name="Item" type="xsd:string"/></xsd:sequence>
                </xsd:$derivation></xsd:complexContent></xsd:complexType>
            """)
            val scenario = feature.scenarios.single()
            for (count in 1..4) {
                val result = scenario.matches(request("<Item>x</Item>".repeat(count)), scenario.resolver)
                assertThat(result.isSuccess()).describedAs("%s count %s: %s", derivation, count, result.reportString()).isEqualTo(count in 2..3)
            }
        }
    }

    @Test
    fun `zero groups remove only their contribution and leave same named declarations usable`() {
        for (group in listOf(
            """<xsd:sequence minOccurs="0" maxOccurs="0"><xsd:element name="Gone"/></xsd:sequence>""",
            """<xsd:choice minOccurs="0" maxOccurs="0"><xsd:element name="Gone"/></xsd:choice>""",
            """<xsd:group ref="tns:Absent" minOccurs="0" maxOccurs="0"/>""")) {
            val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType><xsd:sequence>
                $group<xsd:element name="Gone" type="xsd:string"/>
            </xsd:sequence></xsd:complexType></xsd:element>""", additionalSchema = """
                <xsd:group name="Absent"><xsd:sequence><xsd:element name="Gone"/></xsd:sequence></xsd:group>
            """)
            val scenario = feature.scenarios.single()
            assertThat(scenario.matches(request("<Gone>ok</Gone>"), scenario.resolver).isSuccess()).isTrue()
            assertThat(scenario.matches(request(""), scenario.resolver).isSuccess()).isFalse()
            assertThat(scenario.matches(request("<Gone>ok</Gone>".repeat(2)), scenario.resolver).isSuccess()).isFalse()
        }
    }

    @Test
    fun `required recursive elements do not become optional during matching`() {
        val feature = wsdlFeature("""<xsd:element name="Request" type="tns:Recursive"/>""", additionalSchema = """
            <xsd:complexType name="Recursive"><xsd:sequence><xsd:element name="Child" type="tns:Recursive"/></xsd:sequence></xsd:complexType>
        """)
        val scenario = feature.scenarios.single()
        for (children in listOf("", "<Child/>", "<Child><Child/></Child>")) {
            val result = scenario.matches(request(children), scenario.resolver)
            assertThat(result.isSuccess()).describedAs(children).isFalse()
        }
    }

    @Test
    fun `references to all groups obey XSD 1_0 occurrence and placement restrictions`() {
        val definition = """<xsd:group name="Fields"><xsd:all><xsd:element name="A" type="xsd:string"/></xsd:all></xsd:group>"""
        for (content in listOf(
            """<xsd:group ref="tns:Fields" maxOccurs="2"/>""",
            """<xsd:sequence><xsd:group ref="tns:Fields"/></xsd:sequence>""")) {
            assertThatThrownBy { wsdlFeature("""<xsd:element name="Request"><xsd:complexType>$content</xsd:complexType></xsd:element>""", additionalSchema = definition) }
                .isInstanceOf(io.specmatic.core.pattern.ContractException::class.java).hasMessageContaining("all")
        }
    }

    @Test
    fun `compatibility recognizes equivalent group references and ordered subsets of all`() {
        val pair = """<xsd:element name="A" type="xsd:string"/><xsd:element name="B" type="xsd:string"/>"""
        fun response(content: String) = wsdlFeature("""<xsd:element name="Request" type="xsd:string"/>""",
            """<xsd:element name="Response"><xsd:complexType>$content</xsd:complexType></xsd:element>""",
            additionalSchema = """<xsd:group name="Pair"><xsd:sequence>$pair</xsd:sequence></xsd:group>""").scenarios.single()
        fun encompasses(bigger: Scenario, smaller: Scenario): Boolean =
            bigger.httpResponsePattern.encompasses(smaller.httpResponsePattern, bigger.resolver, smaller.resolver).isSuccess()
        val reference = response("""<xsd:group ref="tns:Pair" minOccurs="2" maxOccurs="3"/>""")
        val inline = response("""<xsd:sequence minOccurs="2" maxOccurs="3">$pair</xsd:sequence>""")
        assertThat(encompasses(reference, inline)).isTrue()
        assertThat(encompasses(inline, reference)).isTrue()
        val unordered = response("<xsd:all>$pair</xsd:all>")
        val ordered = response("<xsd:sequence>$pair</xsd:sequence>")
        assertThat(encompasses(unordered, ordered)).isTrue()
        assertThat(encompasses(ordered, unordered)).isFalse()
    }

    @Test
    fun `generated all variants retain unordered matching`() {
        val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType><xsd:all>
            <xsd:element name="A" type="xsd:string"/><xsd:element name="B" type="xsd:string"/>
        </xsd:all></xsd:complexType></xsd:element>""")
        val scenario = feature.scenarios.single()
        val body = scenario.httpRequestPattern.body
        val variants = body.newBasedOn(io.specmatic.core.pattern.Row(), scenario.resolver).map { it.value }.toList() + body.newBasedOn(scenario.resolver).toList()
        assertThat(variants).isNotEmpty()
        for (variant in variants) {
            val result = variant.matches(toXMLNode(envelope("<Request><B>b</B><A>a</A></Request>")), scenario.resolver)
            assertThat(result.isSuccess()).describedAs(result.reportString()).isTrue()
        }
    }

    @Test
    fun `compatibility compares repeated expanded and nullable group languages`() {
        val pair = """<xsd:element name="A" type="xsd:string"/><xsd:element name="B" type="xsd:string"/>"""
        fun response(content: String) = wsdlFeature("""<xsd:element name="Request" type="xsd:string"/>""",
            """<xsd:element name="Response"><xsd:complexType>$content</xsd:complexType></xsd:element>""").scenarios.single()
        fun encompasses(bigger: Scenario, smaller: Scenario): Boolean =
            bigger.httpResponsePattern.encompasses(smaller.httpResponsePattern, bigger.resolver, smaller.resolver).isSuccess()
        val repeated = response("""<xsd:sequence minOccurs="2" maxOccurs="2">$pair</xsd:sequence>""")
        val expanded = response("<xsd:sequence>${pair.repeat(2)}</xsd:sequence>")
        assertThat(encompasses(repeated, expanded)).isTrue()
        assertThat(encompasses(expanded, repeated)).isTrue()
        val optional = """<xsd:element name="A" type="xsd:string" minOccurs="0"/>"""
        val twice = response("""<xsd:sequence minOccurs="2" maxOccurs="2">$optional</xsd:sequence>""")
        val once = response("<xsd:sequence>$optional</xsd:sequence>")
        assertThat(encompasses(twice, once)).isTrue()
        assertThat(encompasses(once, twice)).isFalse()
    }

    @Test
    fun `named group compositor bounds and extending nonempty all content fail with source context`() {
        val named = """<xsd:element name="Request"><xsd:complexType><xsd:group ref="tns:G"/></xsd:complexType></xsd:element>"""
        assertThatThrownBy { wsdlFeature(named, additionalSchema = """
            <xsd:group name="G"><xsd:sequence minOccurs="0"><xsd:element name="A" type="xsd:string"/></xsd:sequence></xsd:group>
        """, wsdlPath = "named-bounds.wsdl") }.isInstanceOf(io.specmatic.core.pattern.ContractException::class.java).hasMessageContaining("named-bounds.wsdl")
        val allA = """<xsd:all><xsd:element name="A" type="xsd:string"/></xsd:all>"""
        val sequenceB = """<xsd:sequence><xsd:element name="B" type="xsd:string"/></xsd:sequence>"""
        for ((baseContent, extensionContent) in listOf(allA to sequenceB, sequenceB to allA)) {
            assertThatThrownBy { wsdlFeature("""<xsd:element name="Request" type="tns:Derived"/>""", additionalSchema = """
                <xsd:complexType name="Base">$baseContent</xsd:complexType>
                <xsd:complexType name="Derived"><xsd:complexContent><xsd:extension base="tns:Base">$extensionContent</xsd:extension></xsd:complexContent></xsd:complexType>
            """, wsdlPath = "all-extension.wsdl") }.isInstanceOf(io.specmatic.core.pattern.ContractException::class.java).hasMessageContaining("all-extension.wsdl").hasMessageContaining("all")
        }
    }

    @Test
    fun `finite bounds beyond machine integer range retain their meaning and ordinary generation remains small`() {
        val large = "minOccurs=\"0\" maxOccurs=\"2147483648\""
        for (particle in listOf(
            """<xsd:sequence><xsd:element name="Item" type="xsd:string" $large/></xsd:sequence>""",
            """<xsd:sequence $large><xsd:element name="Item" type="xsd:string"/></xsd:sequence>""",
            """<xsd:choice $large><xsd:element name="Item" type="xsd:string"/></xsd:choice>""",
            """<xsd:sequence><xsd:any $large processContents="skip"/></xsd:sequence>""")) {
            val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType>$particle</xsd:complexType></xsd:element>""")
            val scenario = feature.scenarios.single()
            assertThat(scenario.matches(request("<Item>x</Item>".repeat(6)), scenario.resolver).isSuccess()).describedAs(particle).isTrue()
            val generated = scenario.generateHttpRequest(feature.flagsBased)
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(generated.body.toStringLiteral())))
            val payload = document.getElementsByTagNameNS("*", "Request").item(0)
            assertThat((0 until payload.childNodes.length).count { payload.childNodes.item(it) is org.w3c.dom.Element }).isEqualTo(5)
        }
    }

    @Test
    fun `compatibility recognizes a complete union of disjoint wildcard namespaces`() {
        fun response(content: String) = wsdlFeature("""<xsd:element name="Request" type="xsd:string"/>""",
            """<xsd:element name="Response"><xsd:complexType>$content</xsd:complexType></xsd:element>""").scenarios.single()
        fun wildcard(namespace: String) = """<xsd:any namespace="$namespace" processContents="skip"/>"""
        val any = response("<xsd:sequence>${wildcard("##any")}</xsd:sequence>")
        val complete = response("<xsd:choice>${wildcard("##targetNamespace")}${wildcard("##other")}${wildcard("##local")}</xsd:choice>")
        val incomplete = response("<xsd:choice>${wildcard("##targetNamespace")}${wildcard("##other")}</xsd:choice>")
        assertThat(complete.httpResponsePattern.encompasses(any.httpResponsePattern, complete.resolver, any.resolver).isSuccess()).isTrue()
        assertThat(any.httpResponsePattern.encompasses(complete.httpResponsePattern, any.resolver, complete.resolver).isSuccess()).isTrue()
        assertThat(incomplete.httpResponsePattern.encompasses(any.httpResponsePattern, incomplete.resolver, any.resolver).isSuccess()).isFalse()
    }

    @Test
    fun `compatibility does not confuse an empty group with an empty named element`() {
        fun response(content: String) = wsdlFeature("""<xsd:element name="Request" type="xsd:string"/>""",
            """<xsd:element name="Response"><xsd:complexType>$content</xsd:complexType></xsd:element>""").scenarios.single()
        val empty = response("""<xsd:sequence minOccurs="0"/>""")
        val named = response("""<xsd:sequence><xsd:element name="A"><xsd:complexType/></xsd:element></xsd:sequence>""")
        assertThat(empty.httpResponsePattern.encompasses(named.httpResponsePattern, empty.resolver, named.resolver).isSuccess()).isFalse()
    }

    @Test
    fun `empty choices and repeated empty groups generate and match empty content`() {
        for (particle in listOf("<xsd:choice/>", """<xsd:choice minOccurs="2" maxOccurs="unbounded"/>""",
            """<xsd:choice minOccurs="2147483648" maxOccurs="unbounded"/>""",
            """<xsd:sequence minOccurs="2147483648" maxOccurs="unbounded"/>""")) {
            val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType>$particle</xsd:complexType></xsd:element>""")
            val scenario = feature.scenarios.single()
            val generated = scenario.generateHttpRequest(feature.flagsBased)
            val result = scenario.matches(generated, scenario.resolver)
            assertThat(result.isSuccess()).describedAs("%s: %s", particle, result.reportString()).isTrue()
            assertThat(scenario.httpRequestPattern.body.newBasedOn(io.specmatic.core.pattern.Row(), scenario.resolver).toList()).isNotEmpty()
        }
        val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType><xsd:sequence>
            <xsd:element name="Before" type="xsd:string"/><xsd:choice/><xsd:element name="After" type="xsd:string"/>
        </xsd:sequence></xsd:complexType></xsd:element>""")
        val scenario = feature.scenarios.single()
        assertThat(scenario.matches(request("<Before>b</Before><After>a</After>"), scenario.resolver).isSuccess()).isTrue()
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 10, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    fun `huge equivalent groups compare symbolically while referenced type differences remain visible`() {
        fun response(content: String, data: String = "") = wsdlFeature("""<xsd:element name="Request" type="xsd:string"/>""",
            """<xsd:element name="Response"><xsd:complexType>$content</xsd:complexType></xsd:element>""", additionalSchema = data).scenarios.single()
        fun encompasses(bigger: Scenario, smaller: Scenario): Boolean =
            bigger.httpResponsePattern.encompasses(smaller.httpResponsePattern, bigger.resolver, smaller.resolver).isSuccess()
        val a = """<xsd:element name="A" type="xsd:string"/>"""
        val b = """<xsd:element name="B" type="xsd:string"/>"""
        val repeated = response("""<xsd:sequence minOccurs="2147483648" maxOccurs="2147483648">$a</xsd:sequence>""")
        val summed = response("""<xsd:sequence><xsd:element name="A" type="xsd:string" minOccurs="2147483647" maxOccurs="2147483647"/>$a</xsd:sequence>""")
        assertThat(encompasses(repeated, summed)).isTrue()
        assertThat(encompasses(summed, repeated)).isTrue()
        val pairs = response("""<xsd:sequence minOccurs="2147483648" maxOccurs="2147483648">$a$b</xsd:sequence>""")
        val quadruples = response("""<xsd:sequence minOccurs="1073741824" maxOccurs="1073741824">$a$b$a$b</xsd:sequence>""")
        assertThat(encompasses(pairs, quadruples)).isTrue()
        assertThat(encompasses(quadruples, pairs)).isTrue()
        val references = """<xsd:sequence minOccurs="0" maxOccurs="2147483648"><xsd:element name="A" type="tns:Data"/></xsd:sequence>"""
        fun data(extra: String) = """<xsd:complexType name="Data"><xsd:sequence><xsd:element name="Value" type="xsd:string"/>$extra</xsd:sequence></xsd:complexType>"""
        val old = response(references, data(""))
        val changed = response(references, data("""<xsd:element name="Extra" type="xsd:string"/>"""))
        assertThat(encompasses(changed, old)).isFalse()
    }

    @Test
    fun `optional element value errors remain visible before a required sibling`() {
        val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType><xsd:sequence>
            <xsd:element name="Pet" type="xsd:integer" minOccurs="0"/>
            <xsd:element name="Comment" type="xsd:string"/>
        </xsd:sequence></xsd:complexType></xsd:element>""")
        val scenario = feature.scenarios.single()
        val result = scenario.matches(request("<Pet>invalid-number</Pet><Comment>ok</Comment>"), scenario.resolver)
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).contains("Pet", "invalid-number").doesNotContain("Expected Comment")
    }

    @Test
    fun `local element patterns preserve XMLPattern identity and occurrence ranges`() {
        val feature = wsdlFeature("""<xsd:element name="Request"><xsd:complexType><xsd:sequence>
            <xsd:element name="Item" type="xsd:string" minOccurs="2" maxOccurs="3"/>
            <xsd:element ref="tns:Named" minOccurs="0" maxOccurs="2"/>
            <xsd:element name="Code"><xsd:simpleType><xsd:restriction base="xsd:string"/></xsd:simpleType></xsd:element>
        </xsd:sequence></xsd:complexType></xsd:element>""", additionalSchema = """<xsd:element name="Named" type="xsd:string"/>""")
        val types = feature.scenarios.single().resolver.newPatterns.values.filterIsInstance<io.specmatic.core.pattern.XMLPattern>()
        val payload = types.single { it.pattern.wsdlTypeName == "Request" }
        val group = payload.pattern.nodes.single() as io.specmatic.core.pattern.XMLParticlePattern
        val sequence = group.member as io.specmatic.core.pattern.XMLSequencePattern
        assertThat(sequence.members).allSatisfy { assertThat(it).isInstanceOf(io.specmatic.core.pattern.XMLPattern::class.java) }
        val elements = sequence.members.map { it as io.specmatic.core.pattern.XMLPattern }
        assertThat(elements.map { it.pattern.occurrences }).containsExactly(
            io.specmatic.core.pattern.XMLOccurrenceRange(2, 3),
            io.specmatic.core.pattern.XMLOccurrenceRange(0, 2),
            io.specmatic.core.pattern.XMLOccurrenceRange(1, 1)
        )
        val envelope = feature.scenarios.single().httpRequestPattern.body as io.specmatic.core.pattern.XMLPattern
        assertThat(envelope.pattern.name).isEqualTo("Envelope")
        assertThat(envelope.pattern.occurrences).isNull()
    }

    private fun request(children: String) = HttpRequest(method = "POST", path = PATH,
        headers = mapOf("SOAPAction" to "\"$SOAP_ACTION\"", "Content-Type" to "text/xml"),
        body = StringValue(envelope("<Request>$children</Request>")))

    private fun envelope(body: String) = """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:t="$NAMESPACE"><soapenv:Body>$body</soapenv:Body></soapenv:Envelope>"""

    private fun wsdlFeature(
        requestDeclaration: String,
        responseDeclaration: String = """<xsd:element name="Response" type="xsd:string"/>""",
        additionalSchema: String = "",
        schemaAttributes: String = "",
        additionalNamespaces: String = "",
        wsdlPath: String = "comprehension.wsdl",
    ): Feature {
        val wsdl = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                              xmlns:tns="$NAMESPACE"
                              $additionalNamespaces
                              targetNamespace="$NAMESPACE">
                <wsdl:types>
                    <xsd:schema targetNamespace="$NAMESPACE" $schemaAttributes>
                        $requestDeclaration
                        $responseDeclaration
                        $additionalSchema
                    </xsd:schema>
                </wsdl:types>
                <wsdl:message name="RequestMessage">
                    <wsdl:part name="request" element="tns:Request"/>
                </wsdl:message>
                <wsdl:message name="ResponseMessage">
                    <wsdl:part name="response" element="tns:Response"/>
                </wsdl:message>
                <wsdl:portType name="ServicePortType">
                    <wsdl:operation name="$OPERATION">
                        <wsdl:input message="tns:RequestMessage"/>
                        <wsdl:output message="tns:ResponseMessage"/>
                    </wsdl:operation>
                </wsdl:portType>
                <wsdl:binding name="ServiceBinding" type="tns:ServicePortType">
                    <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="$OPERATION">
                        <soap:operation soapAction="$SOAP_ACTION"/>
                        <wsdl:input><soap:body use="literal"/></wsdl:input>
                        <wsdl:output><soap:body use="literal"/></wsdl:output>
                    </wsdl:operation>
                </wsdl:binding>
                <wsdl:service name="Service">
                    <wsdl:port name="ServicePort" binding="tns:ServiceBinding">
                        <soap:address location="http://localhost:9000$PATH"/>
                    </wsdl:port>
                </wsdl:service>
            </wsdl:definitions>
        """.trimIndent()

        return WSDL(toXMLNode(wsdl), wsdlPath).toFeature(wsdlPath)
    }

    companion object {
        private const val NAMESPACE = "http://specmatic.io/occurrences"
        private const val PATH = "/occurrences"
        private const val OPERATION = "Occurrences"
        private const val SOAP_ACTION = "$PATH/$OPERATION"
    }
}
