package io.specmatic.core.value

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.TabularPattern
import io.specmatic.core.pattern.toTabularPattern
import io.specmatic.core.pattern.parsedValue

internal class TypeDeclarationKtTest {
    @Test
    fun `ability to converge a list with an empty list`() {
        val emptyList = parsedValue("""{"list": []}""")
        val oneElementList = parsedValue("""{"list": [1]}""")

        val (emptyDeclaration, _) = emptyList.typeDeclarationWithKey("List", emptyMap(), UseExampleDeclarations())
        val (oneElementDeclaration, _) = oneElementList.typeDeclarationWithKey("List", emptyMap(), UseExampleDeclarations())

        val convergedOneWay = convergeTypeDeclarations(emptyDeclaration, oneElementDeclaration)
        val convergedTheOtherWay = convergeTypeDeclarations(oneElementDeclaration, emptyDeclaration)

        assertThat((convergedOneWay.types.getValue("List") as TabularPattern).pattern.getValue("list")).isEqualTo(DeferredPattern("(integer*)"))
        assertThat((convergedTheOtherWay.types.getValue("List") as TabularPattern).pattern.getValue("list")).isEqualTo(DeferredPattern("(integer*)"))
    }

    @Test
    fun `primitive with key should create a new variable if it already exists for an example`() {
        val declaration = primitiveTypeDeclarationWithKey("name", emptyMap(), UseExampleDeclarations(mapOf("name" to "John Doe")), "string", "Jane Doe")
        assertThat(declaration).isEqualTo(Pair(TypeDeclaration("(name_: string)"), UseExampleDeclarations(mapOf("name" to "John Doe", "name_" to "Jane Doe"))))
    }

    @Test
    fun `primitive with key should not create a new variable if an example does not exist with that name`() {
        val declaration = primitiveTypeDeclarationWithKey("name", emptyMap(), UseExampleDeclarations(emptyMap()), "string", "Jane Doe")
        assertThat(declaration).isEqualTo(Pair(TypeDeclaration("(string)"), UseExampleDeclarations(mapOf("name" to "Jane Doe"))))
    }

    @Test
    fun `primitive without key should create a new variable even if an example does not exist with that name`() {
        val declaration = primitiveTypeDeclarationWithoutKey("name", emptyMap(), UseExampleDeclarations(emptyMap()), "string", "Jane Doe")
        assertThat(declaration).isEqualTo(Pair(TypeDeclaration("(name: string)"), UseExampleDeclarations(mapOf("name" to "Jane Doe"))))
    }

    @Test
    fun `keys with the same type will converge to the same type`() {
        val type1 = toTabularPattern(mapOf("name" to DeferredPattern("(string)")))
        val type2 = toTabularPattern(mapOf("name" to DeferredPattern("(string)")))

        val converged = converge(type1, type2)
        assertThat(converged.pattern.getValue("name")).isEqualTo(DeferredPattern("(string)"))
    }

    @Test
    fun `keys with different value types will converge to the first one`() {
        val type1 = toTabularPattern(mapOf("name" to DeferredPattern("(string)")))
        val type2 = toTabularPattern(mapOf("name" to DeferredPattern("(number)")))

        val converged = converge(type1, type2)
        assertThat(converged.pattern.getValue("name")).isEqualTo(DeferredPattern("(string)"))
    }

    @Test
    fun `first null value and second non null value will converge to optional value`() {
        val type1 = toTabularPattern(mapOf("name" to DeferredPattern("(null)")))
        val type2 = toTabularPattern(mapOf("name" to DeferredPattern("(number)")))

        val converged = converge(type1, type2)
        assertThat(converged.pattern.getValue("name")).isEqualTo(DeferredPattern("(number?)"))
    }

    @Test
    fun `first non null value and second null value will converge to optional value`() {
        val type1 = toTabularPattern(mapOf("name" to DeferredPattern("(number)")))
        val type2 = toTabularPattern(mapOf("name" to DeferredPattern("(null)")))

        val converged = converge(type1, type2)
        assertThat(converged.pattern.getValue("name")).isEqualTo(DeferredPattern("(number?)"))
    }

    @Test
    fun `two null values converge to null value`() {
        val type1 = toTabularPattern(mapOf("name" to DeferredPattern("(null)")))
        val type2 = toTabularPattern(mapOf("name" to DeferredPattern("(null)")))

        val converged = converge(type1, type2)
        assertThat(converged.pattern.getValue("name")).isEqualTo(DeferredPattern("(null)"))
    }

    @Test
    fun `with an optional number first and a non optional number second they should converge to optional number`() {
        val type1 = toTabularPattern(mapOf("name" to DeferredPattern("(number?)")))
        val type2 = toTabularPattern(mapOf("name" to DeferredPattern("(number)")))

        val converged = converge(type1, type2)
        assertThat(converged.pattern.getValue("name")).isEqualTo(DeferredPattern("(number?)"))
    }

    @Test
    fun `with a non optional number first and an optional number second they should converge to optional number`() {
        val type1 = toTabularPattern(mapOf("name" to DeferredPattern("(number)")))
        val type2 = toTabularPattern(mapOf("name" to DeferredPattern("(number?)")))

        val converged = converge(type1, type2)
        assertThat(converged.pattern.getValue("name")).isEqualTo(DeferredPattern("(number?)"))
    }

