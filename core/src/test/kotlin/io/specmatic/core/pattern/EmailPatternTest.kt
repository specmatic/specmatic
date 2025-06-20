package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
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
            "hello@-world.com",   // Invalid domain starting with a hyphen
            "hello@world-.com",   // Invalid domain ending with a hyphen
            "hello@world..com",   // Double dots in domain
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
}