package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.core.value.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

class EmailPatternTest {
    @Test
    @Tag(GENERATION)
    fun `negative values should be generated`() {
        val result = EmailPattern().negativeBasedOn(Row(), Resolver()).toList().map { it.value }
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder(
            "null",
            "number",
            "boolean",
            "string"
        )
    }

    @Test
    fun `email pattern should match an email string`() {
        val matchResult = EmailPattern().matches(StringValue("hello@world.com"), Resolver())
        assertThat(matchResult).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `email pattern should not match an invalid email string`() {
        val invalidEmails = listOf(
            "hello@world",        // Missing domain suffix
            "hello@.com",         // Missing domain name
            "@world.com",         // Missing local part
            "hello world@com",    // Space in email
            "hello@world.c",      // Domain suffix too short
            "hello@world,com",    // Comma instead of dot
            "hello@world@com",    // Multiple @ symbols
            "hello@world.com.",   // Trailing dot
        )

        invalidEmails.forEach { email ->
            val matchResult = EmailPattern().matches(StringValue(email), Resolver())
            assertThat(matchResult).isInstanceOf(Result.Failure::class.java)
        }
    }

    @Test
    fun `email pattern should generate an email string test`() {
        val emails = EmailPattern().newBasedOn(Row(), Resolver()).toList().map { it.value }
        assertThat(emails).allSatisfy {
            assertThat(it).isInstanceOf(EmailPattern::class.java)
        }
    }

    @Test
    fun `email should not encompass string`() {
        assertThat(
            EmailPattern().encompasses(StringPattern(), Resolver(), Resolver(), emptySet())
        ).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `fillInTheBlanks should handle any-value pattern token correctly`() {
        val pattern = EmailPattern()
        val resolver = Resolver()
        val value = StringValue("(anyvalue)")

        val filledInValue = pattern.fillInTheBlanks(value, resolver).value
        val matchResult = pattern.matches(filledInValue, resolver)

        assertThat(matchResult.isSuccess()).withFailMessage(matchResult.reportString()).isTrue()
    }

    private fun File.getExternalExamplesFromContract(): List<ScenarioStub> {
        val attributeExamples = this.parentFile.resolve("${this.nameWithoutExtension}$EXAMPLES_DIR_SUFFIX")
        val normalExamples = this.parentFile.resolve("${this.nameWithoutExtension}${EXAMPLES_DIR_SUFFIX}_no_attr")

        return attributeExamples.listFiles()?.map { ScenarioStub.readFromFile(it) }?.plus(
            normalExamples.listFiles()?.map { ScenarioStub.readFromFile(it) } ?: emptyList()
        ) ?: emptyList()
    }

    @Test
    fun `should be able to handle email format in example`() {
        val specFilepath = File("src/test/resources/openapi/spec_with_format_email_with_external_example/spec.yaml")

        val feature = OpenApiSpecification.fromFile(specFilepath.absolutePath).toFeature()
        val stubScenarios = specFilepath.getExternalExamplesFromContract()

        HttpStub(feature, stubScenarios).use {
            val response = it.client.execute(
                HttpRequest(
                "GET",
                "/pets/2"
                )
            )

            assertThat(response.status).isEqualTo(200)

            val body = (response.body as JSONObjectValue).jsonObject
            assertThat(body.keys).containsExactlyInAnyOrder("id", "name", "type", "status", "email")

            val emailValue = EmailPattern().matches(body["email"] as StringValue, Resolver())
            assertThat(emailValue).isInstanceOf(Result.Success::class.java)
        }
    }


    @Test
    fun `should be able to fix invalid values`() {
        val pattern = JSONObjectPattern(mapOf("email" to EmailPattern()), typeAlias = "(Test)")
        val dictionary = "Test: { email: SomeDude@example.com }".let(Dictionary::fromYaml)
        val resolver = Resolver(dictionary = dictionary)
        val invalidValues = listOf(
            StringValue("Unknown"),
            NumberValue(999),
            NullValue
        )

        assertThat(invalidValues).allSatisfy {
            val fixedValue = pattern.fixValue(JSONObjectValue(mapOf("email" to it)), resolver)
            fixedValue as JSONObjectValue
            assertThat(fixedValue.jsonObject["email"]).isEqualTo(StringValue("SomeDude@example.com"))
        }
    }

    @Test
    fun `should be able to create newBasedOn values without row value`() {
        val jsonPattern = JSONObjectPattern(mapOf("id" to NumberPattern(), "email" to EmailPattern()), typeAlias = "(Details)")
        val newBased = jsonPattern.newBasedOn(Resolver())

        assertThat(newBased.toList()).allSatisfy {
            assertThat(it.pattern.getValue("email")).isInstanceOf(EmailPattern::class.java)
        }
    }

    @Test
    fun `resolveSubstitutions should return value when no substitution needed`() {
        val pattern = EmailPattern()
        val resolver = Resolver()
        val substitution = Substitution(
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            EmptyStringPattern,
            resolver,
            JSONObjectValue(mapOf())
        )
        val emailValue = StringValue("test@example.com")

        val result = pattern.resolveSubstitutions(substitution, emailValue, resolver, null)

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(emailValue)
    }

    @Test
    fun `resolveSubstitutions should handle data lookup substitution with valid email`() {
        val pattern = EmailPattern()
        val originalRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("(DEPARTMENT:string)"))))
        val runningRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("engineering"))))
        val resolver = Resolver()
        val dataLookup = JSONObjectValue(mapOf(
            "dataLookup" to JSONObjectValue(mapOf(
                "dept" to JSONObjectValue(mapOf(
                    "engineering" to JSONObjectValue(mapOf(
                        "contact" to StringValue("support@engineering.example.com")
                    )),
                    "sales" to JSONObjectValue(mapOf(
                        "contact" to StringValue("sales@example.com")
                    ))
                ))
            ))
        ))
        val substitution = Substitution(
            runningRequest,
            originalRequest,
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            JSONObjectPattern(mapOf("department" to StringPattern())),
            resolver,
            dataLookup
        )
        val valueExpression = StringValue("$(dataLookup.dept[DEPARTMENT].contact)")

        val result = pattern.resolveSubstitutions(substitution, valueExpression, resolver, null)

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(StringValue("support@engineering.example.com"))
    }

    @Test
    fun `resolveSubstitutions should fail when substituted value doesn't match email pattern`() {
        val pattern = EmailPattern()
        val originalRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("(DEPARTMENT:string)"))))
        val runningRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("engineering"))))
        val resolver = Resolver()
        val dataLookup = JSONObjectValue(mapOf(
            "dataLookup" to JSONObjectValue(mapOf(
                "dept" to JSONObjectValue(mapOf(
                    "engineering" to JSONObjectValue(mapOf(
                        "contact" to StringValue("invalid-email-format")
                    ))
                ))
            ))
        ))
        val substitution = Substitution(
            runningRequest,
            originalRequest,
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            JSONObjectPattern(mapOf("department" to StringPattern())),
            resolver,
            dataLookup
        )
        val valueExpression = StringValue("$(dataLookup.dept[DEPARTMENT].contact)")

        val result = pattern.resolveSubstitutions(substitution, valueExpression, resolver, null)

        assertThat(result).isInstanceOf(HasFailure::class.java)
    }

    @Test
    fun `should be able to use values provided by StringProviders when one is registered`() {
        val pattern = EmailPattern()
        val resolver = Resolver()
        val provider = object: StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>): String = "specmatic@test.in"
        }

        StringProviders.with(provider) {
            val generated = pattern.generate(resolver)
            assertThat(generated).isEqualTo(StringValue("specmatic@test.in"))
        }
    }

    @Test
    fun `should generate a random string if no provider exists or can't provide a value`() {
        val pattern = EmailPattern()
        val resolver = Resolver()
        val provider = object: StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>): String? = null
        }

        StringProviders.with(provider) {
            val generated = pattern.generate(resolver)
            assertThat(generated).isInstanceOf(StringValue::class.java)
        }
    }

    @Test
    fun `invalid value provided by any StringProviders should be halted by resolver and result in random generation`() {
        val pattern = EmailPattern()
        val resolver = Resolver()
        val provider = object: StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>): String = "123"
        }

        StringProviders.with(provider) {
            val generated = pattern.generate(resolver)
            assertThat(generated.toStringLiteral()).isNotEqualTo("123")
            assertThat(generated).isInstanceOf(StringValue::class.java)
        }
    }
}