    @Test
    fun `with an empty array first and a json array second they should converge to a json array`() {
        val type1 = toTabularPattern(mapOf("data" to DeferredPattern("[]")))
        val type2 = toTabularPattern(mapOf("data" to DeferredPattern("(number*)")))

        val converged = converge(type1, type2)
        assertThat(converged.pattern.getValue("data")).isEqualTo(DeferredPattern("(number*)"))
    }

    @Test
    fun `with a json array first and an empty array second they should converge to a json array`() {
        val type1 = toTabularPattern(mapOf("data" to DeferredPattern("(number*)")))
        val type2 = toTabularPattern(mapOf("data" to DeferredPattern("[]")))

        val converged = converge(type1, type2)
        assertThat(converged.pattern.getValue("data")).isEqualTo(DeferredPattern("(number*)"))
    }

    @Test
    fun `with an empty array first and a number second they should converge to an empty array`() {
        val type1 = toTabularPattern(mapOf("data" to DeferredPattern("[]")))
        val type2 = toTabularPattern(mapOf("data" to DeferredPattern("(number)")))

        val converged = converge(type1, type2)
        assertThat(converged.pattern.getValue("data")).isEqualTo(DeferredPattern("[]"))
    }

    @Test
    fun `with a number first and an empty array second they should converge to a number`() {
        val type1 = toTabularPattern(mapOf("data" to DeferredPattern("(number)")))
        val type2 = toTabularPattern(mapOf("data" to DeferredPattern("[]")))

        val converged = converge(type1, type2)
        assertThat(converged.pattern.getValue("data")).isEqualTo(DeferredPattern("(number)"))
    }

    @Test
    fun `missing types in the first type declaration are included in converged`() {
        val missingIn1Type = toTabularPattern(mapOf("name" to DeferredPattern("(null)")))
        val personType = toTabularPattern(mapOf("name" to DeferredPattern("(number)")))

        val type1 = TypeDeclaration("(Nothing)", mapOf("(Person)" to personType))
        val type2 = TypeDeclaration("(Nothing)", mapOf("(MissingIn1)" to missingIn1Type, "(Person)" to personType))

        val converged = convergeTypeDeclarations(type1, type2)

        assertThat(converged.types).hasSize(2)

        assertThat(converged.types.getValue("(Person)")).isEqualTo(personType)
        assertThat(converged.types.getValue("(MissingIn1)")).isEqualTo(missingIn1Type)
    }

    @Test
    fun `missing types in the second type declaration are included in converged`() {
        val missingIn2Type = toTabularPattern(mapOf("name" to DeferredPattern("(null)")))
        val personType = toTabularPattern(mapOf("name" to DeferredPattern("(number)")))

        val type1 = TypeDeclaration("(Nothing)", mapOf("(Person)" to personType, "(MissingIn2)" to missingIn2Type))
        val type2 = TypeDeclaration("(Nothing)", mapOf("(Person)" to personType))

        val converged = convergeTypeDeclarations(type1, type2)

        assertThat(converged.types).hasSize(2)

        assertThat(converged.types.getValue("(Person)")).isEqualTo(personType)
        assertThat(converged.types.getValue("(MissingIn2)")).isEqualTo(missingIn2Type)
    }

    @Test
    fun `types are converged`() {
        val personType1 = toTabularPattern(mapOf("name" to DeferredPattern("(number)")))
        val personType2 = toTabularPattern(mapOf("name" to DeferredPattern("(null)")))
        val personTypeConverged = toTabularPattern(mapOf("name" to DeferredPattern("(number?)")))

        val type1 = TypeDeclaration("(Nothing)", mapOf("(Person)" to personType1))
        val type2 = TypeDeclaration("(Nothing)", mapOf("(Person)" to personType2))

        val converged = convergeTypeDeclarations(type1, type2)

        assertThat(converged.types.getValue("(Person)")).isEqualTo(personTypeConverged)
    }

    @Test
    fun `type keys are converged`() {
        val personType1 = toTabularPattern(mapOf("name" to DeferredPattern("(string)"), "surname" to DeferredPattern("(string)")))
        val personType2 = toTabularPattern(mapOf("name" to DeferredPattern("(string)"), "address" to DeferredPattern("(string)")))

        val type1 = TypeDeclaration("(Nothing)", mapOf("(Person)" to personType1))
        val type2 = TypeDeclaration("(Nothing)", mapOf("(Person)" to personType2))
        val expectedConverged = TypeDeclaration("(Nothing)", mapOf("(Person)" to toTabularPattern(mapOf("name" to DeferredPattern("(string)"), "surname?" to DeferredPattern("(string)"), "address?" to DeferredPattern("(string)")))))

        val converged = convergeTypeDeclarations(type1, type2)

        assertThat(converged).isEqualTo(expectedConverged)
    }

    @Test
    fun `an optional key when present but not optional in the converged type is converged to be optional`() {
        val personType1 = toTabularPattern(mapOf("name" to DeferredPattern("(string)"), "surname" to DeferredPattern("(string)")))
        val personType2 = toTabularPattern(mapOf("name" to DeferredPattern("(string)"), "surname?" to DeferredPattern("(string)")))

        val type1 = TypeDeclaration("(Nothing)", mapOf("(Person)" to personType1))
        val type2 = TypeDeclaration("(Nothing)", mapOf("(Person)" to personType2))

        val expectedConverged = TypeDeclaration(
            "(Nothing)",
            mapOf("(Person)" to toTabularPattern(mapOf("name" to DeferredPattern("(string)"), "surname?" to DeferredPattern("(string)")))))

        val converged = convergeTypeDeclarations(type1, type2)

        assertThat(converged).isEqualTo(expectedConverged)
    }
}