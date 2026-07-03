package io.specmatic.core.pattern

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.*
import io.specmatic.core.wsdl.parser.message.MULTIPLE_ATTRIBUTE_VALUE
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.parser.message.OPTIONAL_ATTRIBUTE_VALUE
import io.specmatic.shouldMatch
import io.specmatic.shouldNotMatch
import io.specmatic.trimmedLinesString
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.function.Consumer

private const val isOptional: String = "$OCCURS_ATTRIBUTE_NAME=\"$OPTIONAL_ATTRIBUTE_VALUE\""
private const val occursMultipleTimes: String = "$OCCURS_ATTRIBUTE_NAME=\"$MULTIPLE_ATTRIBUTE_VALUE\""
private const val ANIMAL_NAMESPACE: String = "http://example.com/animals"
private const val XML_SCHEMA_NAMESPACE: String = "http://www.w3.org/2001/XMLSchema"

private val ANIMAL_TYPE = WSDLTypeName(ANIMAL_NAMESPACE, "Animal")
private val DOG_TYPE = WSDLTypeName(ANIMAL_NAMESPACE, "Dog")
private val WORKING_DOG_TYPE = WSDLTypeName(ANIMAL_NAMESPACE, "WorkingDog")
private val CAT_TYPE = WSDLTypeName(ANIMAL_NAMESPACE, "Cat")
private val VEHICLE_TYPE = WSDLTypeName(ANIMAL_NAMESPACE, "Vehicle")
private val BASE_CODE_TYPE = WSDLTypeName(ANIMAL_NAMESPACE, "BaseCode")
private val CONSTRAINED_CODE_TYPE = WSDLTypeName(ANIMAL_NAMESPACE, "ConstrainedCode")
private val XML_SCHEMA_STRING_TYPE = WSDLTypeName(XML_SCHEMA_NAMESPACE, "string")

private const val ANIMAL_TYPE_KEY = "(tns_Animal)"
private const val DOG_TYPE_KEY = "(tns_Dog)"
private const val WORKING_DOG_TYPE_KEY = "(tns_WorkingDog)"
private const val CAT_TYPE_KEY = "(tns_Cat)"
private const val VEHICLE_TYPE_KEY = "(tns_Vehicle)"
private const val BASE_CODE_TYPE_KEY = "(tns_BaseCode)"
private const val CONSTRAINED_CODE_TYPE_KEY = "(tns_ConstrainedCode)"
private const val XML_SCHEMA_STRING_TYPE_KEY = "(xs_string)"

private val ANIMAL_TYPE_KEYS = mapOf(
    ANIMAL_TYPE to ANIMAL_TYPE_KEY,
    DOG_TYPE to DOG_TYPE_KEY,
    WORKING_DOG_TYPE to WORKING_DOG_TYPE_KEY,
    CAT_TYPE to CAT_TYPE_KEY,
    VEHICLE_TYPE to VEHICLE_TYPE_KEY,
)

private val CODE_TYPE_KEYS = mapOf(
    BASE_CODE_TYPE to BASE_CODE_TYPE_KEY,
    CONSTRAINED_CODE_TYPE to CONSTRAINED_CODE_TYPE_KEY,
)

private val CODE_TYPE_KEYS_WITH_XML_SCHEMA_TYPE = CODE_TYPE_KEYS + mapOf(
    XML_SCHEMA_STRING_TYPE to XML_SCHEMA_STRING_TYPE_KEY
)

internal class XMLPatternTest {
    @Nested
    inner class GenerateValues {
        @Test
        fun `generate a number`() {
            val type = parsedPattern("<data>(number)</data>")
            val node = type.generate(Resolver()) as XMLNode

            val textChild = node.childNodes.first() as StringValue
            assertDoesNotThrow { textChild.string.toInt() }
        }

        @Test
        fun `generate a number in a nested node`() {
            val type = parsedPattern("<parent><child1><child2>(number)</child2></child1></parent>")
            val parent = type.generate(Resolver()) as XMLNode

            val child2 = parent.getXMLNodeByPath("child1.child2")
            val textChild = child2.childNodes.first() as StringValue
            assertDoesNotThrow { textChild.string.toInt() }
        }

        @Test
        fun `generate a value with a list of types`() {
            val itemsType = parsedPattern("<items>(Item*)</items>")
            val itemType = parsedPattern("<item>(string)</item>")

            val resolver = Resolver(newPatterns = mapOf("(Item)" to itemType))
            val xmlValue = itemsType.generate(resolver) as XMLNode

            for (node in xmlValue.childNodes.map { it as XMLNode }) {
                assertThat(node.childNodes.size == 1)
                assertThat(node.childNodes[0]).isInstanceOf(StringValue::class.java)
            }
        }

        @Test
        fun `generate a value with namespace intact`() {
            val itemsType = parsedPattern("<ns1:items xmlns:ns1=\"http://example.com/items\">(string)</ns1:items>")

            val xmlValue = itemsType.generate(Resolver()) as XMLNode

            assertThat(xmlValue.name).isEqualTo("items")
            assertThat(xmlValue.realName).isEqualTo("ns1:items")

            assertThat(xmlValue.attributes.size).isOne()
            assertThat(xmlValue.attributes["xmlns:ns1"]).isEqualTo(StringValue("http://example.com/items"))

            assertThat(xmlValue.childNodes.size).isOne()
            assertThat(xmlValue.childNodes.first()).isInstanceOf(StringValue::class.java)
        }

        @Test
        fun `xsi type selects compatible WSDL derived pattern during generation`() {
            val resolver = Resolver(newPatterns = animalPatterns())
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(
                        TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal")),
                        "xmlns:tns" to ExactValuePattern(StringValue(ANIMAL_NAMESPACE)),
                        "xmlns:xsi" to ExactValuePattern(StringValue("http://www.w3.org/2001/XMLSchema-instance")),
                        "xsi:type" to ExactValuePattern(StringValue("tns:Dog")),
                    ),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )

            val generated = pattern.generate(resolver)

            assertThat(generated.realName).isEqualTo("tns:Animal")
            assertThat(generated.attributes["xsi:type"]).isEqualTo(StringValue("tns:Dog"))
            assertThat(generated.childNodes.filterIsInstance<XMLNode>().map(XMLNode::realName))
                .containsExactly("tns:name", "tns:breed")
        }

        @Test
        fun `generate uses compatible WSDL concrete subtype variants instead of abstract base type`() {
            val resolver = Resolver(newPatterns = animalPatterns(animalIsAbstract = true))
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(
                        TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal")),
                        "xmlns:tns" to ExactValuePattern(StringValue(ANIMAL_NAMESPACE)),
                    ),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )

            val generated = pattern.generate(resolver)

