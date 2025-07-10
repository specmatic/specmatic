package io.specmatic.stub.report

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class StubTypeTest {
    
    @Test
    fun `should have correct values for all stub types`() {
        assertEquals("explicit", StubType.EXPLICIT.value)
        assertEquals("example", StubType.EXAMPLE.value)
        assertEquals("generated", StubType.GENERATED.value)
        assertEquals("pass-through", StubType.PASS_THROUGH.value)
    }
    
    @Test
    fun `should convert from string value to enum`() {
        assertEquals(StubType.EXPLICIT, StubType.fromValue("explicit"))
        assertEquals(StubType.EXAMPLE, StubType.fromValue("example"))
        assertEquals(StubType.GENERATED, StubType.fromValue("generated"))
        assertEquals(StubType.PASS_THROUGH, StubType.fromValue("pass-through"))
    }
    
    @Test
    fun `should throw exception for unknown stub type`() {
        val exception = assertThrows<IllegalArgumentException> {
            StubType.fromValue("unknown")
        }
        assertEquals("Unknown stub type: unknown", exception.message)
    }
}