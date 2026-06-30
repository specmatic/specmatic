package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.*
import io.specmatic.core.substitution.SubstitutionImpl
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
    fun `response encompasses should resolve deferred headers using the passed resolvers`() {
        val older = HttpResponsePattern(
            status = 200,
            headersPattern = HttpHeadersPattern(mapOf("X-Data" to DeferredPattern("(OlderHeaderType)"))),
            body = StringPattern()
        )
        val newer = HttpResponsePattern(
            status = 200,
            headersPattern = HttpHeadersPattern(mapOf("X-Data" to DeferredPattern("(NewerHeaderType)"))),
            body = StringPattern()
        )

        val olderResolver = Resolver(newPatterns = mapOf("(NewerHeaderType)" to StringPattern()))
        val newerResolver = Resolver(newPatterns = mapOf("(OlderHeaderType)" to StringPattern()))
        val result = newer.encompasses(older, olderResolver, newerResolver)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `response encompasses should report header mismatch when deferred headers resolve to incompatible types`() {
        val older = HttpResponsePattern(
            status = 200,
            headersPattern = HttpHeadersPattern(mapOf("X-Data" to DeferredPattern("(OlderHeaderType)"))),
            body = StringPattern()
        )
        val newer = HttpResponsePattern(
            status = 200,
            headersPattern = HttpHeadersPattern(mapOf("X-Data" to DeferredPattern("(NewerHeaderType)"))),
            body = StringPattern()
        )

        val olderResolver = Resolver(newPatterns = mapOf("(NewerHeaderType)" to NumberPattern()))
        val newerResolver = Resolver(newPatterns = mapOf("(OlderHeaderType)" to StringPattern()))
        val result = newer.encompasses(older, olderResolver, newerResolver)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains("RESPONSE.HEADER.X-Data")
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

    @Test
    fun `matchesStatusAndContentType should succeed when status and content type both match`() {
        val pattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(contentType = "application/json"))
        val result = pattern.matchesStatusAndContentType(
            httpResponse = HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/json")),
            resolver = Resolver()
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `matchesStatusAndContentType should succeed when status and simplified content type both match`() {
        val pattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(contentType = "application/json"))
        val result = pattern.matchesStatusAndContentType(
            httpResponse = HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/json; charset=utf-8")),
            resolver = Resolver()
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `matchesStatusAndContentType should fail with response breadcrumb when content type mismatches`() {
        val pattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(contentType = "application/json"))
        val result = pattern.matchesStatusAndContentType(
            httpResponse = HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/xml")),
            resolver = Resolver()
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains(">> RESPONSE.HEADER.Content-Type")
    }

    @Test
    fun `matchesStatusAndContentType should fail with status breadcrumb when status mismatches`() {
        val pattern = HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(contentType = "application/json"))
        val result = pattern.matchesStatusAndContentType(
            httpResponse = HttpResponse(status = 201, headers = mapOf("Content-Type" to "application/json")),
            resolver = Resolver()
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains(">> RESPONSE.STATUS")
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

    @Nested
    inner class ResolveSubstitutionsTests {
        @Test
        fun `should resolve stored composite object and array values in response body`() {
            val profilePattern = JSONObjectPattern(
                typeAlias = "(Profile)",
                pattern = mapOf("name" to StringPattern()),
            )

            val petPattern = JSONObjectPattern(
                typeAlias = "(Pet)",
                pattern = mapOf("name" to StringPattern()),
            )

            val petsPattern = ListPattern(petPattern, typeAlias = "(Pets)")
            val bodyPattern = JSONObjectPattern(pattern = mapOf("profile" to profilePattern, "pets" to petsPattern))
            val resolver = Resolver(newPatterns = mapOf("(Profile)" to profilePattern, "(Pet)" to petPattern, "(Pets)" to petsPattern))

            val profile = parsedJSONObject("""{"name": "Sherlock"}""")
            val pets = parsedJSONArray("""[{"name": "Dog"},{"name": "Cat"}]""")
            val substitution = SubstitutionImpl.empty()
                .upsertStoreUsing(StringValue("(profile:Profile)"), StringValue(profile.toUnformattedString()), resolver)
                .upsertStoreUsing(StringValue("(pets:Pets)"), StringValue(pets.toUnformattedString()), resolver)

            val responsePattern = HttpResponsePattern(status = 200, body = bodyPattern)
            val response = HttpResponse(
                status = 200,
                body = parsedJSONObject("""{"profile": "$(profile)", "pets": "$(pets)"}""")
            )

            val resolved = responsePattern.resolveSubstitutions(substitution, response, resolver).value
            assertThat(resolved.body).isEqualTo(
                parsedJSONObject("""{"profile": {"name": "Sherlock"}, "pets": [{"name": "Dog"}, {"name": "Cat"}]}""")
            )
        }

        @Test
        fun `should resolve composite object and array values from data lookup in response body`() {
            val profilePattern = JSONObjectPattern(
                typeAlias = "(Profile)",
                pattern = mapOf("name" to StringPattern()),
            )

            val petPattern = JSONObjectPattern(
                typeAlias = "(Pet)",
                pattern = mapOf("name" to StringPattern()),
            )

            val petsPattern = ListPattern(petPattern, typeAlias = "(Pets)")
            val bodyPattern = JSONObjectPattern(pattern = mapOf("profile" to profilePattern, "pets" to petsPattern))
            val resolver = Resolver(newPatterns = mapOf("(Profile)" to profilePattern, "(Pet)" to petPattern, "(Pets)" to petsPattern))

            val substitution = SubstitutionImpl.from(
                resolver = resolver,
                runningRequest = HttpRequest(method = "GET", path = "/profiles/10"),
                originalRequest = HttpRequest(method = "GET", path = "/profiles/(ID:number)"),
                data = parsedJSONObject("""
                {
                  "lookupData": {
                    "dictionary": {
                      "10": {
                        "profile": {"name": "Sherlock"},
                        "pets": [{"name": "Dog"}, {"name": "Cat"}]
                      }
                    }
                  }
                }
                """.trimIndent())
            )

            val responsePattern = HttpResponsePattern(status = 200, body = bodyPattern)
            val response = HttpResponse(
                status = 200,
                body = parsedJSONObject(
                    """{"profile": "$(lookupData.dictionary[ID].profile)", "pets": "$(lookupData.dictionary[ID].pets)"}"""
                )
            )

            val resolved = responsePattern.resolveSubstitutions(substitution, response, resolver).value
            assertThat(resolved.body).isEqualTo(
                parsedJSONObject("""{"profile": {"name": "Sherlock"}, "pets": [{"name": "Dog"}, {"name": "Cat"}]}""")
            )
        }

        @Test
        fun `should use dictionary backed generation when substitutions are unresolved across response`() {
            val addressPattern = JSONObjectPattern(
                typeAlias = "(Address)",
                pattern = mapOf("street" to StringPattern()),
            )

            val bodyPattern = JSONObjectPattern(
                typeAlias = "(PetResponse)",
                pattern = mapOf(
                    "message" to StringPattern(),
                    "addresses" to ListPattern(addressPattern)
                ),
            )

            val responsePattern = HttpResponsePattern(
                status = 200,
                body = bodyPattern,
                headersPattern = HttpHeadersPattern(mapOf("X-Trace" to StringPattern())),
            )

            val response = HttpResponse(
                status = 200,
                headers = mapOf("X-Trace" to "$(missing-trace)"),
                body = parsedJSONObject("""{"message": "$(missing-message)", "addresses": [{"street": "$(missing-street)"}]}""")
            )

            val resolver = Resolver(
                newPatterns = mapOf("(PetResponse)" to bodyPattern, "(Address)" to addressPattern),
                dictionary = Dictionary.fromYaml("""
                RESPONSE:
                  HEADER:
                    X-Trace: trace-from-dictionary
                PetResponse:
                  message: done
                Address:
                  street: Baker Street
                """.trimIndent())
            )

            val resolved = responsePattern.resolveSubstitutions(SubstitutionImpl.empty(), response, resolver).value
            assertThat(resolved.headers["X-Trace"]).isEqualTo("trace-from-dictionary")
            assertThat(resolved.body).isEqualTo(
                parsedJSONObject("""{"message": "done", "addresses": [{"street": "Baker Street"}]}""")
            )
        }
    }
}
