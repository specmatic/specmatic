package io.specmatic.core.config.v3

import io.specmatic.core.config.validation.ConfigValidationSeverity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ValidationContextTest {
    private val resolver = object : RefOrValueResolver {
        override fun resolveRef(reference: String): Any = emptyMap<String, Any>()
    }

    @Test
    fun `uses target location for referenced properties and authored location for reference siblings`() {
        val reference: RefOrValue<Map<String, Any>> = RefOrValue.Reference(
            ref = "#/components/runOptions/kafka",
            extra = mapOf("inline" to emptyMap<String, Any>()),
        )

        val output = ValidationContext(location = "/dependencies", resolver = resolver)
            .child("runOptions")
            .check(
                reference = reference,
                resolve = { _, _ -> emptyMap() },
                validate = { _, context ->
                    listOf(
                        context.child("target").error(ConfigValidationSeverity.ERROR, "target"),
                        context.child("inline").error(ConfigValidationSeverity.ERROR, "inline"),
                    )
                },
            )

        assertThat(output.map { it.instanceLocation }).containsExactly(
            "/components/runOptions/kafka/target",
            "/dependencies/runOptions/inline",
        )
    }

    @Test
    fun `nested reference uses its own target and sibling locations`() {
        val nestedReference = "#/components/runOptions/kafka"
        val outerReference: RefOrValue<Map<String, Any>> = RefOrValue.Reference(
            ref = "#/components/services/kafkaService",
            extra = mapOf(
                "runOptions" to mapOf($$"$ref" to nestedReference),
            ),
        )

        val nestedValue: RefOrValue<Map<String, Any>> = RefOrValue.Reference(
            ref = nestedReference,
            extra = mapOf("inline" to emptyMap<String, Any>()),
        )

        val output = ValidationContext(location = "/dependencies", resolver = resolver)
            .child("service")
            .check(
                reference = outerReference,
                resolve = { _, _ -> emptyMap() },
                validate = { _, context ->
                    context.child("runOptions").check(
                        reference = nestedValue,
                        resolve = { _, _ -> emptyMap() },
                        validate = { _, nestedContext ->
                            listOf(
                                nestedContext.child("target").error(ConfigValidationSeverity.ERROR, "target"),
                                nestedContext.child("inline").error(ConfigValidationSeverity.ERROR, "inline"),
                            )
                        },
                    )
                },
            )

        assertThat(output.map { it.instanceLocation }).containsExactly(
            "/components/runOptions/kafka/target",
            "/dependencies/service/runOptions/inline",
        )
    }

    @Test
    fun `keeps authored location for external references`() {
        val reference: RefOrValue<Map<String, Any>> = RefOrValue.Reference(
            ref = "kafka.yaml#/components/runOptions/kafka",
            extra = mapOf("inline" to emptyMap<String, Any>()),
        )

        val output = ValidationContext(location = "/dependencies", resolver = resolver)
            .child("runOptions")
            .check(
                reference = reference,
                resolve = { _, _ -> emptyMap() },
                validate = { _, context ->
                    listOf(
                        context.child("target").error(ConfigValidationSeverity.ERROR, "target"),
                        context.child("inline").error(ConfigValidationSeverity.ERROR, "inline"),
                    )
                },
            )

        assertThat(output.map { it.instanceLocation }).containsExactly(
            "/dependencies/runOptions/target",
            "/dependencies/runOptions/inline",
        )
    }

    @Test
    fun `escapes JSON pointer path segments`() {
        val context = ValidationContext(location = "/dependencies", resolver = resolver)
        assertThat(context.child("run/options").child("kafka~config").location)
            .isEqualTo("/dependencies/run~1options/kafka~0config")
    }
}
