package io.specmatic.core

import io.specmatic.core.pattern.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class CalculatePathTest {

    @Test
    fun `calculatePath should find AnyPattern in simple object with typeAlias`() {
        // Create a pattern with typeAlias and an AnyPattern field
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "id" to StringPattern(),
                "data" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
            ),
            typeAlias = "(User)"
        )

        // Create a value that matches this pattern
        val value = JSONObjectValue(mapOf(
            "id" to StringValue("123"),
            "data" to StringValue("some data")
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).containsExactly("{User}.data{string}")
    }

    @Test
    fun `calculatePath should find AnyPattern in object without typeAlias`() {
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "id" to StringPattern(),
                "value" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
            )
        )

        val value = JSONObjectValue(mapOf(
            "id" to StringValue("123"),
            "value" to NumberValue(42)
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).containsExactly("value{number}")
    }

    @Test
    fun `calculatePath should find AnyPattern at top level`() {
        // For this test, create a scenario and test through Scenario.calculatePath
        val httpRequestPattern = HttpRequestPattern(
            body = AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
        )
        val httpResponsePattern = HttpResponsePattern(
            headersPattern = HttpHeadersPattern(),
            status = 200,
            body = StringPattern()
        )
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = httpRequestPattern,
            httpResponsePattern = httpResponsePattern
        )

        val httpRequest = HttpRequest(
            method = "POST",
            path = "/test",
            body = StringValue("some data")
        )

        val paths = scenario.calculatePath(httpRequest)
        
        // Since we have an AnyPattern at the top level that matches a scalar type,
        // it should return the scalar type name in braces
        assertThat(paths).containsExactly("{string}")
    }

    @Test
    fun `calculatePath should handle array at top level`() {
        // Create a scenario with an array at the top level containing AnyPattern elements
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = HttpRequestPattern(
                body = ListPattern(
                    pattern = AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
                )
            ),
            httpResponsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(),
                status = 200,
                body = StringPattern()
            )
        )

        val httpRequest = HttpRequest(
            method = "POST",
            path = "/test",
            body = JSONArrayValue(listOf(
                StringValue("first item"),
                NumberValue(42),
                StringValue("third item")
            ))
        )

        val paths = scenario.calculatePath(httpRequest)
        
        // For array at top level, paths should be in the format "[0]{string}", "[1]{number}", etc.
        assertThat(paths).containsExactlyInAnyOrder("{[0]}{string}", "{[1]}{number}", "{[2]}{string}")
    }

    @Test
    fun `calculatePath should handle empty object`() {
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "id" to StringPattern()
            )
        )

        val value = JSONObjectValue(mapOf(
            "id" to StringValue("123")
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).isEmpty()
    }

    @Test
    fun `calculatePath should handle non-JSON object value`() {
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "data" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
            )
        )

        val paths = pattern.calculatePath(StringValue("not an object"), Resolver())
        
        assertThat(paths).isEmpty()
    }

    @Test
    fun `calculatePath should find nested AnyPatterns`() {
        val nestedPattern = JSONObjectPattern(
            pattern = mapOf(
                "nestedData" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
            ),
            typeAlias = "(NestedObject)"
        )
        
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "id" to StringPattern(),
                "nested" to nestedPattern
            ),
            typeAlias = "(MainObject)"
        )

        val value = JSONObjectValue(mapOf(
            "id" to StringValue("123"),
            "nested" to JSONObjectValue(mapOf(
                "nestedData" to StringValue("some data")
            ))
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).containsExactly("{MainObject}.nested{NestedObject}.nestedData{string}")
    }

    @Test
    fun `calculatePath should handle multiple AnyPatterns in same object`() {
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "data1" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap()),
                "data2" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap()),
                "regularField" to StringPattern()
            ),
            typeAlias = "(MultiAnyObject)"
        )

        val value = JSONObjectValue(mapOf(
            "data1" to StringValue("first"),
            "data2" to NumberValue(42),
            "regularField" to StringValue("regular")
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).containsExactlyInAnyOrder("{MultiAnyObject}.data1{string}", "{MultiAnyObject}.data2{number}")
    }

    @Test
    fun `Feature calculatePath should find paths from first matching scenario`() {
        val requestPattern1 = HttpRequestPattern(
            method = "POST",
            httpPathPattern = buildHttpPathPattern("/test"),
            body = JSONObjectPattern(
                pattern = mapOf(
                    "field1" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
                ),
                typeAlias = "(Request1)"
            )
        )
        val responsePattern1 = HttpResponsePattern(
            headersPattern = HttpHeadersPattern(),
            status = 200,
            body = StringPattern()
        )
        
        val scenario1 = Scenario(
            name = "scenario1",
            httpRequestPattern = requestPattern1,
            httpResponsePattern = responsePattern1
        )

        val requestPattern2 = HttpRequestPattern(
            method = "POST",
            httpPathPattern = buildHttpPathPattern("/test"),
            body = JSONObjectPattern(
                pattern = mapOf(
                    "field2" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
                ),
                typeAlias = "(Request2)"
            )
        )
        val responsePattern2 = HttpResponsePattern(
            headersPattern = HttpHeadersPattern(),
            status = 200,
            body = StringPattern()
        )
        
        val scenario2 = Scenario(
            name = "scenario2",
            httpRequestPattern = requestPattern2,
            httpResponsePattern = responsePattern2
        )

        val feature = Feature(
            scenarios = listOf(scenario1, scenario2),
            name = "TestFeature"
        )

        val httpRequest = HttpRequest(
            method = "POST",
            path = "/test",
            body = JSONObjectValue(mapOf(
                "field1" to StringValue("value1")
            ))
        )

        val paths = feature.calculatePath(httpRequest, 200)
        
        assertThat(paths).containsExactly("{Request1}.field1{string}")
    }

    @Test
    fun `calculatePath should find AnyPatterns in arrays`() {
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "items" to ListPattern(
                    pattern = AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
                )
            ),
            typeAlias = "(ArrayContainer)"
        )

        val value = JSONObjectValue(mapOf(
            "items" to JSONArrayValue(listOf(
                StringValue("item1"),
                NumberValue(42),
                StringValue("item3")
            ))
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).containsExactlyInAnyOrder(
            "{ArrayContainer}.items[0]{string}",
            "{ArrayContainer}.items[1]{number}", 
            "{ArrayContainer}.items[2]{string}"
        )
    }

    @Test
    fun `calculatePath should find nested AnyPatterns in array objects`() {
        val arrayItemPattern = JSONObjectPattern(
            pattern = mapOf(
                "data" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
            ),
            typeAlias = "(ArrayItem)"
        )
        
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "items" to ListPattern(
                    pattern = arrayItemPattern
                )
            ),
            typeAlias = "(ArrayContainer)"
        )

        val value = JSONObjectValue(mapOf(
            "items" to JSONArrayValue(listOf(
                JSONObjectValue(mapOf("data" to StringValue("first"))),
                JSONObjectValue(mapOf("data" to NumberValue(42)))
            ))
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).containsExactlyInAnyOrder(
            "{ArrayContainer}.items[0]{ArrayItem}.data{string}",
            "{ArrayContainer}.items[1]{ArrayItem}.data{number}"
        )
    }

    @Test
    fun `calculatePath should handle ListPattern with AnyPattern`() {
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "items" to ListPattern(
                    pattern = AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
                )
            ),
            typeAlias = "(ListContainer)"
        )

        val value = JSONObjectValue(mapOf(
            "items" to JSONArrayValue(listOf(
                StringValue("item1"),
                NumberValue(42)
            ))
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).containsExactlyInAnyOrder(
            "{ListContainer}.items[0]{string}",
            "{ListContainer}.items[1]{number}"
        )
    }

    @Test
    fun `calculatePath should handle empty arrays`() {
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "items" to ListPattern(
                    pattern = AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
                )
            ),
            typeAlias = "(EmptyArrayContainer)"
        )

        val value = JSONObjectValue(mapOf(
            "items" to JSONArrayValue(emptyList())
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).isEmpty()
    }

    @Test
    fun `calculatePath should handle missing optional keys`() {
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "requiredField" to StringPattern(),
                "optionalField?" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
            ),
            typeAlias = "(OptionalFieldObject)"
        )

        val value = JSONObjectValue(mapOf(
            "requiredField" to StringValue("value")
            // optionalField is missing
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).isEmpty()
    }
    
    @Test
    fun `calculatePath should handle AnyPattern at top level with typeAlias`() {
        val patterns = mapOf(
            "(Address)" to JSONObjectPattern(
                pattern = mapOf("street" to StringPattern(), "locality" to StringPattern())
            ),
            "(AddressRef)" to JSONObjectPattern(
                pattern = mapOf("address_id" to NumberPattern())
            )
        )
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = HttpRequestPattern(
                body = AnyPattern(
                    pattern = listOf(
                        DeferredPattern(pattern = "(Address)"),
                        DeferredPattern(pattern = "(AddressRef)")
                    ),
                    typeAlias = "(AddressOrRef)",
                    extensions = emptyMap()
                )
            ),
            httpResponsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(),
                status = 200,
                body = StringPattern()
            ),
            patterns = patterns
        )

        val httpRequest = HttpRequest(
            method = "POST",
            path = "/test",
            body = JSONObjectValue(mapOf("address_id" to NumberValue(123)))
        )

        val paths = scenario.calculatePath(httpRequest)
        
        assertThat(paths).containsExactly("{AddressRef}")
    }
    
    @Test
    fun `calculatePath should handle AnyPattern at top level without typeAlias`() {
        val patterns = mapOf(
            "(Address)" to JSONObjectPattern(
                pattern = mapOf("street" to StringPattern(), "locality" to StringPattern())
            ),
            "(AddressRef)" to JSONObjectPattern(
                pattern = mapOf("address_id" to NumberPattern())
            )
        )
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = HttpRequestPattern(
                body = AnyPattern(
                    pattern = listOf(
                        DeferredPattern(pattern = "(Address)"),
                        DeferredPattern(pattern = "(AddressRef)")
                    ),
                    extensions = emptyMap()
                    // No typeAlias for the AnyPattern itself
                )
            ),
            httpResponsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(),
                status = 200,
                body = StringPattern()
            ),
            patterns = patterns
        )

        val httpRequest = HttpRequest(
            method = "POST",
            path = "/test",
            body = JSONObjectValue(mapOf("address_id" to NumberValue(123)))
        )

        val paths = scenario.calculatePath(httpRequest)
        
        assertThat(paths).containsExactly("{AddressRef}")
    }
    
    @Test
    fun `calculatePath should handle AnyPattern where one option has no typeAlias`() {
        val patterns = mapOf(
            "(Address)" to JSONObjectPattern(
                pattern = mapOf("street" to StringPattern(), "locality" to StringPattern())
            )
        )
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = HttpRequestPattern(
                body = AnyPattern(
                    pattern = listOf(
                        DeferredPattern(pattern = "(Address)"),
                        JSONObjectPattern(pattern = mapOf("address_id" to NumberPattern()))
                    ),
                    extensions = emptyMap()
                )
            ),
            httpResponsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(),
                status = 200,
                body = StringPattern()
            ),
            patterns = patterns
        )

        val httpRequest = HttpRequest(
            method = "POST",
            path = "/test",
            body = JSONObjectValue(mapOf("address_id" to NumberValue(123)))
        )

        val paths = scenario.calculatePath(httpRequest)
        
        assertThat(paths).containsExactly("{[1]}")
    }
    
    @Test
    fun `calculatePath should handle array of AnyPattern objects`() {
        val patterns = mapOf(
            "(Address)" to JSONObjectPattern(
                pattern = mapOf("street" to StringPattern(), "locality" to StringPattern())
            ),
            "(AddressRef)" to JSONObjectPattern(
                pattern = mapOf("address_id" to NumberPattern())
            )
        )
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = HttpRequestPattern(
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "name" to StringPattern(),
                        "addresses" to ListPattern(
                            pattern = AnyPattern(
                                pattern = listOf(
                                    DeferredPattern(pattern = "(Address)"),
                                    DeferredPattern(pattern = "(AddressRef)")
                                ),
                                extensions = emptyMap()
                            )
                        )
                    ),
                    typeAlias = "(Person)"
                )
            ),
            httpResponsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(),
                status = 200,
                body = StringPattern()
            ),
            patterns = patterns
        )

        val httpRequest = HttpRequest(
            method = "POST",
            path = "/test",
            body = JSONObjectValue(mapOf(
                "name" to StringValue("Jack"),
                "addresses" to JSONArrayValue(listOf(
                    JSONObjectValue(mapOf("address_id" to NumberValue(123))),
                    JSONObjectValue(mapOf("street" to StringValue("Baker Street"), "locality" to StringValue("London")))
                ))
            ))
        )

        val paths = scenario.calculatePath(httpRequest)
        
        assertThat(paths).containsExactlyInAnyOrder("{Person}.addresses[0]{AddressRef}", "{Person}.addresses[1]{Address}")
    }
    
    @Test
    fun `calculatePath should handle top-level array of AnyPatterns`() {
        val patterns = mapOf(
            "(Address)" to JSONObjectPattern(
                pattern = mapOf("street" to StringPattern(), "locality" to StringPattern())
            ),
            "(AddressRef)" to JSONObjectPattern(
                pattern = mapOf("address_id" to NumberPattern())
            )
        )
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = HttpRequestPattern(
                body = ListPattern(
                    pattern = AnyPattern(
                        pattern = listOf(
                            DeferredPattern(pattern = "(Address)"),
                            DeferredPattern(pattern = "(AddressRef)")
                        ),
                        extensions = emptyMap()
                    ),
                    typeAlias = "(AddressList)"
                )
            ),
            httpResponsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(),
                status = 200,
                body = StringPattern()
            ),
            patterns = patterns
        )

        val httpRequest = HttpRequest(
            method = "POST",
            path = "/test",
            body = JSONArrayValue(listOf(
                JSONObjectValue(mapOf("address_id" to NumberValue(123))),
                JSONObjectValue(mapOf("street" to StringValue("Baker Street"), "locality" to StringValue("London")))
            ))
        )

        val paths = scenario.calculatePath(httpRequest)
        
        assertThat(paths).containsExactlyInAnyOrder("{[0]}{AddressRef}", "{[1]}{Address}")
    }
    
    @Test
    fun `calculatePath should handle array of AnyPattern objects in ListPattern`() {
        // Pattern: JSONObjectPattern with ListPattern containing AnyPattern
        
        val patterns = mapOf(
            "(Address)" to JSONObjectPattern(
                pattern = mapOf(
                    "street" to StringPattern(),
                    "locality" to StringPattern()
                )
            ),
            "(AddressRef)" to JSONObjectPattern(
                pattern = mapOf(
                    "address_id" to NumberPattern()
                )
            )
        )
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = HttpRequestPattern(
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "name" to StringPattern(),
                        "addresses" to ListPattern(
                            pattern = AnyPattern(
                                pattern = listOf(
                                    DeferredPattern("(Address)"),
                                    DeferredPattern("(AddressRef)")
                                ),
                                extensions = emptyMap()
                            )
                        )
                    ),
                    typeAlias = "(Person)"
                )
            ),
            httpResponsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(),
                status = 200,
                body = StringPattern()
            ),
            patterns = patterns
        )
        
        val httpRequest = HttpRequest(
            method = "POST",
            path = "/test",
            body = JSONObjectValue(mapOf(
                "name" to StringValue("Jack"),
                "addresses" to JSONArrayValue(listOf(
                    JSONObjectValue(mapOf("address_id" to NumberValue(123))),
                    JSONObjectValue(mapOf("street" to StringValue("Baker Street"), "locality" to StringValue("London")))
                ))
            ))
        )
        
        val paths = scenario.calculatePath(httpRequest)
        
        assertThat(paths).containsExactlyInAnyOrder("{Person}.addresses[0]{AddressRef}", "{Person}.addresses[1]{Address}")
    }
    
    @Test
    fun `calculatePath should handle multiple paths from different AnyPattern fields`() {
        // Pattern: JSONObjectPattern with multiple fields that resolve to AnyPatterns
        
        val patterns = mapOf(
            "(Address)" to JSONObjectPattern(
                pattern = mapOf(
                    "street" to StringPattern(),
                    "locality" to StringPattern()
                )
            ),
            "(AddressRef)" to JSONObjectPattern(
                pattern = mapOf(
                    "address_id" to NumberPattern()
                )
            ),
            "(AddressOrRef)" to AnyPattern(
                pattern = listOf(
                    DeferredPattern("(Address)"),
                    DeferredPattern("(AddressRef)")
                ),
                extensions = emptyMap()
            )
        )
        
        val scenario = Scenario(
            name = "test",
            httpRequestPattern = HttpRequestPattern(
                body = JSONObjectPattern(
                    pattern = mapOf(
                        "name" to StringPattern(),
                        "officeAddress" to DeferredPattern("(AddressOrRef)"),
                        "homeAddress" to DeferredPattern("(AddressOrRef)")
                    ),
                    typeAlias = "(Person)"
                )
            ),
            httpResponsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(),
                status = 200,
                body = StringPattern()
            ),
            patterns = patterns
        )
        
        val httpRequest = HttpRequest(
            method = "POST",
            path = "/test",
            body = JSONObjectValue(mapOf(
                "name" to StringValue("Jack"),
                "officeAddress" to JSONObjectValue(mapOf("address_id" to NumberValue(123))),
                "homeAddress" to JSONObjectValue(mapOf("street" to StringValue("Baker Street"), "locality" to StringValue("London")))
            ))
        )
        
        val paths = scenario.calculatePath(httpRequest)
        
        assertThat(paths).containsExactlyInAnyOrder("{Person}.officeAddress{AddressRef}", "{Person}.homeAddress{Address}")
    }
    
    @Test
    fun `calculatePath should handle deeply nested AnyPattern with typeAlias`() {
        // Test case 1: Nested structure with typeAlias at multiple levels
        val level3Pattern = JSONObjectPattern(
            pattern = mapOf(
                "data" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
            ),
            typeAlias = "(Level3Object)"
        )
        
        val level2Pattern = JSONObjectPattern(
            pattern = mapOf(
                "level3" to level3Pattern
            ),
            typeAlias = "(Level2Object)"
        )
        
        val level1Pattern = JSONObjectPattern(
            pattern = mapOf(
                "level2" to level2Pattern
            ),
            typeAlias = "(Level1Object)"
        )

        val value = JSONObjectValue(mapOf(
            "level2" to JSONObjectValue(mapOf(
                "level3" to JSONObjectValue(mapOf(
                    "data" to StringValue("test")
                ))
            ))
        ))

        val paths = level1Pattern.calculatePath(value, Resolver())
        
        assertThat(paths).containsExactly("{Level1Object}.level2{Level2Object}.level3{Level3Object}.data{string}")
    }

    @Test
    fun `calculatePath should return only the keys that at some level contain an AnyPattern`() {
        // Test case 1: Nested structure with typeAlias at multiple levels
        val level3Pattern = JSONObjectPattern(
            pattern = mapOf(
                "keyAppears" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap()),
                "keyDoesNotAppear" to StringPattern()
            ),
            typeAlias = "(Level3Object)"
        )

        val level2Pattern = JSONObjectPattern(
            pattern = mapOf(
                "keyAppears" to level3Pattern,
                "keyDoesNotAppear?" to JSONObjectPattern(
                    pattern = mapOf("key" to StringPattern())
                )
            ),
            typeAlias = "(Level2Object)"
        )

        val level1Pattern = JSONObjectPattern(
            pattern = mapOf(
                "keyAppears" to level2Pattern,
                "keyDoesNotAppear?" to StringPattern()
            ),
            typeAlias = "(Level1Object)"
        )

        val value = JSONObjectValue(mapOf(
            "keyDoesNotAppear" to StringValue("not relevant"),
            "keyAppears" to JSONObjectValue(mapOf(
                "keyDoesNotAppear" to StringValue("not relevant"),
                "keyAppears" to JSONObjectValue(mapOf(
                    "keyAppears" to StringValue("test")
                ))
            ))
        ))

        val paths = level1Pattern.calculatePath(value, Resolver())

        assertThat(paths).containsExactly("{Level1Object}.keyAppears{Level2Object}.keyAppears{Level3Object}.keyAppears{string}")
    }
    
    @Test
    fun `calculatePath should handle nested AnyPattern containing JSONObjectPattern`() {
        // Test case 2: Nested structure with AnyPattern containing JSONObjectPattern
        val innerObjectPattern = JSONObjectPattern(
            pattern = mapOf(
                "innerData" to StringPattern()
            ),
            typeAlias = "(InnerObject)"
        )
        
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "field" to AnyPattern(
                    listOf(
                        innerObjectPattern,
                        StringPattern()
                    ), extensions = emptyMap()
                )
            ),
            typeAlias = "(OuterObject)"
        )

        val value = JSONObjectValue(mapOf(
            "field" to JSONObjectValue(mapOf(
                "innerData" to StringValue("test")
            ))
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).containsExactly("{OuterObject}.field{InnerObject}")
    }
    
    @Test
    fun `calculatePath should handle nested AnyPattern with DeferredPattern`() {
        // Test case 3: Nested structure using DeferredPattern without direct typeAlias
        val patterns = mapOf(
            "(NestedData)" to JSONObjectPattern(
                pattern = mapOf(
                    "value" to StringPattern()
                )
            )
        )
        
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "level1" to JSONObjectPattern(
                    pattern = mapOf(
                        "level2" to AnyPattern(
                            listOf(
                                DeferredPattern("(NestedData)"),
                                NumberPattern()
                            ), extensions = emptyMap()
                        )
                    ),
                    typeAlias = "(MiddleLevel)"
                )
            ),
            typeAlias = "(TopLevel)"
        )

        val value = JSONObjectValue(mapOf(
            "level1" to JSONObjectValue(mapOf(
                "level2" to JSONObjectValue(mapOf(
                    "value" to StringValue("test")
                ))
            ))
        ))

        val paths = pattern.calculatePath(value, Resolver(newPatterns = patterns))
        
        assertThat(paths).containsExactly("{TopLevel}.level1{MiddleLevel}.level2{NestedData}")
    }
    
    @Test
    fun `calculatePath should handle multiple nested AnyPatterns without typeAlias`() {
        // Test case 4: Multiple levels of nesting without typeAlias at some levels
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "level1" to JSONObjectPattern(
                    pattern = mapOf(
                        "level2" to JSONObjectPattern(
                            pattern = mapOf(
                                "level3" to AnyPattern(
                                    listOf(StringPattern(), NumberPattern()),
                                    extensions = emptyMap()
                                )
                            )
                        )
                    )
                )
            )
        )

        val value = JSONObjectValue(mapOf(
            "level1" to JSONObjectValue(mapOf(
                "level2" to JSONObjectValue(mapOf(
                    "level3" to NumberValue(42)
                ))
            ))
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).containsExactly("level1.level2.level3{number}")
    }
    
    @Test 
    fun `calculatePath should handle AnyPattern containing nested JSONObjectPattern with own AnyPatterns`() {
        // Test case 5: AnyPattern containing JSONObjectPattern that itself has AnyPatterns
        // This tests whether we can properly find nested AnyPatterns inside matched JSONObjectPatterns
        val nestedObjectWithAnyPattern = JSONObjectPattern(
            pattern = mapOf(
                "nestedField" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
            ),
            typeAlias = "(NestedObjectWithAny)"
        )
        
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "container" to AnyPattern(
                    listOf(
                        nestedObjectWithAnyPattern,
                        StringPattern()
                    ), extensions = emptyMap()
                )
            ),
            typeAlias = "(Container)"
        )

        val value = JSONObjectValue(mapOf(
            "container" to JSONObjectValue(mapOf(
                "nestedField" to StringValue("test")
            ))
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        // The expectation is that the AnyPattern should find the nested AnyPattern and include
        // the complete path through the nested patterns, including the nested field
        assertThat(paths).containsExactly("{Container}.container{NestedObjectWithAny}.nestedField{string}")
    }
    
    @Test
    fun `calculatePath should handle oneOf AnyPattern at level 3 without typeAlias`() {
        // Test case from problem statement: "Nested structure with oneOf at level 3"
        val pattern = JSONObjectPattern(
            pattern = mapOf(
                "level1" to JSONObjectPattern(
                    pattern = mapOf(
                        "level2" to JSONObjectPattern(
                            pattern = mapOf(
                                "level3" to AnyPattern(
                                    listOf(StringPattern(), NumberPattern()),
                                    extensions = emptyMap()
                                )
                            )
                        )
                    )
                )
            )
        )

        val value = JSONObjectValue(mapOf(
            "level1" to JSONObjectValue(mapOf(
                "level2" to JSONObjectValue(mapOf(
                    "level3" to StringValue("test")
                ))
            ))
        ))

        val paths = pattern.calculatePath(value, Resolver())
        
        assertThat(paths).containsExactly("level1.level2.level3{string}")
    }
}