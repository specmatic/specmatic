package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScalarTypeTest {
    @Test
    fun `ScalarType should extend HasDefaultExample`() {
        val numberPattern = NumberPattern(example = "42")
        val stringPattern = StringPattern(example = "test")
        val booleanPattern = BooleanPattern(example = "true")
        val uuidPattern = UUIDPattern(example = "123e4567-e89b-12d3-a456-426614174000")
        val datePattern = DatePattern(example = "2023-01-01")
        val timePattern = TimePattern(example = "12:30:00")
        val dateTimePattern = DateTimePattern(example = "2023-01-01T12:30:00Z")
        val nullPattern = NullPattern
        val binaryPattern = BinaryPattern(example = "SGVsbG8gV29ybGQ=")
        val base64Pattern = Base64StringPattern(example = "SGVsbG8gV29ybGQ=")

        // Verify they all implement ScalarType (which extends HasDefaultExample)
        assertThat(numberPattern).isInstanceOf(ScalarType::class.java)
        assertThat(stringPattern).isInstanceOf(ScalarType::class.java)
        assertThat(booleanPattern).isInstanceOf(ScalarType::class.java)
        assertThat(uuidPattern).isInstanceOf(ScalarType::class.java)
        assertThat(datePattern).isInstanceOf(ScalarType::class.java)
        assertThat(timePattern).isInstanceOf(ScalarType::class.java)
        assertThat(dateTimePattern).isInstanceOf(ScalarType::class.java)
        assertThat(nullPattern).isInstanceOf(ScalarType::class.java)
        assertThat(binaryPattern).isInstanceOf(ScalarType::class.java)
        assertThat(base64Pattern).isInstanceOf(ScalarType::class.java)

        // Verify they all have example properties
        assertThat(numberPattern.example).isEqualTo("42")
        assertThat(stringPattern.example).isEqualTo("test")
        assertThat(booleanPattern.example).isEqualTo("true")
        assertThat(uuidPattern.example).isEqualTo("123e4567-e89b-12d3-a456-426614174000")
        assertThat(datePattern.example).isEqualTo("2023-01-01")
        assertThat(timePattern.example).isEqualTo("12:30:00")
        assertThat(dateTimePattern.example).isEqualTo("2023-01-01T12:30:00Z")
        assertThat(nullPattern.example).isNull()
        assertThat(binaryPattern.example).isEqualTo("SGVsbG8gV29ybGQ=")
        assertThat(base64Pattern.example).isEqualTo("SGVsbG8gV29ybGQ=")
    }

    @Test
    fun `generate should use examples when provided`() {
        val resolver = Resolver()
        
        val numberPattern = NumberPattern(example = "42")
        val stringPattern = StringPattern(example = "test")
        val booleanPattern = BooleanPattern(example = "true")
        
        val generatedNumber = numberPattern.generate(resolver)
        val generatedString = stringPattern.generate(resolver)
        val generatedBoolean = booleanPattern.generate(resolver)
        
        assertThat(generatedNumber.toStringLiteral()).isEqualTo("42")
        assertThat(generatedString.toStringLiteral()).isEqualTo("test")
        assertThat(generatedBoolean.toStringLiteral()).isEqualTo("true")
    }
}