import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SampleTest {

    @Test
    fun addition_shouldWork() {
        val result = 2 + 3
        assertEquals(6, result)
    }

    @Test
    fun string_shouldContain() {
        val value = "gradle kotlin test"
        assertTrue(value.contains("kotlin"))
    }

    @Test
    fun division_byZero_shouldThrow() {
        assertFailsWith<ArithmeticException> {
            val x = 10 / 0
        }
    }
}
