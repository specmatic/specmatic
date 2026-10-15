package io.specmatic.core.discriminator

import io.specmatic.core.Resolver
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.ListPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DiscriminatorDetailsBasedItemGeneratorTest {
    
    @Test
    fun `should generate discriminator based values for AnyPattern in ListPattern`() {
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

        val values = DiscriminatorBasedValueGenerator.generateDiscriminatorBasedValues(
            Resolver(),
            listPattern
        )

        assertThat(values).hasSize(2)
        assertThat(values.map { it.discriminatorValue }).containsExactlyInAnyOrder("savings", "current")

        val currentType =
            ((values.first { it.discriminatorValue == "current" }.value as JSONArrayValue).list.first() as JSONObjectValue).jsonObject["@type"]?.toStringLiteral()
        val savingsType =
            ((values.first { it.discriminatorValue == "savings" }.value as JSONArrayValue).list.first() as JSONObjectValue).jsonObject["@type"]?.toStringLiteral()

        assertThat(currentType).isEqualTo("current")
        assertThat(savingsType).isEqualTo("savings")
    }

    @Test
    fun `should generate discriminator based values for DeferredPattern in ListPattern`() {
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
            DeferredPattern("(Account)")
        )

        val deferredPattern = mapOf(
            "(Account)" to AnyPattern(
                listOf(savingsAccountPattern, currentAccountPattern),
                discriminatorProperty = "@type",
                discriminatorValues = setOf("savings", "current")
            )
        )
        val values = DiscriminatorBasedValueGenerator.generateDiscriminatorBasedValues(
            Resolver(newPatterns = deferredPattern),
            listPattern
        )

        assertThat(values).hasSize(2)
        assertThat(values.map { it.discriminatorValue }).containsExactlyInAnyOrder("savings", "current")

        val currentType =
            ((values.first { it.discriminatorValue == "current" }.value as JSONArrayValue).list.first() as JSONObjectValue).jsonObject["@type"]?.toStringLiteral()
        val savingsType =
            ((values.first { it.discriminatorValue == "savings" }.value as JSONArrayValue).list.first() as JSONObjectValue).jsonObject["@type"]?.toStringLiteral()

        assertThat(currentType).isEqualTo("current")
        assertThat(savingsType).isEqualTo("savings")
    }

    @Test
    fun `should generate single value for ListPattern without a discriminator`() {
        val simplePattern = JSONObjectPattern(
            pattern = mapOf(
                "field" to StringPattern()
            )
        )

        val listPattern = ListPattern(simplePattern)

        val values = DiscriminatorBasedValueGenerator
            .generateDiscriminatorBasedValues(Resolver(), listPattern)

        assertThat(values).hasSize(1)
        assertThat(values.map { it.discriminatorValue }).containsExactly("")
    }

    @Test
    fun `should generate discriminator based values for non-list pattern`() {
        val accountPattern = AnyPattern(
            pattern = listOf(
                JSONObjectPattern(
                    pattern = mapOf(
                        "@type" to ExactValuePattern(StringValue("savings"), discriminator = true),
                        "accountId" to StringPattern(),
                        "accountHolderName" to StringPattern(),
                        "balance" to NumberPattern()
                    )
                )
            ),
            discriminatorProperty = "@type",
            discriminatorValues = setOf("savings")
        )

        val values =
            DiscriminatorBasedValueGenerator.generateDiscriminatorBasedValues(Resolver(), accountPattern)

        assertThat(values).hasSize(1)
        assertThat(values.map { it.discriminatorValue }).containsExactly("savings")
        assertThat((values.first { it.discriminatorValue == "savings" }.value as JSONObjectValue).jsonObject["@type"]?.toStringLiteral()).isEqualTo("savings")
    }

    @Test
    fun `should generate discriminator based values for non-list DeferredPattern`() {
        val accountPattern = DeferredPattern("(Account)")

        val deferredPattern = mapOf(
            "(Account)" to AnyPattern(
                pattern = listOf(
                    JSONObjectPattern(
                        pattern = mapOf(
                            "@type" to ExactValuePattern(StringValue("savings"), discriminator = true),
                            "accountId" to StringPattern(),
                            "accountHolderName" to StringPattern(),
                            "balance" to NumberPattern()
                        )
                    )
                ),
                discriminatorProperty = "@type",
                discriminatorValues = setOf("savings")
            )
        )
        val values =
            DiscriminatorBasedValueGenerator
                .generateDiscriminatorBasedValues(Resolver(newPatterns = deferredPattern), accountPattern)

        assertThat(values).hasSize(1)
        assertThat(values.map { it.discriminatorValue }).containsExactly("savings")
        assertThat((values.first { it.discriminatorValue == "savings" }.value as JSONObjectValue).jsonObject["@type"]?.toStringLiteral()).isEqualTo("savings")
    }

    @Test
    fun `should return single value for non-list pattern without a discriminator`() {
        val simplePattern = JSONObjectPattern(
            pattern = mapOf(
                "field" to StringPattern()
            )
        )

        val values =
            DiscriminatorBasedValueGenerator.generateDiscriminatorBasedValues(Resolver(), simplePattern)

        assertThat(values).hasSize(1)
        assertThat(values.map { it.discriminatorValue }).containsExactly("")
    }

}
