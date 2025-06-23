package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import org.junit.jupiter.api.Nested

internal class HttpResponsePatternTest {
    @Test
    fun `it should encompass itself`() {
        val httpResponsePattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(mapOf("X-Optional?" to StringPattern())))
        assertThat(httpResponsePattern.encompasses(httpResponsePattern, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `it should encompass another smaller response pattern`() {
        val bigger = HttpResponsePattern(
            status = 200,
            headersPattern = HttpHeadersPattern(mapOf("X-Required" to StringPattern())),
            body = toTabularPattern(
                mapOf(
                    "data" to AnyPattern(
                        listOf(StringPattern(), NullPattern),
                        extensions = emptyMap()
                    )
                )
            )
        )
        val smaller = HttpResponsePattern(
            status = 200,
            headersPattern = HttpHeadersPattern(mapOf("X-Required" to StringPattern(), "X-Extra" to StringPattern())),
            body = toTabularPattern(mapOf("data" to StringPattern()))
        )
        assertThat(bigger.encompasses(smaller, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `when validating a response string against a response type number, should return an error`() {
        val response = HttpResponse(200, emptyMap(), StringValue("not a number"))
        val pattern = HttpResponsePattern(status = 200, body = NumberPattern())

        assertThat(pattern.matchesResponse(response, Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `all response match errors should be returned together`() {
        val response = HttpResponse.ok(StringValue("not a number")).copy(headers = mapOf("X-Data" to "abc123"))
        val pattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(mapOf("X-Data" to NumberPattern())), body = NumberPattern())

        val result = pattern.matchesResponse(response, Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)

        result as Result.Failure

        assertThat(result.toMatchFailureDetailList()).hasSize(2)

        val resultText = result.reportString()
        assertThat(resultText).contains(">> RESPONSE.HEADER.X-Data")
        assertThat(resultText).contains(">> RESPONSE.BODY")
    }

    @Test
    fun `all response backward compatibility header errors should be returned together with body errors`() {
        val older = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(mapOf("X-Data" to StringPattern())), body = StringPattern())
        val newer = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(mapOf("X-Data" to NumberPattern())), body = NumberPattern())

        val result: Result = newer.encompasses(older, Resolver(), Resolver())

        val resultText = result.reportString()

        assertThat(resultText).contains("RESPONSE.HEADER.X-Data")
        assertThat(resultText).contains("RESPONSE.BODY")
    }

    @Test
    fun `should generate no body response if the body pattern is NoBodyPattern`() {
        val httpResponsePattern = HttpResponsePattern(
            status = 203,
            body = NoBodyPattern
        )
        val response = httpResponsePattern.fillInTheBlanks(Resolver())

        assertThat(response.status).isEqualTo(203)
        assertThat(response.headers["Content-Type"]).isNull()
        assertThat(response.body).isEqualTo(NoBodyValue)
    }

    @Nested
    inner class GenerateResponseV2Tests {

        @Test
        fun `should generate responses for a list pattern based response body with discriminator`() {
            val savingsAccountPattern = JSONObjectPattern(
                pattern = mapOf(
                    "@type" to ExactValuePattern(StringValue("savings"), discriminator = true),
                    "accountId" to StringPattern(),
                    "accountHolderName" to StringPattern(),
                    "balance" to NumberPattern(),
                    "minimumBalance" to NumberPattern()
                )
            )

            val currentAccountPattern = JSONObjectPattern(
                pattern = mapOf(
                    "@type" to ExactValuePattern(StringValue("current"), discriminator = true),
                    "accountId" to StringPattern(),
                    "accountHolderName" to StringPattern(),
                    "balance" to NumberPattern(),
                    "overdraftLimit" to NumberPattern()
                )
            )

            val listPattern = ListPattern(
                AnyPattern(
                    listOf(savingsAccountPattern, currentAccountPattern),
                    discriminatorProperty = "@type",
                    discriminatorValues = setOf("savings", "current")
                )
            )

            val httpResponsePattern = HttpResponsePattern(
                body = listPattern
            )

            val responses = httpResponsePattern.generateResponseV2(Resolver())

            assertThat(responses.size).isEqualTo(2)
            assertThat(responses.map { it.discriminatorValue }).containsExactlyInAnyOrder("savings", "current")


            val savingsAccountRequestBody = (responses.first { it.discriminatorValue == "savings" }.value.body as JSONArrayValue).list.first() as JSONObjectValue
            val currentAccountRequestBody = (responses.first { it.discriminatorValue == "current" }.value.body as JSONArrayValue).list.first() as JSONObjectValue
            assertThat(savingsAccountRequestBody.jsonObject["@type"]?.toStringLiteral()).isEqualTo("savings")
            assertThat(currentAccountRequestBody.jsonObject["@type"]?.toStringLiteral()).isEqualTo("current")
        }

        @Test
        fun `should generate responses for a non-list pattern based response body with discriminator`() {
            val savingsAccountPattern = JSONObjectPattern(
                pattern = mapOf(
                    "@type" to ExactValuePattern(StringValue("savings"), discriminator = true),
                    "accountId" to StringPattern(),
                    "accountHolderName" to StringPattern(),
                    "balance" to NumberPattern(),
                    "minimumBalance" to NumberPattern()
                )
            )

            val currentAccountPattern = JSONObjectPattern(
                pattern = mapOf(
                    "@type" to ExactValuePattern(StringValue("current"), discriminator = true),
                    "accountId" to StringPattern(),
                    "accountHolderName" to StringPattern(),
                    "balance" to NumberPattern(),
                    "overdraftLimit" to NumberPattern()
                )
            )

            val bodyPattern = AnyPattern(
                listOf(savingsAccountPattern, currentAccountPattern),
                discriminatorProperty = "@type",
                discriminatorValues = setOf("savings", "current")
            )

            val httpResponsePattern = HttpResponsePattern(
                body = bodyPattern
            )

            val responses = httpResponsePattern.generateResponseV2(Resolver())

            assertThat(responses.size).isEqualTo(2)
            assertThat(responses.map { it.discriminatorValue }).containsExactlyInAnyOrder("savings", "current")

            val savingsAccountRequestBody = (responses.first { it.discriminatorValue ==  "savings"}.value.body as JSONObjectValue)
            val currentAccountRequestBody = (responses.first { it.discriminatorValue ==  "current"}.value.body as JSONObjectValue)
            assertThat(savingsAccountRequestBody.jsonObject["@type"]?.toStringLiteral()).isEqualTo("savings")
            assertThat(currentAccountRequestBody.jsonObject["@type"]?.toStringLiteral()).isEqualTo("current")
        }
    }
}
