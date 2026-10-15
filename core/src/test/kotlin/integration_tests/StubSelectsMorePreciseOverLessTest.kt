package integration_tests

import io.specmatic.testPort

import io.specmatic.core.HttpRequest
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.stub.ContractStub
import io.specmatic.stub.createStubFromContracts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class StubSelectsMorePreciseOverLessTest {
    companion object {
        lateinit var stub: ContractStub

        @BeforeAll
        @JvmStatic
        fun setup() {
            stub = createStubFromContracts(listOf("src/test/resources/spec_with_mixed_examples.yaml"), timeoutMillis = 0, port = testPort())
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            stub.close()
        }
    }

    @ParameterizedTest
    @CsvSource(
        "Inline Example Product, 100, 100",
        "Precise Example Product, 100, 101",
        "Variable Name Example Product, 100, 102",
        "Variable Name And Inventory Example Product, 200, 103"
    )
    fun `should return specific expectation over general`(name: String, inventory: String, id: String) {
        val request = HttpRequest("POST", "/products", body = parsedJSONObject("""{"name": "$name", "type": "other", "inventory": $inventory}"""))

        stub.client.execute(request).let { response ->
            val jsonResponse = response.body as JSONObjectValue

            assertThat(jsonResponse.findFirstChildByName("id")?.toStringLiteral()).isEqualTo(id)
        }
    }
}