            when (generated.attributes["xsi:type"]?.toStringLiteral()) {
                "tns:Dog" -> assertThat(generated.childNodes.filterIsInstance<XMLNode>().map(XMLNode::realName))
                    .containsExactly("tns:name", "tns:breed")
                "tns:WorkingDog" -> assertThat(generated.childNodes.filterIsInstance<XMLNode>().map(XMLNode::realName))
                    .containsExactly("tns:name", "tns:breed", "tns:job")
                "tns:Cat" -> assertThat(generated.childNodes.filterIsInstance<XMLNode>().map(XMLNode::realName))
                    .containsExactly("tns:name", "tns:lives")
                else -> fail("Expected generate to pick a concrete WSDL subtype")
            }
        }

        @Test
        fun `newBasedOn uses compatible WSDL concrete subtype variants instead of abstract base type`() {
            val resolver = Resolver(newPatterns = animalPatterns(animalIsAbstract = true))
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(
                        TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal")),
                        "xmlns:tns" to ExactValuePattern(StringValue(ANIMAL_NAMESPACE)),
                    ),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )

            val generated = pattern.newBasedOn(Row(), resolver)
                .map { it.value as XMLPattern }
                .map { it.generate(resolver) }
                .toList()

            assertThat(generated.map { it.attributes["xsi:type"]?.toStringLiteral() })
                .containsExactlyInAnyOrder("tns:Dog", "tns:WorkingDog", "tns:Cat")
            assertThat(generated).noneMatch { it.attributes["xsi:type"] == null }
        }

        @Test
        fun `newBasedOn uses existing compatible xsi type instead of expanding every WSDL variant`() {
            val resolver = Resolver(newPatterns = animalPatterns())
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(
                        TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal")),
                        "xmlns:tns" to ExactValuePattern(StringValue(ANIMAL_NAMESPACE)),
                        "xmlns:xsi" to ExactValuePattern(StringValue("http://www.w3.org/2001/XMLSchema-instance")),
                        "xsi:type" to ExactValuePattern(StringValue("tns:Dog")),
                    ),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )

            val generated = pattern.newBasedOn(Row(), resolver)
                .map { it.value as XMLPattern }
                .map { it.generate(resolver) }
                .toList()

            assertThat(generated).hasSize(1)
            assertThat(generated.single().attributes["xsi:type"]?.toStringLiteral()).isEqualTo("tns:Dog")
            assertThat(generated.single().childNodes.filterIsInstance<XMLNode>().map(XMLNode::realName))
                .containsExactly("tns:name", "tns:breed")
        }

        @Test
        fun `newBasedOn uses existing compatible schema instance type with non xsi prefix`() {
            val resolver = Resolver(newPatterns = animalPatterns())
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(
                        TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal")),
                        "xmlns:tns" to ExactValuePattern(StringValue(ANIMAL_NAMESPACE)),
                        "xmlns:typeNs" to ExactValuePattern(StringValue("http://www.w3.org/2001/XMLSchema-instance")),
                        "typeNs:type" to ExactValuePattern(StringValue("tns:Dog")),
                    ),
                    namespaceUri = ANIMAL_NAMESPACE,
                    attributeNamespaceUris = mapOf("typeNs:type" to "http://www.w3.org/2001/XMLSchema-instance"),
                )
            )

            val generated = pattern.newBasedOn(Row(), resolver)
                .map { it.value as XMLPattern }
                .map { it.generate(resolver) }
                .toList()

            assertThat(generated).hasSize(1)
            assertThat(generated.single().attributes["typeNs:type"]?.toStringLiteral()).isEqualTo("tns:Dog")
            assertThat(generated.single().childNodes.filterIsInstance<XMLNode>().map(XMLNode::realName))
                .containsExactly("tns:name", "tns:breed")
        }

        @Test
        fun `generate avoids xsi prefix when it is already bound to a different namespace`() {
            val resolver = Resolver(newPatterns = animalPatterns(animalIsAbstract = true))
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(
                        TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal")),
                        "xmlns:tns" to ExactValuePattern(StringValue(ANIMAL_NAMESPACE)),
                        "xmlns:xsi" to ExactValuePattern(StringValue("http://example.com/not-schema-instance")),
                    ),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )

            val generated = pattern.newBasedOn(Row(), resolver)
                .map { it.value as XMLPattern }
                .map { it.generate(resolver) }
                .first()
            val schemaInstanceTypeAttribute = generated.attributes.keys.single { it.endsWith(":type") }
            val schemaInstancePrefix = schemaInstanceTypeAttribute.substringBefore(":")

            assertThat(schemaInstancePrefix).isNotEqualTo("xsi")
            assertThat(generated.namespaces["xsi"]).isEqualTo("http://example.com/not-schema-instance")
            assertThat(generated.namespaces[schemaInstancePrefix]).isEqualTo("http://www.w3.org/2001/XMLSchema-instance")
        }

        @Test
        fun `newBasedOn includes named WSDL simple base type and compatible variants`() {
            val resolver = Resolver(newPatterns = codePatterns())
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Code",
                    realName = "tns:Code",
                    attributes = mapOf(
                        TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_BaseCode")),
                        "xmlns:tns" to ExactValuePattern(StringValue(ANIMAL_NAMESPACE)),
                    ),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )

            val generated = pattern.newBasedOn(Row(), resolver)
                .map { it.value as XMLPattern }
                .map { it.generate(resolver) }
                .toList()

            assertThat(generated.map { it.attributes["xsi:type"]?.toStringLiteral() })
                .contains(null, "tns:ConstrainedCode")
            assertThat(generated.filter { it.attributes["xsi:type"]?.toStringLiteral() == "tns:ConstrainedCode" }
                .map { it.childNodes.single().toStringLiteral() })
                .allMatch { value -> value.matches(Regex("[A-Z0-9]{6,}")) }
        }

        @Test
        fun `newBasedOn ignores WSDL simple variants from XML Schema namespace`() {
            val resolver = Resolver(newPatterns = codePatternsWithXMLSchemaNamespaceVariant())
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Code",
                    realName = "tns:Code",
                    attributes = mapOf(
                        TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_BaseCode")),
                        "xmlns:tns" to ExactValuePattern(StringValue(ANIMAL_NAMESPACE)),
                    ),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )

            val generated = pattern.newBasedOn(Row(), resolver)
                .map { it.value as XMLPattern }
                .map { it.generate(resolver) }
                .toList()

            assertThat(generated.map { it.attributes["xsi:type"]?.toStringLiteral() })
                .contains(null, "tns:ConstrainedCode")
                .doesNotContain("xs:string")
        }

        @Test
        fun `newBasedOn uses WSDL base type when no compatible derived variants exist`() {
            val resolver = Resolver(newPatterns = animalWithoutDerivedPatterns())
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(
                        TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal")),
                        "xmlns:tns" to ExactValuePattern(StringValue(ANIMAL_NAMESPACE)),
                    ),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )

            val generated = pattern.newBasedOn(Row(), resolver)
                .map { it.value as XMLPattern }
                .map { it.generate(resolver) }
                .toList()

            assertThat(generated).hasSize(1)
            assertThat(generated.single().attributes["xsi:type"]).isNull()
            assertThat(generated.single().childNodes.filterIsInstance<XMLNode>().map(XMLNode::realName))
                .containsExactly("tns:name")
        }

        @Test
        fun `anything value becomes a random string`() {
            val xmlNode = XMLPattern("<data>(anything)</data>").generate(Resolver())
            assertThat(xmlNode.childNodes.first().toStringLiteral()).isNotBlank()
        }

        @Test
        fun `optional xml node generation produces zero or one occurrence`() {
            val type = XMLPattern("<data><item $isOptional>(string)</item></data>")

            val skipped = type.generate(Resolver(xmlGenerationDecisions = FixedXMLGenerationDecisions(false)))
            val included = type.generate(Resolver(xmlGenerationDecisions = FixedXMLGenerationDecisions(true)))

            assertThat(skipped.childNodes.filterIsInstance<XMLNode>().count { it.name == "item" }).isEqualTo(0)
            assertThat(included.childNodes.filterIsInstance<XMLNode>().count { it.name == "item" }).isEqualTo(1)
        }

        @Test
        fun `recursive optional xml node generation skips schema already being generated`() {
            val nodeType = XMLPattern("<SPECMATIC_TYPE><id>(string)</id><child $isOptional $TYPE_ATTRIBUTE_NAME=\"Node\" /></SPECMATIC_TYPE>")
            val type = XMLPattern("<data><child $isOptional $TYPE_ATTRIBUTE_NAME=\"Node\" /></data>")
            val resolver = Resolver(
                newPatterns = mapOf("(Node)" to nodeType),
                xmlGenerationDecisions = FixedXMLGenerationDecisions(true)
            )

            val generatedChild = type.generate(resolver).childNodes.filterIsInstance<XMLNode>().single { it.name == "child" }

            assertThat(generatedChild.childNodes.filterIsInstance<XMLNode>().map { it.name }).containsExactly("id")
        }

        @Test
        fun `values should be generated for nested values`() {
            val customerType = XMLPattern("<SPECMATIC_TYPE><name>John</name></SPECMATIC_TYPE>")
            val salesDataType = XMLPattern("<sales><customer specmatic_type=\"Customer\" /></sales>")

            val resolver = Resolver(newPatterns = mapOf("(Customer)" to customerType))

            val salesDataValue =
                salesDataType.newBasedOn(Row(), resolver).map { it.value as XMLPattern }.map { it.generate(resolver) }
                    .first()
            val expected = xmlNode("sales") {
                xmlNode("customer") {
                    xmlNode("name") {
                        text("John")
                    }
                }
            }

            assertThat(salesDataValue).isEqualTo(expected)
        }

        @Test
        fun `values should be generated for nested referenced types`() {
            val customerType = XMLPattern("<SPECMATIC_TYPE><name>(string)</name></SPECMATIC_TYPE>")
            val salesDataType = XMLPattern("<sales><customer specmatic_type=\"Customer\" /></sales>")

            val resolver = Resolver(newPatterns = mapOf("(Customer)" to customerType))

            val salesDataValue =
                salesDataType.newBasedOn(Row(), resolver).map { it.value as XMLPattern }.map { it.generate(resolver) }
                    .first()

            assertThat(salesDataValue.findFirstChildByPath("customer.name")?.childNodes?.first()).isInstanceOf(StringValue::class.java)
        }

        @Test
        fun `required recursive xml child is omitted at cycle cutoff`() {
            val responseType = XMLPattern("<response><characteristics $TYPE_ATTRIBUTE_NAME=\"AttributeValuePair\"/></response>")
            val attributeValuePairType = XMLPattern(
                """
                <AttributeValuePair>
                    <attributeName>(string)</attributeName>
                    <attributeValue>(string)</attributeValue>
                    <characteristics $TYPE_ATTRIBUTE_NAME="AttributeValuePair"/>
                    <action>(string)</action>
                    <unitOfMeasure>(string)</unitOfMeasure>
                </AttributeValuePair>
                """.trimIndent()
            )
            val resolver = Resolver(newPatterns = mapOf("(AttributeValuePair)" to attributeValuePairType))

            val generated = responseType.generate(resolver)

            val characteristics = generated.getXMLNodeByPath("characteristics")
            assertThat(characteristics.childNodes.filterIsInstance<XMLNode>().map { it.name })
                .containsExactly("attributeName", "attributeValue", "action", "unitOfMeasure")
        }

        @Test
        fun `required recursive xml child in generated examples is omitted at cycle cutoff`() {
            val responseType = XMLPattern(
                """
                <response>
                    <characteristics>
                        <attributeName>(string)</attributeName>
                        <attributeValue>(string)</attributeValue>
                        <characteristics $TYPE_ATTRIBUTE_NAME="AttributeValuePair"/>
                        <action>(string)</action>
                        <unitOfMeasure>(string)</unitOfMeasure>
                    </characteristics>
                </response>
                """.trimIndent()
            )
            val attributeValuePairType = XMLPattern(
                """
                <AttributeValuePair>
                    <attributeName>(string)</attributeName>
                    <attributeValue>(string)</attributeValue>
                    <characteristics $TYPE_ATTRIBUTE_NAME="AttributeValuePair"/>
                    <action>(string)</action>
                    <unitOfMeasure>(string)</unitOfMeasure>
                </AttributeValuePair>
                """.trimIndent()
            )
            val resolver = Resolver(newPatterns = mapOf("(AttributeValuePair)" to attributeValuePairType))

            val generatedPattern = responseType.newBasedOn(Row(), resolver).map { it.value as XMLPattern }.first()
            val generated = generatedPattern.generate(resolver)

            val nestedCharacteristics = generated.getXMLNodeByPath("characteristics.characteristics")
            assertThat(nestedCharacteristics.childNodes.filterIsInstance<XMLNode>().map { it.name })
                .containsExactly("attributeName", "attributeValue", "action", "unitOfMeasure")
        }

        @Test
        fun `required recursive xml child in generated negative examples is omitted at cycle cutoff`() {
            val responseType = XMLPattern(
                """
                <response>
                    <characteristics>
                        <attributeName>(string)</attributeName>
                        <attributeValue>(string)</attributeValue>
                        <characteristics $TYPE_ATTRIBUTE_NAME="AttributeValuePair"/>
                        <action>(string)</action>
                        <unitOfMeasure>(string)</unitOfMeasure>
                    </characteristics>
                </response>
                """.trimIndent()
            )
            val attributeValuePairType = XMLPattern(
                """
                <AttributeValuePair>
                    <attributeName>(string)</attributeName>
                    <attributeValue>(string)</attributeValue>
                    <characteristics $TYPE_ATTRIBUTE_NAME="AttributeValuePair"/>
                    <action>(string)</action>
                    <unitOfMeasure>(string)</unitOfMeasure>
                </AttributeValuePair>
                """.trimIndent()
            )
            val resolver = Resolver(newPatterns = mapOf("(AttributeValuePair)" to attributeValuePairType))

            val generatedPattern = responseType.negativeBasedOn(Row(), resolver).map { it.value as XMLPattern }.first()
            val generated = generatedPattern.generate(resolver)

            val nestedCharacteristics = generated.getXMLNodeByPath("characteristics.characteristics")
            assertThat(nestedCharacteristics.childNodes.filterIsInstance<XMLNode>().map { it.name })
                .containsExactly("attributeName", "attributeValue", "action", "unitOfMeasure")
        }

        @Test
        fun `choice group newBasedOn omits recursive xml type reference at cycle cutoff`() {
            val choiceGroup = XMLChoiceGroupPattern(
                choices = listOf(listOf(XMLPattern("<child $TYPE_ATTRIBUTE_NAME=\"Node\"/>")))
            )
            val resolver = Resolver(newPatterns = mapOf(withPatternDelimiters("Node") to XMLPattern("<Node/>")))

            val expandedChoice = resolver.withCyclePrevention(DeferredPattern(withPatternDelimiters("Node"))) { cycleResolver ->
                choiceGroup.newBasedOn(cycleResolver).first() as XMLSequencePattern
            }

            assertThat(expandedChoice.members).isEmpty()
        }

        @Test
        fun `choice group row based newBasedOn omits recursive xml type reference at cycle cutoff`() {
            val choiceGroup = XMLChoiceGroupPattern(
                choices = listOf(listOf(XMLPattern("<child $TYPE_ATTRIBUTE_NAME=\"Node\"/>")))
            )
            val resolver = Resolver(newPatterns = mapOf(withPatternDelimiters("Node") to XMLPattern("<Node/>")))

            val expandedChoice = resolver.withCyclePrevention(DeferredPattern(withPatternDelimiters("Node"))) { cycleResolver ->
                choiceGroup.newBasedOn(Row(), cycleResolver).first().value as XMLSequencePattern
            }

            assertThat(expandedChoice.members).isEmpty()
        }

        @Test
        fun `finite recursive xml sample matches at cycle cutoff`() {
            val responseType = XMLPattern(
                """
                <response>
                    <characteristics>
                        <attributeName>(string)</attributeName>
                        <attributeValue>(string)</attributeValue>
                        <characteristics $TYPE_ATTRIBUTE_NAME="AttributeValuePair"/>
                        <action>(string)</action>
                        <unitOfMeasure>(string)</unitOfMeasure>
                    </characteristics>
                </response>
                """.trimIndent()
            )
            val attributeValuePairType = XMLPattern(
                """
                <AttributeValuePair>
                    <attributeName>(string)</attributeName>
                    <attributeValue>(string)</attributeValue>
                    <characteristics $TYPE_ATTRIBUTE_NAME="AttributeValuePair"/>
                    <action>(string)</action>
                    <unitOfMeasure>(string)</unitOfMeasure>
                </AttributeValuePair>
                """.trimIndent()
            )
            val response = toXMLNode(
                """
                <response>
                    <characteristics>
                        <attributeName>name</attributeName>
                        <attributeValue>value</attributeValue>
                        <characteristics>
                            <attributeName>nested-name</attributeName>
                            <attributeValue>nested-value</attributeValue>
                            <action>nested-action</action>
                            <unitOfMeasure>nested-unit</unitOfMeasure>
                        </characteristics>
                        <action>action</action>
                        <unitOfMeasure>unit</unitOfMeasure>
                    </characteristics>
                </response>
                """.trimIndent()
            )
            val resolver = Resolver(newPatterns = mapOf("(AttributeValuePair)" to attributeValuePairType))

            assertThat(responseType.matches(response, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `values should be generated for nested multiples`() {
            val customerType = XMLPattern("<SPECMATIC_TYPE><name>(string)</name></SPECMATIC_TYPE>")
            val salesDataType = XMLPattern("<sales><customer specmatic_type=\"Customer\" specmatic_occurs=\"multiple\"/></sales>")

            val resolver = Resolver(newPatterns = mapOf("(Customer)" to customerType))

            val salesDataValue =
                salesDataType.newBasedOn(Row(), resolver).map { it.value as XMLPattern }.map { it.generate(resolver) }
                    .first()

            assertThat(salesDataValue.findFirstChildByPath("customer.name")?.childNodes?.first()).isInstanceOf(StringValue::class.java)
        }
    }

    @Nested
    inner class MatchValues {
        @Test
        fun `should fail to match nulls gracefully`() {
            NullValue shouldNotMatch XMLPattern("<data></data>")
        }

        @Test
        fun `should match a number within a structure`() {
            toXMLNode("<outer><inner>1</inner></outer>") shouldMatch XMLPattern("<outer><inner>(number)</inner></outer>")
        }

        @Test
        fun `should match a type with whitespace`() {
            val xmlSpecWithWhitespace = """
<outer>
    <inner>
        (number)
    </inner>
</outer>
""".trimMargin()
            toXMLNode("<outer><inner>1</inner></outer>") shouldMatch XMLPattern(xmlSpecWithWhitespace)
        }

        @Test
        fun `optional node text should match non empty value`() {
            toXMLNode("<data>1</data>") shouldMatch XMLPattern("<data>(number?)</data>")
        }

        @Test
        fun `optional node text should match empty value`() {
            toXMLNode("<data></data>") shouldMatch XMLPattern("<data>(number?)</data>")
        }

        @Test
        fun `should not match a value that doesn't conform to the specified type`() {
            toXMLNode("<outer><inner>abc</inner></outer>") shouldNotMatch XMLPattern("<outer><inner>(number)</inner></outer>")
        }

        @Test
        fun `should not match a missing node`() {
            toXMLNode("<person><name>Jane</name></person>") shouldNotMatch XMLPattern("<person><name>(string)</name><address>(string)</address></person>")
        }

        @Test
        fun `list type should match multiple xml values of the same type`() {
            val numberInfoPattern = XMLPattern("<number>(number)</number>")
            val resolver = Resolver(newPatterns = mapOf("(NumberInfo)" to numberInfoPattern))
            val answerPattern = XMLPattern("<answer>(NumberInfo*)</answer>")
            val value = toXMLNode("<answer><number>10</number><number>20</number></answer>")

            assertThat(resolver.matchesPattern(null, answerPattern, value)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `xsi type selects compatible WSDL derived complex type`() {
            val resolver = Resolver(newPatterns = animalPatterns())
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal"))),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )
            val value = toXMLNode(
                """
                <tns:Animal xmlns:tns="$ANIMAL_NAMESPACE"
                            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                            xsi:type="tns:Dog">
                  <tns:name>Fido</tns:name>
                  <tns:breed>Beagle</tns:breed>
                </tns:Animal>
                """.trimIndent()
            )

            assertThat(pattern.matches(value, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `schema instance type with non xsi prefix selects compatible WSDL derived complex type`() {
            val resolver = Resolver(newPatterns = animalPatterns())
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal"))),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )
            val value = toXMLNode(
                """
                <tns:Animal xmlns:tns="$ANIMAL_NAMESPACE"
                            xmlns:typeNs="http://www.w3.org/2001/XMLSchema-instance"
                            typeNs:type="tns:Dog">
                  <tns:name>Fido</tns:name>
                  <tns:breed>Beagle</tns:breed>
                </tns:Animal>
                """.trimIndent()
            )

            assertThat(pattern.matches(value, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `type attribute with wrong namespace does not select WSDL derived complex type`() {
            val resolver = Resolver(newPatterns = animalPatterns())
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal"))),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )
            val value = toXMLNode(
                """
                <tns:Animal xmlns:tns="$ANIMAL_NAMESPACE"
                            xmlns:typeNs="http://example.com/not-schema-instance"
                            typeNs:type="tns:Dog">
                  <tns:name>Fido</tns:name>
                  <tns:breed>Beagle</tns:breed>
                </tns:Animal>
                """.trimIndent()
            )

            val result = pattern.matches(value, resolver)

            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).contains("typeNs:type")
        }

        @Test
        fun `xsi type fails when known WSDL type is incompatible with declared type`() {
            val resolver = Resolver(newPatterns = animalPatterns())
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal"))),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )
            val value = toXMLNode(
                """
                <tns:Animal xmlns:tns="$ANIMAL_NAMESPACE"
                            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                            xsi:type="tns:Vehicle">
                  <tns:registration>KA01AB1234</tns:registration>
                </tns:Animal>
                """.trimIndent()
            )

            val result = pattern.matches(value, resolver)

            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).contains("Invalid xsi:type")
        }

        @Test
        fun `xsi type fails when WSDL type is unknown`() {
            val resolver = Resolver(newPatterns = animalPatterns())
            val pattern = XMLPattern(
                XMLTypeData(
                    name = "Animal",
                    realName = "tns:Animal",
                    attributes = mapOf(TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("tns_Animal"))),
                    namespaceUri = ANIMAL_NAMESPACE,
                )
            )
            val value = toXMLNode(
                """
                <tns:Animal xmlns:tns="$ANIMAL_NAMESPACE"
                            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                            xsi:type="tns:UnknownType">
                  <tns:name>Fido</tns:name>
                </tns:Animal>
                """.trimIndent()
            )

            val result = pattern.matches(value, resolver)

            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).contains("Unknown xsi:type")
            assertThat(result.reportString()).contains("UnknownType")
        }

        @Test
        fun `node with string should match empty node`() {
            val type = parsedPattern("""<name>(string)</name>""")
            val value = parsedValue("""<name/>""")

            val result = type.matches(value, Resolver())
            println(result.toReport())
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `matching works for an xml node with more than one child node`() {
            val type = XMLPattern("<account><name>John Doe</name><address>(string)</address></account>")
            val value = toXMLNode("<account><name>John Doe</name><address>Baker street</address></account>")

            assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        private fun XMLPattern.matches(value: XMLNode) {
            val result = this.matches(value, Resolver())
            println(result.reportString())
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `anything type matches anything`() {
            val anything = XMLPattern("<data>(anything)</data>")

            val string = toXMLNode("<data>hello world</data>")
            val xml = toXMLNode("<data><hello>world</hello></data>")

            anything.matches(string)
            anything.matches(xml)
        }

    }

    private fun animalPatterns(animalIsAbstract: Boolean = false): Map<String, Pattern> {
        val animal = XMLPattern(
            XMLTypeData(
                name = "Animal",
                realName = "tns:Animal",
                nodes = listOf(XMLPattern("<tns:name xmlns:tns=\"$ANIMAL_NAMESPACE\">(string)</tns:name>")),
                namespaceUri = ANIMAL_NAMESPACE,
                wsdlTypeNamespace = ANIMAL_NAMESPACE,
                wsdlTypeName = "Animal",
                wsdlTypeIsAbstract = animalIsAbstract,
            )
        )
        val dog = XMLPattern(
            XMLTypeData(
                name = "Dog",
                realName = "tns:Dog",
                nodes = listOf(
                    XMLPattern("<tns:name xmlns:tns=\"$ANIMAL_NAMESPACE\">(string)</tns:name>"),
                    XMLPattern("<tns:breed xmlns:tns=\"$ANIMAL_NAMESPACE\">(string)</tns:breed>")
                ),
                namespaceUri = ANIMAL_NAMESPACE,
                wsdlTypeNamespace = ANIMAL_NAMESPACE,
                wsdlTypeName = "Dog",
                wsdlBaseTypeNamespace = ANIMAL_NAMESPACE,
                wsdlBaseTypeName = "Animal",
            )
        )
        val vehicle = XMLPattern(
            XMLTypeData(
                name = "Vehicle",
                realName = "tns:Vehicle",
                nodes = listOf(XMLPattern("<tns:registration xmlns:tns=\"$ANIMAL_NAMESPACE\">(string)</tns:registration>")),
                namespaceUri = ANIMAL_NAMESPACE,
                wsdlTypeNamespace = ANIMAL_NAMESPACE,
                wsdlTypeName = "Vehicle",
            )
        )
        val workingDog = XMLPattern(
            XMLTypeData(
                name = "WorkingDog",
                realName = "tns:WorkingDog",
                nodes = listOf(
                    XMLPattern("<tns:name xmlns:tns=\"$ANIMAL_NAMESPACE\">(string)</tns:name>"),
                    XMLPattern("<tns:breed xmlns:tns=\"$ANIMAL_NAMESPACE\">(string)</tns:breed>"),
                    XMLPattern("<tns:job xmlns:tns=\"$ANIMAL_NAMESPACE\">(string)</tns:job>")
                ),
                namespaceUri = ANIMAL_NAMESPACE,
                wsdlTypeNamespace = ANIMAL_NAMESPACE,
                wsdlTypeName = "WorkingDog",
                wsdlBaseTypeNamespace = ANIMAL_NAMESPACE,
                wsdlBaseTypeName = "Dog",
            )
        )
        val cat = XMLPattern(
            XMLTypeData(
                name = "Cat",
                realName = "tns:Cat",
                nodes = listOf(
                    XMLPattern("<tns:name xmlns:tns=\"$ANIMAL_NAMESPACE\">(string)</tns:name>"),
                    XMLPattern("<tns:lives xmlns:tns=\"$ANIMAL_NAMESPACE\">(number)</tns:lives>")
                ),
                namespaceUri = ANIMAL_NAMESPACE,
                wsdlTypeNamespace = ANIMAL_NAMESPACE,
                wsdlTypeName = "Cat",
                wsdlBaseTypeNamespace = ANIMAL_NAMESPACE,
                wsdlBaseTypeName = "Animal",
            )
        )

        return mapOf(
            ANIMAL_TYPE_KEY to animal.withWSDLTypeLookupMetadata(
                knownTypeKeys = ANIMAL_TYPE_KEYS,
                compatibleTypeKeys = mapOf(
                    ANIMAL_TYPE to ANIMAL_TYPE_KEY,
                    DOG_TYPE to DOG_TYPE_KEY,
                    WORKING_DOG_TYPE to WORKING_DOG_TYPE_KEY,
                    CAT_TYPE to CAT_TYPE_KEY,
                ),
                concreteSubtypeKeys = mapOf(
                    DOG_TYPE to DOG_TYPE_KEY,
                    WORKING_DOG_TYPE to WORKING_DOG_TYPE_KEY,
                    CAT_TYPE to CAT_TYPE_KEY,
                ),
            ),
            DOG_TYPE_KEY to dog.withWSDLTypeLookupMetadata(
                knownTypeKeys = ANIMAL_TYPE_KEYS,
                compatibleTypeKeys = mapOf(
                    DOG_TYPE to DOG_TYPE_KEY,
                    WORKING_DOG_TYPE to WORKING_DOG_TYPE_KEY,
                ),
                concreteSubtypeKeys = mapOf(WORKING_DOG_TYPE to WORKING_DOG_TYPE_KEY),
            ),
            WORKING_DOG_TYPE_KEY to workingDog.withWSDLTypeLookupMetadata(
                knownTypeKeys = ANIMAL_TYPE_KEYS,
                compatibleTypeKeys = mapOf(WORKING_DOG_TYPE to WORKING_DOG_TYPE_KEY),
            ),
            CAT_TYPE_KEY to cat.withWSDLTypeLookupMetadata(
                knownTypeKeys = ANIMAL_TYPE_KEYS,
                compatibleTypeKeys = mapOf(CAT_TYPE to CAT_TYPE_KEY),
            ),
            VEHICLE_TYPE_KEY to vehicle.withWSDLTypeLookupMetadata(
                knownTypeKeys = ANIMAL_TYPE_KEYS,
                compatibleTypeKeys = mapOf(VEHICLE_TYPE to VEHICLE_TYPE_KEY),
            ),
        )
    }

    private fun animalWithoutDerivedPatterns(): Map<String, Pattern> {
        val animal = XMLPattern(
            XMLTypeData(
                name = "Animal",
                realName = "tns:Animal",
                nodes = listOf(XMLPattern("<tns:name xmlns:tns=\"$ANIMAL_NAMESPACE\">(string)</tns:name>")),
                namespaceUri = ANIMAL_NAMESPACE,
                wsdlTypeNamespace = ANIMAL_NAMESPACE,
                wsdlTypeName = "Animal",
            )
        )

        val vehicle = XMLPattern(
            XMLTypeData(
                name = "Vehicle",
                realName = "tns:Vehicle",
                nodes = listOf(XMLPattern("<tns:registration xmlns:tns=\"$ANIMAL_NAMESPACE\">(string)</tns:registration>")),
                namespaceUri = ANIMAL_NAMESPACE,
                wsdlTypeNamespace = ANIMAL_NAMESPACE,
                wsdlTypeName = "Vehicle",
            )
        )

        return mapOf(
            ANIMAL_TYPE_KEY to animal,
            VEHICLE_TYPE_KEY to vehicle,
        )
    }

    private fun codePatterns(): Map<String, Pattern> {
        val baseCode = XMLPattern(
            XMLTypeData(
                name = "BaseCode",
                realName = "tns:BaseCode",
                nodes = listOf(StringPattern()),
                namespaceUri = ANIMAL_NAMESPACE,
                wsdlTypeNamespace = ANIMAL_NAMESPACE,
                wsdlTypeName = "BaseCode",
            )
        )
        val constrainedCode = XMLPattern(
            XMLTypeData(
                name = "ConstrainedCode",
                realName = "tns:ConstrainedCode",
                nodes = listOf(StringPattern(minLength = 6, regex = "[A-Z0-9]+")),
                namespaceUri = ANIMAL_NAMESPACE,
                wsdlTypeNamespace = ANIMAL_NAMESPACE,
                wsdlTypeName = "ConstrainedCode",
                wsdlBaseTypeNamespace = ANIMAL_NAMESPACE,
                wsdlBaseTypeName = "BaseCode",
            )
        )

        return mapOf(
            BASE_CODE_TYPE_KEY to baseCode.withWSDLTypeLookupMetadata(
                knownTypeKeys = CODE_TYPE_KEYS,
                compatibleTypeKeys = mapOf(
                    BASE_CODE_TYPE to BASE_CODE_TYPE_KEY,
                    CONSTRAINED_CODE_TYPE to CONSTRAINED_CODE_TYPE_KEY,
                ),
                concreteSubtypeKeys = mapOf(CONSTRAINED_CODE_TYPE to CONSTRAINED_CODE_TYPE_KEY),
            ),
            CONSTRAINED_CODE_TYPE_KEY to constrainedCode.withWSDLTypeLookupMetadata(
                knownTypeKeys = CODE_TYPE_KEYS,
                compatibleTypeKeys = mapOf(CONSTRAINED_CODE_TYPE to CONSTRAINED_CODE_TYPE_KEY),
            ),
        )
    }

    private fun codePatternsWithXMLSchemaNamespaceVariant(): Map<String, Pattern> {
        val xmlSchemaString = XMLPattern(
            XMLTypeData(
                name = "string",
                realName = "xs:string",
                nodes = listOf(StringPattern()),
                namespaceUri = XML_SCHEMA_NAMESPACE,
                wsdlTypeNamespace = XML_SCHEMA_NAMESPACE,
                wsdlTypeName = "string",
                wsdlBaseTypeNamespace = ANIMAL_NAMESPACE,
                wsdlBaseTypeName = "BaseCode",
            )
        )

        return codePatterns().mapValues { (_, pattern) ->
            when ((pattern as? XMLPattern)?.pattern?.wsdlTypeName()) {
                BASE_CODE_TYPE -> pattern.withWSDLTypeLookupMetadata(
                    knownTypeKeys = CODE_TYPE_KEYS_WITH_XML_SCHEMA_TYPE,
                    compatibleTypeKeys = mapOf(
                        BASE_CODE_TYPE to BASE_CODE_TYPE_KEY,
                        CONSTRAINED_CODE_TYPE to CONSTRAINED_CODE_TYPE_KEY,
                        XML_SCHEMA_STRING_TYPE to XML_SCHEMA_STRING_TYPE_KEY,
                    ),
                    concreteSubtypeKeys = mapOf(
                        CONSTRAINED_CODE_TYPE to CONSTRAINED_CODE_TYPE_KEY,
                        XML_SCHEMA_STRING_TYPE to XML_SCHEMA_STRING_TYPE_KEY,
                    ),
                )

                CONSTRAINED_CODE_TYPE -> pattern.withWSDLTypeLookupMetadata(
                    knownTypeKeys = CODE_TYPE_KEYS_WITH_XML_SCHEMA_TYPE,
                    compatibleTypeKeys = mapOf(CONSTRAINED_CODE_TYPE to CONSTRAINED_CODE_TYPE_KEY),
                )

                else -> pattern
            }
        } + mapOf(
            XML_SCHEMA_STRING_TYPE_KEY to xmlSchemaString.withWSDLTypeLookupMetadata(
                knownTypeKeys = CODE_TYPE_KEYS_WITH_XML_SCHEMA_TYPE,
                compatibleTypeKeys = mapOf(XML_SCHEMA_STRING_TYPE to XML_SCHEMA_STRING_TYPE_KEY),
            )
        )
    }

    private fun Pattern.withWSDLTypeLookupMetadata(
        knownTypeKeys: Map<WSDLTypeName, String>,
        compatibleTypeKeys: Map<WSDLTypeName, String>,
        concreteSubtypeKeys: Map<WSDLTypeName, String> = emptyMap(),
    ): Pattern {
        return when (this) {
            is XMLPattern -> copy(
                pattern = pattern.copy(
                    wsdlKnownTypeKeys = knownTypeKeys,
                    wsdlCompatibleTypeKeys = compatibleTypeKeys,
                    wsdlConcreteSubtypeKeys = concreteSubtypeKeys,
                )
            )

            is AnyPattern -> copy(
                pattern = pattern.map {
                    it.withWSDLTypeLookupMetadata(
                        knownTypeKeys,
                        compatibleTypeKeys,
                        concreteSubtypeKeys,
                    )
                }
            )

            else -> this
        }
    }

    @Nested
    inner class BackwardCompatibility {
        @Test
        fun `empty xml node should be compatible with itself`() {
            val type = XMLPattern("<xml/>")
            val result: Result = type.encompasses(type, Resolver(), Resolver())
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `creates two executable combinations per mandatory field with optional children`() {
            val personType = XMLPattern("""
                <person>
                    <name>(string)</name>
                    <address specmatic_type="Address" />
                </person>
            """.trimIndent())

            val addressType = XMLPattern("""
                <SPECMATIC_TYPE>
                    <flat_no specmatic_occurs="optional">(string)</flat_no>
                    <street specmatic_occurs="optional">(string)</street>
                </SPECMATIC_TYPE>
            """.trimIndent())

            val resolver = Resolver(newPatterns = mapOf("(Address)" to addressType))

            val testTypes = personType.newBasedOn(resolver).map { it.toPrettyString() }.toList()

            for (type in testTypes) {
                println(type)
            }

            assertThat(testTypes.size).isEqualTo(2)

            assertThat(testTypes.map { it.trimmedLinesString() }).contains("""
                <person>
                  <name>(string)</name>
                  <address specmatic_type="Address">
                    <flat_no>(string)</flat_no>
                    <street>(string)</street>
                  </address>
                </person>
                """.trimIndent().trimmedLinesString())

            assertThat(testTypes.map { it.trimmedLinesString() }).contains("""
                <person>
                  <name>(string)</name>
                  <address specmatic_type="Address"/>
                </person>
                """.trimIndent().trimmedLinesString())
        }

        @Test
        fun `creates three executable combinations per optional field with optional children`() {
            val personType = XMLPattern("""
                <person>
                    <name>(string)</name>
                    <address specmatic_occurs="optional" specmatic_type="Address" />
                </person>
            """.trimIndent())

            val addressType = XMLPattern("""
                <SPECMATIC_TYPE>
                    <flat_no specmatic_occurs="optional">(string)</flat_no>
                    <street specmatic_occurs="optional">(string)</street>
                </SPECMATIC_TYPE>
            """.trimIndent())

            val resolver = Resolver(newPatterns = mapOf("(Address)" to addressType))

            val testTypes = personType.newBasedOn(resolver).map { it.toPrettyString() }.toList()

            for (type in testTypes) {
                println(type)
            }

            assertThat(testTypes.size).isEqualTo(3)

            assertThat(testTypes.map { it.trimmedLinesString() }).contains("""
                <person>
                  <name>(string)</name>
                  <address specmatic_type="Address">
                    <flat_no>(string)</flat_no>
                    <street>(string)</street>
                  </address>
                </person>
                """.trimIndent().trimmedLinesString())

            assertThat(testTypes.map { it.trimmedLinesString() }).contains("""
                <person>
                  <name>(string)</name>
                  <address specmatic_type="Address"/>
                </person>
                """.trimIndent().trimmedLinesString())

            assertThat(testTypes.map { it.trimmedLinesString() }).contains("""
                <person>
                  <name>(string)</name>
                </person>
                """.trimIndent().trimmedLinesString())
        }

        @Test
        fun `optional node text type should encompass text type`() {
            val resolver = Resolver()

            val bigger = XMLPattern("<data>(number?)</data>")
            val smaller = XMLPattern("<data>(number)</data>")

            assertThat(bigger.encompasses(smaller, resolver, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `optional node text type should encompass empty text`() {
            val resolver = Resolver()

            val bigger = XMLPattern("<data>(number?)</data>")
            val smaller = XMLPattern("<data></data>")

            assertThat(bigger.encompasses(smaller, resolver, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `optional node text type should encompass empty text node without closing tag`() {
            val resolver = Resolver()

            val bigger = XMLPattern("<data>(number?)</data>")
            val smaller = XMLPattern("<data/>")

            assertThat(bigger.encompasses(smaller, resolver, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `sanity check for pattern encompassing`() {
            val numberInfoPattern = XMLPattern("<number>(number)</number>")
            val resolver = Resolver()

            assertThat(numberInfoPattern.encompasses(numberInfoPattern, resolver, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `sanity check for pattern encompassing with raw values`() {
            val numberInfoPattern = XMLPattern("<number>100</number>")
            val resolver = Resolver()

            assertThat(numberInfoPattern.encompasses(numberInfoPattern, resolver, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `sanity check for xml with number type encompassing another with raw number`() {
            val pattern1 = XMLPattern("<number>(number)</number>")
            val pattern2 = XMLPattern("<number>100</number>")
            val resolver = Resolver()

            assertThat(pattern1.encompasses(pattern2, resolver, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `pattern by name should encompass another pattern of the same structure`() {
            val numberInfoPattern = XMLPattern("<number>(number)</number>")
            val resolver = Resolver(newPatterns = mapOf("(Number)" to XMLPattern("<number>(number)</number>")))

            assertThat(resolver.getPattern("(Number)").encompasses(numberInfoPattern, resolver, resolver)).isInstanceOf(
                    Result.Success::class.java)
        }

        @Test
        fun `sanity check for nested pattern encompassing`() {
            val answersPattern = XMLPattern("<answer><number>(number)</number><name>(string)</name></answer>")
            val resolver = Resolver()

            assertThat(answersPattern.encompasses(answersPattern, resolver, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `pattern should not encompass another with different order`() {
            val answersPattern1 = XMLPattern("<answer><number>(number)</number><name>(string)</name></answer>")
            val answersPattern2 = XMLPattern("<answer><name>(string)</name><number>(number)</number></answer>")
            val resolver = Resolver()

            assertThat(answersPattern1.encompasses(answersPattern2, resolver, resolver)).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `repeating pattern should not encompass another with a different repeating type`() {
            val answersPattern1 = XMLPattern("<answers>(Number*)</answers>")
            val answersPattern2 = XMLPattern("<answers>(Number*)</answers>")
            val resolver1 = Resolver(newPatterns = mapOf("(Number)" to parsedPattern("<number>(number)</number>")))
            val resolver2 = Resolver(newPatterns = mapOf("(Number)" to parsedPattern("<number>(string)</number>")))

            assertThat(answersPattern1.encompasses(answersPattern2, resolver1, resolver2)).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `node with finite number of children should not encompass repeating pattern with similar type`() {
            val answersPattern1 = XMLPattern("<answers><number>(number)</number><number>(number)</number></answers>")
            val answersPattern2 = XMLPattern("<answer>(Number*)</answer>")
            val resolver = Resolver(newPatterns = mapOf("(Number)" to parsedPattern("<number>(number)</number>")))

            assertThat(answersPattern1.encompasses(answersPattern2, resolver, resolver)).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `optional attribute encompasses non optional`() {
            val bigger = XMLPattern("""<number val$XML_ATTR_OPTIONAL_SUFFIX="(number)">(number)</number>""")
            val smaller = XMLPattern("""<number val="(number)">(number)</number>""")
            assertThat(bigger.encompasses(smaller, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `multiple should not encompass single`() {
            val multiple = XMLPattern("<number $occursMultipleTimes>(number)</number>")
            val single = XMLPattern("<number>(number)</number>")

            assertThat(multiple.encompasses(single, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `multiple should should encompass optional`() {
            val multiple = XMLPattern("<number $occursMultipleTimes>(number)</number>")
            val optional = XMLPattern("<number $isOptional>(number)</number>")

            assertThat(multiple.encompasses(optional, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `multiple should encompass multiple`() {
            val multiple1 = XMLPattern("<number $occursMultipleTimes>(number)</number>")
            val multiple2 = XMLPattern("<number $occursMultipleTimes>(number)</number>")

            assertThat(multiple1.encompasses(multiple2, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `optional should encompass single`() {
            val optional = XMLPattern("<number $isOptional>(number)</number>")
            val single = XMLPattern("<number>(number)</number>")

            assertThat(optional.encompasses(single, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `optional should not encompass multiple`() {
            val multiple = XMLPattern("<number $occursMultipleTimes>(number)</number>")
            val optional = XMLPattern("<number $isOptional>(number)</number>")

            assertThat(optional.encompasses(multiple, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `optional should encompass optional`() {
            val optional1 = XMLPattern("<number $occursMultipleTimes>(number)</number>")
            val optional2 = XMLPattern("<number $occursMultipleTimes>(number)</number>")

            assertThat(optional1.encompasses(optional2, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `when some nodes are not matched at the end the two types are not compatible`() {
            val type1 = XMLPattern("<contact_info><address $occursMultipleTimes>(string)</address></contact_info>")
            val type2 = XMLPattern("<contact_info><address $occursMultipleTimes>(string)</address><phone>(number)</phone></contact_info>")

            assertThat(type1.encompasses(type2, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `node containing anything is backward compatible with itself`() {
            val nodeContainingAnything = XMLPattern("<data>(anything)</data>")
            assertThat(nodeContainingAnything.encompasses(nodeContainingAnything, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `node containing anything is NOT backward compatible with a node with a different type`() {
            val nodeContainingAnything = XMLPattern("<data>(anything)</data>")
            val nodeContainingString = XMLPattern("<data>(string)</data>")
            assertThat(nodeContainingAnything.encompasses(nodeContainingString, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `edge case - two nodes with the same content followed by a different third node`() {
            val node = XMLPattern("<content><data>(string)</data><data>(string)</data><should_not_break>(string)</should_not_break></content>")
            assertThat(node.encompasses(node, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }
    }

    @Nested
    inner class Attributes {
        @Test
        fun `sanity check for attributes`() {
            val pattern = XMLPattern("""<number val="(number)">(number)</number>""")
            assertThat(pattern.encompasses(pattern, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `sanity check for attributes with raw values`() {
            val pattern = XMLPattern("""<number val="10">(number)</number>""")
            assertThat(pattern.encompasses(pattern, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `different raw values in attributes should not match`() {
            val pattern1 = XMLPattern("""<number val="10">(number)</number>""")
            val pattern2 = XMLPattern("""<number val="20">(number)</number>""")
            assertThat(pattern1.encompasses(pattern2, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `should generate a value when the xml contains an empty node`() {
            val pattern = XMLPattern("<data><empty/><value>10</value></data>")
            val value = pattern.generate(Resolver())

            assertThat(value.toStringLiteral()).isEqualToIgnoringWhitespace("<data><empty/><value>10</value></data>")
        }

        @Test
        fun `should pick up node names from examples`() {
            val xmlType = XMLPattern("<data><name>(string)</name><age>(number)</age></data>")
            val example = Row(listOf("name", "age"), listOf("John Doe", "10"))

            val newTypes = xmlType.newBasedOn(example, Resolver()).map { it.value as XMLPattern }.toList()

            val xmlNode = newTypes[0].generate(Resolver())
            assertThat(xmlNode.toStringLiteral()).isEqualToIgnoringWhitespace("<data><name>John Doe</name><age>10</age></data>")
        }

        @Test
        fun `should pick up attribute names from examples`() {
            val xmlType = XMLPattern("""<data name="(string)" age="(number)"></data>""")
            val example = Row(listOf("name", "age"), listOf("John Doe", "10"))

            val newTypes = xmlType.newBasedOn(example, Resolver()).map { it.value as XMLPattern }.toList()

            val xmlNode = newTypes[0].generate(Resolver())
            assertThat(xmlNode).isEqualTo(toXMLNode("""<data age="10" name="John Doe"/>"""))
        }

        @Test
        fun `should pick up attribute names with optional values from examples`() {
            val xmlType = XMLPattern("""<data name="(string?)" age="(number?)"></data>""")
            val example = Row(listOf("name", "age"), listOf("John Doe", "10"))

            val newTypes = xmlType.newBasedOn(example, Resolver()).map { it.value as XMLPattern }.toList()

            val xmlNode = newTypes[0].generate(Resolver())
            assertThat(xmlNode).isEqualTo(toXMLNode("""<data age="10" name="John Doe"/>"""))
        }

        @Test
        fun `should pick up attribute names with optional values from empty examples`() {
            val xmlType = XMLPattern("""<data name="(string?)" age="(number?)"></data>""")
            val example = Row(listOf("name", "age"), listOf("", ""))

            val newTypes = xmlType.newBasedOn(example, Resolver()).map { it.value as XMLPattern }.toList()
            assertThat(newTypes.size).isOne()

            val xmlNode = newTypes[0].generate(Resolver())
            assertThat(xmlNode).isEqualTo(toXMLNode("""<data age="" name=""/>"""))
        }

        @Test
        fun `optional attribute should pick up example value`() {
            val type = XMLPattern("""<number val$XML_ATTR_OPTIONAL_SUFFIX="(number)"></number>""")
            val example = Row(listOf("val"), listOf("10"))

            val newTypes = type.newBasedOn(example, Resolver()).map { it.value as XMLPattern }.toList()
            assertThat(newTypes.size).isOne()

            val xmlNode = newTypes[0].generate(Resolver())
            assertThat(xmlNode.toStringLiteral()).isEqualTo("""<number val="10"/>""")
        }

        @Test
        fun `optional attribute without examples should generate all tests for the attribute and without the attribute`() {
            val type = XMLPattern("""<number val$XML_ATTR_OPTIONAL_SUFFIX="(number)"></number>""")

            val newTypes = type.newBasedOn(Row(), Resolver()).map { it.value as XMLPattern }.toList()
            assertThat(newTypes.size).isEqualTo(2)

            val flags = mutableListOf<String>()

            for (newType in newTypes) {
                when {
                    newType.pattern.attributes.containsKey("val") -> flags.add("with")
                    else -> flags.add("without")
                }
            }

            assertThat(flags).hasSize(2)
            assertThat(flags).contains("with")
            assertThat(flags).contains("without")
        }

        @Test
        fun `sanity test that double optional gets handled right`() {
            val type =
                XMLPattern("""<number val$XML_ATTR_OPTIONAL_SUFFIX$XML_ATTR_OPTIONAL_SUFFIX="(number)"></number>""")

            val newTypes = type.newBasedOn(Row(), Resolver()).map { it.value as XMLPattern }.toList()
            assertThat(newTypes).hasSize(2)

            val flags = mutableListOf<String>()

            for (newType in newTypes) {
                when {
                    newType.pattern.attributes.containsKey("val$XML_ATTR_OPTIONAL_SUFFIX") -> flags.add("with")
                    else -> flags.add("without")
                }
            }

            assertThat(flags).hasSize(2)
            assertThat(flags).contains("with")
            assertThat(flags).contains("without")
        }
    }

    @Nested
    inner class Examples {
        @Test
        fun `should pick up node names with optional values from examples`() {
            val xmlType = XMLPattern("<data><name>(string?)</name><age>(number?)</age></data>")
            val example = Row(listOf("name", "age"), listOf("John Doe", "10"))

            val newTypes = xmlType.newBasedOn(example, Resolver()).map { it.value as XMLPattern }.toList()

            val xmlNode = newTypes[0].generate(Resolver())
            assertThat(xmlNode.toStringLiteral()).isEqualToIgnoringWhitespace("<data><name>John Doe</name><age>10</age></data>")
        }

        @Test
        fun `will not pick up node names with values from invalid examples`() {
            val xmlType = XMLPattern("<data><name>(string?)</name><age>(number?)</age></data>")
            val example = Row(listOf("name", "age"), listOf("John Doe", "ABC"))

            assertThatThrownBy { xmlType.newBasedOn(example, Resolver()).map { it.value as XMLPattern }.toList() }.isInstanceOf(ContractException::class.java)
        }
    }

    @Nested
    inner class TypeLookup {
        @Test
        fun `do a type lookup for a node with the spec namespace and match the type to the given a node`() {
            val nameType = parsedPattern("<$TYPE_ATTRIBUTE_NAME>(string)</$TYPE_ATTRIBUTE_NAME>")
            val personType = parsedPattern("<person><name $TYPE_ATTRIBUTE_NAME=\"Name\"/></person>")

            val resolver = Resolver(newPatterns = mapOf("(Name)" to nameType))

            val xmlNode = parsedValue("<person><name>Jill</name></person>")
            assertThat(resolver.matchesPattern(null, personType, xmlNode).isSuccess()).isTrue
        }

        @Test
        fun `do a type lookup for a node with the type attribute and match the name to the current type but the namespaces and child nodes against the looked up type`() {
            val nameType = parsedPattern("<$TYPE_ATTRIBUTE_NAME>(string)</$TYPE_ATTRIBUTE_NAME>")
            val personType = parsedPattern("<person><name $TYPE_ATTRIBUTE_NAME=\"Name\"/></person>")

            val resolver = Resolver(newPatterns = mapOf("(Name)" to nameType))

            val xmlNode = parsedValue("<person><name>Jill</name></person>")
            assertThat(resolver.matchesPattern(null, personType, xmlNode).isSuccess()).isTrue
        }
    }

    @Nested
    inner class NewOptionalNodesSyntax {
        @Test
        fun `last node can be optional`() {
            val type = XMLPattern("<account><name>(string)</name><address $isOptional>(string)</address></account>")
            val value = toXMLNode("<account><name>John Doe</name></account>")

            assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `last typed node can be optional`() {
            val accountType = XMLPattern("<account><name>(string)</name><address $TYPE_ATTRIBUTE_NAME=\"Address\" $isOptional/></account>")
            val addressType = XMLPattern("<$TYPE_ATTRIBUTE_NAME>(string)</$TYPE_ATTRIBUTE_NAME>")
            val value = toXMLNode("<account><name>John Doe</name></account>")

            val resolver = Resolver(newPatterns = mapOf("(Address)" to addressType))

            assertThat(accountType.matches(value, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `second to last node can be optional`() {
            val type = XMLPattern("<account><name>(string)</name><address $isOptional>(string)</address><phone>(number)</phone></account>")
            val value = toXMLNode("<account><name>John Doe</name><phone>10</phone></account>")

            assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `multiple nodes can be optional`() {
            val type = XMLPattern("<account><name $isOptional>(string)</name><address $isOptional>(string)</address><phone>(number)</phone></account>")
            val value = toXMLNode("<account><phone>10</phone></account>")

            assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `multiple optional nodes can be corresponding nodes that are actually present`() {
            val type = XMLPattern("<account><name $isOptional>(string)</name><address $isOptional>(string)</address><phone>(number)</phone></account>")
            val value = toXMLNode("<account><name>Jane Doe</name><address>Baker Street</address><phone>10</phone></account>")

            assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
        }


        @Test
        fun `an optional type of the same name should fail if the node does not match with an appropriate error`() {
            val nameType = XMLPattern("<name><nameid $isOptional>(number)</nameid><fullname>(string)</fullname></name>")
            val name = toXMLNode("<name><nameid>hello</nameid><fullname>Jane Doe</fullname></name>")

            val result = nameType.matches(name, Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)

            assertThat(result.reportString()).contains("nameid")
        }

        @Test
        fun `xml with optional nodes generates 2 samples`() {
            val nameType = XMLPattern("<name><nameid $isOptional>(number)</nameid></name>")
            val newTypes = nameType.newBasedOn(Row(), Resolver()).map { it.value as XMLPattern }.toList()

            assertThat(newTypes).hasSize(2)

            val newValues = newTypes.map {
                it.generate(Resolver())
            }

            assertThat(newValues).anySatisfy(Consumer {
                assertThat(it.name).isEqualTo("name")

                val first = it.childNodes.first() as XMLNode

                assertThat(first.name).isEqualTo("nameid")
                assertThat(first.attributes).doesNotContainKey(OCCURS_ATTRIBUTE_NAME)
            })

            assertThat(newValues).anySatisfy(Consumer {
                assertThat(it.name).isEqualTo("name")
                assertThat(it.childNodes).isEmpty()
            })
        }

        @Test
        fun `xml with optional node generates one sample with the node and one without`() {
            val nameType = XMLPattern("<name><nameid $isOptional>(number)</nameid></name>")
            val newValues =
                nameType.newBasedOn(Row(), Resolver()).map { it.value as XMLPattern }.map { it.generate(Resolver()) }
                    .toList()

            assertThat(newValues).anySatisfy(Consumer {
                assertThat(it.name).isEqualTo("name")

                val first = it.childNodes.first() as XMLNode

                assertThat(first.name).isEqualTo("nameid")
                assertThat(first.attributes).doesNotContainKey(OCCURS_ATTRIBUTE_NAME)
            })

            assertThat(newValues).anySatisfy(Consumer {
                assertThat(it.name).isEqualTo("name")
                assertThat(it.childNodes).isEmpty()
            })
        }

        @Test
        fun `xml with an optional typed node generates one sample with the node and one without`() {
            val nameType = XMLPattern("<name><nameid $isOptional $TYPE_ATTRIBUTE_NAME=\"Nameid\" /></name>")
            val nameIdType = XMLPattern("<nameid>(number)</nameid>")
            val resolver = Resolver(newPatterns = mapOf("(Nameid)" to nameIdType))

            val newValues =
                nameType.newBasedOn(Row(), resolver).map { it.value as XMLPattern }.map { it.generate(resolver) }
                    .toList()

            assertThat(newValues).anySatisfy(Consumer {
                assertThat(it.name).isEqualTo("name")

                val first = it.childNodes.first() as XMLNode

                assertThat(first.name).isEqualTo("nameid")
                assertThat(first.attributes).doesNotContainKey(OCCURS_ATTRIBUTE_NAME)
            })

            assertThat(newValues).anySatisfy(Consumer {
                assertThat(it.name).isEqualTo("name")
                assertThat(it.childNodes).isEmpty()
            })
        }

        @Test
        fun `xml with a typed optional node loads data from examples`() {
            val nameType = XMLPattern("<name><nameid $isOptional $TYPE_ATTRIBUTE_NAME=\"Nameid\" /></name>")
            val nameIdType = XMLPattern("<nameid>(number)</nameid>")
            val resolver = Resolver(newPatterns = mapOf("(Nameid)" to nameIdType))
            val row = Row(listOf("nameid"), listOf("10"))
            val newValues =
                nameType.newBasedOn(row, resolver).map {
                    it.value as XMLPattern
                }.map {
                    it.generate(resolver)
                }.toList()

            assertThat(newValues.isNotEmpty())

            val name = newValues.first()
            val nameId = name.childNodes.first() as XMLNode
            assertThat(nameId.childNodes.first().toStringLiteral()).isEqualTo("10")
        }

        @Test
        fun `optional type returns an error when matching a value of a different type`() {
            val type = XMLPattern("<account><name $isOptional>(string)</name></account>")
            val value = toXMLNode("<account>test</account>")

            assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `match should fail a node does not match and all the nodes are optional`() {
            val accountType = XMLPattern("<account><id>(number)</id><name type=\"Name\" $isOptional/><address $TYPE_ATTRIBUTE_NAME=\"Address\" $isOptional/></account>")
            val nameType = XMLPattern("<$TYPE_ATTRIBUTE_NAME><fullname>(string)</fullname><salutation>(string)</salutation></$TYPE_ATTRIBUTE_NAME>")
            val addressType = XMLPattern("<$TYPE_ATTRIBUTE_NAME>(string)</$TYPE_ATTRIBUTE_NAME>")
            val resolver = Resolver(newPatterns = mapOf("(Name)" to nameType, "(Address)" to addressType))

            val accountValue = toXMLNode("<account><id>10</id><name><firstname>Jane</firstname></name><address>Baker street</address></account>")
            val result = accountType.matches(accountValue, resolver)

            assertThat(result).isInstanceOf(Result.Failure::class.java)
        }
    }

    @Nested
    inner class MultiNodeMatch {
        @Test
        fun `xml with a node that occurs multiple times generates a single sample`() {
            val nameType = XMLPattern("<name><title $occursMultipleTimes>(number)</title></name>")
            val newTypes = nameType.newBasedOn(Row(), Resolver()).map { it.value as XMLPattern }.toList()

            assertThat(newTypes).hasSize(1)
        }

        @Test
        fun `direct generation of an xml node that occurs multiple times generates one occurrence`() {
            val nameType = XMLPattern("<name><title $occursMultipleTimes>(number)</title></name>")

            val generated = nameType.generate(Resolver())
            val generatedChildren = generated.childNodes.filterIsInstance<XMLNode>()

            assertThat(generatedChildren).hasSize(1)
            assertThat(generatedChildren.map { it.name }).containsOnly("title")
        }

        @Test
        fun `xml with a node that occurs multiple times generates multiple nodes`() {
            val nameType = XMLPattern("<name><title $occursMultipleTimes>(number)</title></name>")
            val newValues =
                nameType.newBasedOn(Row(), Resolver()).map { it.value as XMLPattern }.map { it.generate(Resolver()) }
                    .toList()

            assertThat(newValues.isNotEmpty())

            assertThat(newValues).allSatisfy(Consumer {
                assertThat(it.name).isEqualTo("name")

                val first = it.childNodes.first() as XMLNode

                assertThat(first.name).isEqualTo("title")
                assertThat(first.attributes).doesNotContainKey(OCCURS_ATTRIBUTE_NAME)
            })
        }

        @Test
        fun `xml with a typed node that occurs multiple times generates multiple nodes`() {
            val nameType = XMLPattern("<name><nameid $occursMultipleTimes $TYPE_ATTRIBUTE_NAME=\"Nameid\" /></name>")
            val nameIdType = XMLPattern("<nameid>(number)</nameid>")
            val resolver = Resolver(newPatterns = mapOf("(Nameid)" to nameIdType))
            val newValues =
                nameType.newBasedOn(Row(), resolver).map { it.value as XMLPattern }.map { it.generate(resolver) }
                    .toList()

            assertThat(newValues.isNotEmpty())

            assertThat(newValues).allSatisfy(Consumer {
                assertThat(it.name).isEqualTo("name")

                val first = it.childNodes.first() as XMLNode

                assertThat(first.name).isEqualTo("nameid")
                assertThat(first.attributes).doesNotContainKey(OCCURS_ATTRIBUTE_NAME)
            })
        }

        @Test
        fun `xml with a typed node that occurs multiple times loads data from examples`() {
            val nameType = XMLPattern("<name><nameid $occursMultipleTimes $TYPE_ATTRIBUTE_NAME=\"Nameid\" /></name>")
            val nameIdType = XMLPattern("<nameid>(number)</nameid>")
            val resolver = Resolver(newPatterns = mapOf("(Nameid)" to nameIdType))
            val row = Row(listOf("nameid"), listOf("10"))
            val newValues =
                nameType.newBasedOn(row, resolver).map { it.value as XMLPattern }.map { it.generate(resolver) }
                    .toList()

            assertThat(newValues.isNotEmpty())

            val name = newValues.first()
            val nameId = name.childNodes.first() as XMLNode
            assertThat(nameId.childNodes.first().toStringLiteral()).isEqualTo("10")
        }

        @Test
        fun `multiple nodes at the end can be matched`() {
            val type = XMLPattern("<account><name>(string)</name><address $occursMultipleTimes>(string)</address></account>")
            val value = toXMLNode("<account><name>John Doe</name><address>Baker Street</address><address>Downing Street</address></account>")

            assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `multiple typed nodes at the end can be matched`() {
            val type = XMLPattern("<account><name>(string)</name><address $TYPE_ATTRIBUTE_NAME=\"Address\" $occursMultipleTimes/></account>")
            val addressType = XMLPattern("<$TYPE_ATTRIBUTE_NAME>(string)</$TYPE_ATTRIBUTE_NAME>")
            val resolver = Resolver(newPatterns = mapOf("(Address)" to addressType))
            val value = toXMLNode("<account><name>John Doe</name><address>Baker Street</address><address>Downing Street</address></account>")

            assertThat(type.matches(value, resolver)).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `multiple nodes in the middle can be matched`() {
            val type = XMLPattern("<account><name>(string)</name><address $occursMultipleTimes>(string)</address><phone>(number)</phone></account>")
            val value = toXMLNode("<account><name>John Doe</name><address>Baker Street</address><address>Downing Street</address><phone>10</phone></account>")

            assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `multiple nodes at the start can be matched`() {
            val type = XMLPattern("<account><address $occursMultipleTimes>(string)</address><phone>(number)</phone><name>(string)</name></account>")
            val value = toXMLNode("<account><address>Baker Street</address><address>Downing Street</address><phone>10</phone><name>John Doe</name></account>")

            assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `multi-node declaration can match 0 occurence of those nodes`() {
            val type = XMLPattern("<account><name>(string)</name><address $occursMultipleTimes>(string)</address><phone>(number)</phone></account>")
            val value = toXMLNode("<account><name>John Doe</name><phone>10</phone></account>")

            assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `multi-node declaration can be followed by optional declaration in which none of the nodes declared are found in the matched value`() {
            val type = XMLPattern("<account><name>(string)</name><address $occursMultipleTimes>(string)</address><phone $isOptional>(number)</phone></account>")
            val value = toXMLNode("<account><name>John Doe</name></account>")

            assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `a multiple type of the same name should fail if the node does not match with an appropriate error`() {
            val nameType = XMLPattern("<name><nameid $occursMultipleTimes>(number)</nameid><fullname>(string)</fullname></name>")
            val name = toXMLNode("<name><nameid>hello</nameid><fullname>Jane Doe</fullname></name>")

            val result = nameType.matches(name, Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)

            assertThat(result.reportString()).contains("nameid")
        }

        @Test
        fun `match should fail a node does not match and all the nodes occur multiple times`() {
            val accountType = XMLPattern("<account><id>(number)</id><name type=\"Name\" $occursMultipleTimes/><address $TYPE_ATTRIBUTE_NAME=\"Address\" $occursMultipleTimes/></account>")
            val nameType = XMLPattern("<$TYPE_ATTRIBUTE_NAME><fullname>(string)</fullname><salutation>(string)</salutation></$TYPE_ATTRIBUTE_NAME>")
            val addressType = XMLPattern("<$TYPE_ATTRIBUTE_NAME>(string)</$TYPE_ATTRIBUTE_NAME>")
            val resolver = Resolver(newPatterns = mapOf("(Name)" to nameType, "(Address)" to addressType))

            val accountValue = toXMLNode("<account><id>10</id><name><firstname>Jane</firstname></name><address>Baker street</address></account>")
            val result = accountType.matches(accountValue, resolver)

            assertThat(result).isInstanceOf(Result.Failure::class.java)
        }
    }

    @Test
    fun `unbound namespace should be parsed`() {
        val xml = """<ns1:name>(string)</ns1:name>"""
        val type = parsedPattern(xml)

        assertThat(type.matches(parsedValue(xml), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `generate Gherkin statements`() {
        val xml = XMLPattern("<account><id>(number)</id></account>")
        val gherkinStatement = xml.toGherkinStatement("TypeName")

        assertThat(gherkinStatement.trimmedLinesString()).isEqualTo(
                """And type TypeName
""${'"'}
<account>
  <id>(number)</id>
</account>
""${'"'}""".trimmedLinesString()
        )
    }

    @Test
    fun `will load a stub value with unexpected xmlns value defined`() {
        val type = XMLPattern("<account><id>(number)</id></account>")
        val value = toXMLNode("""<account xmlns:ns0="https://hello-world.com"><id>10</id></account>""")

        val matchResult = type.matches(value, Resolver())

        println(matchResult.reportString())

        assertThat(matchResult).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `a nillable node should match a node with no child nodes`() {
        val type = XMLPattern("<account specmatic_nillable=\"true\"><id>(number)</id></account>")
        val value = toXMLNode("""<account />""")

        val matchResult = type.matches(value, Resolver())

        println(matchResult.reportString())

        assertThat(matchResult).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `soap header pattern is marked only for the direct envelope header`() {
        val type = XMLPattern(
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:h="http://headers" xmlns:tns="http://body">
                <soapenv:Header>
                    <h:TraceId>(string)</h:TraceId>
                </soapenv:Header>
                <soapenv:Body>
                    <tns:Header>
                        <tns:Name>(string)</tns:Name>
                    </tns:Header>
                </soapenv:Body>
            </soapenv:Envelope>
            """.trimIndent(),
            isSOAP = true
        )

        val envelopeChildren = type.pattern.nodes.filterIsInstance<XMLPattern>()
        val soapHeader = envelopeChildren.single { it.pattern.name == "Header" }
        val body = envelopeChildren.single { it.pattern.name == "Body" }
        val payloadHeader = body.pattern.nodes.single() as XMLPattern

        assertThat(soapHeader.pattern.isSOAPHeader).isTrue()
        assertThat(payloadHeader.pattern.isSOAPHeader).isFalse()
    }

    @Test
    fun `soap header children match in pattern order even when sample order is different`() {
        val type = soapEnvelopePatternWithHeader()
        val value = toXMLNode(
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:h="http://headers" xmlns:tns="http://body">
                <soapenv:Header>
                    <h:ClientId>client-123</h:ClientId>
                    <h:TraceId>trace-123</h:TraceId>
                </soapenv:Header>
                <soapenv:Body>
                    <tns:Request>hello</tns:Request>
                </soapenv:Body>
            </soapenv:Envelope>
            """.trimIndent()
        )

        assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `soap header children with same local name match in namespace order when sample order is different`() {
        val type = XMLPattern(
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:a="http://headers/a" xmlns:b="http://headers/b" xmlns:tns="http://body">
                <soapenv:Header>
                    <a:Token>alpha</a:Token>
                    <b:Token>beta</b:Token>
                    <a:TraceId>(string)</a:TraceId>
                </soapenv:Header>
                <soapenv:Body>
                    <tns:Request>hello</tns:Request>
                </soapenv:Body>
            </soapenv:Envelope>
            """.trimIndent(),
            isSOAP = true
        )
        val value = toXMLNode(
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:a="http://headers/a" xmlns:b="http://headers/b" xmlns:tns="http://body">
                <soapenv:Header>
                    <a:TraceId>trace-123</a:TraceId>
                    <b:Token>beta</b:Token>
                    <a:Token>alpha</a:Token>
                </soapenv:Header>
                <soapenv:Body>
                    <tns:Request>hello</tns:Request>
                </soapenv:Body>
            </soapenv:Envelope>
            """.trimIndent()
        )

        assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `soap header children match in pattern order for soap 1_2 envelope namespace`() {
        val type = soapEnvelopePatternWithHeader()
        val value = toXMLNode(
            """
            <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope" xmlns:h="http://headers" xmlns:tns="http://body">
                <env:Header>
                    <h:ClientId>client-123</h:ClientId>
                    <h:TraceId>trace-123</h:TraceId>
                </env:Header>
                <env:Body>
                    <tns:Request>hello</tns:Request>
                </env:Body>
            </env:Envelope>
            """.trimIndent()
        )

        assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `unexpected soap header child still fails after header order normalization`() {
        val type = soapEnvelopePatternWithHeader()
        val value = toXMLNode(
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:h="http://headers" xmlns:tns="http://body">
                <soapenv:Header>
                    <h:ClientId>client-123</h:ClientId>
                    <h:Unexpected>unexpected</h:Unexpected>
                    <h:TraceId>trace-123</h:TraceId>
                </soapenv:Header>
                <soapenv:Body>
                    <tns:Request>hello</tns:Request>
                </soapenv:Body>
            </soapenv:Envelope>
            """.trimIndent()
        )

        assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `duplicate soap header names preserve relative sample order during normalization`() {
        val type = XMLPattern(
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:h="http://headers" xmlns:tns="http://body">
                <soapenv:Header>
                    <h:Token>one</h:Token>
                    <h:Token>two</h:Token>
                    <h:TraceId>(string)</h:TraceId>
                </soapenv:Header>
                <soapenv:Body>
                    <tns:Request>hello</tns:Request>
                </soapenv:Body>
            </soapenv:Envelope>
            """.trimIndent(),
            isSOAP = true
        )
        val value = toXMLNode(
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:h="http://headers" xmlns:tns="http://body">
                <soapenv:Header>
                    <h:TraceId>trace-123</h:TraceId>
                    <h:Token>two</h:Token>
                    <h:Token>one</h:Token>
                </soapenv:Header>
                <soapenv:Body>
                    <tns:Request>hello</tns:Request>
                </soapenv:Body>
            </soapenv:Envelope>
            """.trimIndent()
        )

        assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `non soap header named node remains order sensitive`() {
        val type = XMLPattern(
            """
            <Envelope>
                <Header>
                    <TraceId>(string)</TraceId>
                    <ClientId>(string)</ClientId>
                </Header>
            </Envelope>
            """.trimIndent()
        )
        val value = toXMLNode(
            """
            <Envelope>
                <Header>
                    <ClientId>client-123</ClientId>
                    <TraceId>trace-123</TraceId>
                </Header>
            </Envelope>
            """.trimIndent()
        )

        assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `soap body payload children remain order sensitive`() {
        val type = XMLPattern(
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tns="http://body">
                <soapenv:Body>
                    <tns:Request>
                        <tns:AccountId>(string)</tns:AccountId>
                        <tns:CustomerId>(string)</tns:CustomerId>
                    </tns:Request>
                </soapenv:Body>
            </soapenv:Envelope>
            """.trimIndent(),
            isSOAP = true
        )
        val value = toXMLNode(
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tns="http://body">
                <soapenv:Body>
                    <tns:Request>
                        <tns:CustomerId>customer-123</tns:CustomerId>
                        <tns:AccountId>account-123</tns:AccountId>
                    </tns:Request>
                </soapenv:Body>
            </soapenv:Envelope>
            """.trimIndent()
        )

        assertThat(type.matches(value, Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    private fun soapEnvelopePatternWithHeader(): XMLPattern =
        XMLPattern(
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:h="http://headers" xmlns:tns="http://body">
                <soapenv:Header>
                    <h:TraceId>(string)</h:TraceId>
                    <h:ClientId>(string)</h:ClientId>
                </soapenv:Header>
                <soapenv:Body>
                    <tns:Request>(string)</tns:Request>
                </soapenv:Body>
            </soapenv:Envelope>
            """.trimIndent(),
            isSOAP = true
        )
}

private class FixedXMLGenerationDecisions(private vararg val decisions: Boolean) : XMLGenerationDecisions {
    private var index = 0

    override fun includeOptionalXMLNode(): Boolean {
        val decision = decisions.getOrElse(index) { decisions.last() }
        index += 1
        return decision
    }
}
