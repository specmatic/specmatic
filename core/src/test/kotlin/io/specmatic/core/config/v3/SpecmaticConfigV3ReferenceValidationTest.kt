package io.specmatic.core.config.v3

import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.validation.ConfigValidationResult
import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.validation.SpecmaticConfigValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SpecmaticConfigV3ReferenceValidationTest {
    private val validator = SpecmaticConfigValidator()

    @Nested
    inner class ReferenceResolution {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.config.v3.SpecmaticConfigV3ReferenceValidationTest#missingTypedReferenceCases")
        fun `reports missing typed references`(testCase: InvalidReferenceCase) {
            assertThat(validator.validate(testCase.configuration))
                .isEqualTo(invalidAt(testCase.instanceLocation, testCase.message, testCase.loadError))
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.config.v3.SpecmaticConfigV3ReferenceValidationTest#validTypedReferenceCases")
        fun `accepts typed references with compatible targets`(testCase: ValidReferenceCase) {
            assertThat(validator.validate(testCase.configuration))
                .isEqualTo(ConfigValidationResult.Valid(SpecmaticConfigVersion.VERSION_3))
        }
    }

    @Nested
    inner class TypeCompatibility {
        @Test
        fun `reports a service reference that resolves to the wrong component`() {
            assertThat(validator.validate($$"""
            version: 3
            systemUnderTest:
              service:
                $ref: '#/components/sources/source'
            components:
              sources:
                source:
                  filesystem: {}
            """.trimIndent())).isEqualTo(
                invalidAt(
                    instanceLocation = "/systemUnderTest/service",
                    message = "Reference '#/components/sources/source' could not be resolved as the expected typed value: Failed to convert resolved value to CommonServiceConfig<TestRunOptions, TestSettings>",
                    loadError = "Failed to convert resolved value to CommonServiceConfig<TestRunOptions, TestSettings>"
                )
            )
        }

        @Test
        fun `reports inline siblings that make a typed reference incompatible`() {
            assertThat(validator.validate($$"""
            version: 3
            systemUnderTest:
              service:
                $ref: '#/components/services/sut'
                definitions: invalid
            components:
              services:
                sut:
                  definitions: []
            """.trimIndent())).isEqualTo(
                invalidAt(
                    instanceLocation = "/systemUnderTest/service",
                    message = "Reference '#/components/services/sut' could not be resolved as the expected typed value: Failed to convert resolved value to CommonServiceConfig<TestRunOptions, TestSettings>",
                    loadError = "Failed to convert resolved value to CommonServiceConfig<TestRunOptions, TestSettings>"
                )
            )
        }

        @Test
        fun `reports a certificate reference with an incompatible typed target`() {
            assertThat(validator.validate($$"""
            version: 3
            components:
              runOptions:
                api:
                  openapi:
                    type: test
                    cert:
                      $ref: '#/components/adapters/hooks'
              adapters:
                hooks:
                  preSpecmaticRequestProcessor: hooks/before.sh
            """.trimIndent())).isEqualTo(
                invalidAt(
                    instanceLocation = "/components/runOptions/api/openapi/cert",
                    message = "Reference '#/components/adapters/hooks' could not be resolved as the expected typed value: Failed to convert resolved value to HttpsConfiguration"
                )
            )
        }
    }

    @Nested
    inner class InlineAndNestedValidation {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.config.v3.SpecmaticConfigV3ReferenceValidationTest#nestedReferenceCases")
        fun `validates nested references exactly once at the owning location`(testCase: InvalidReferenceCase) {
            assertThat(validator.validate(testCase.configuration))
                .isEqualTo(invalidAt(testCase.instanceLocation, testCase.message))
        }
    }

    private fun invalidAt(instanceLocation: String, message: String, loadError: String? = null) = ConfigValidationResult.Invalid(
        version = SpecmaticConfigVersion.VERSION_3,
        output = buildList {
            loadError?.let {
                add(ConfigValidationOutput(
                    valid = false,
                    keywordLocation = "",
                    instanceLocation = "",
                    error = "Could not load specification sources: $it",
                ))
            }
            add(ConfigValidationOutput(
                valid = false,
                error = message,
                keywordLocation = "",
                instanceLocation = instanceLocation,
            ))
        }
    )

    companion object {
        private const val MISSING_MESSAGE = "could not be resolved as the expected typed value: Component reference does not exist:"
        data class InvalidReferenceCase(
            val name: String,
            val message: String,
            val configuration: String,
            val instanceLocation: String,
            val loadError: String? = null,
        ) {
            override fun toString(): String = name
        }

        data class ValidReferenceCase(val name: String, val configuration: String) {
            override fun toString(): String = name
        }

        @JvmStatic
        fun missingTypedReferenceCases() = listOf(
            InvalidReferenceCase(
                name = "missing service component",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    $ref: '#/components/services/missing'
                components:
                  services: {}
                """.trimIndent(),
                instanceLocation = "/systemUnderTest/service",
                message = "Reference '#/components/services/missing' $MISSING_MESSAGE #/components/services/missing",
                loadError = "Component reference does not exist: #/components/services/missing",
            ),
            InvalidReferenceCase(
                name = "missing source beside a definition",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    definitions:
                      - definition:
                          source:
                            $ref: '#/components/sources/missing'
                          specs: [orders.yaml]
                components:
                  sources: {}
                """.trimIndent(),
                instanceLocation = "/systemUnderTest/service/definitions/0/definition/source",
                message = "Reference '#/components/sources/missing' $MISSING_MESSAGE #/components/sources/missing",
                loadError = "Component reference does not exist: #/components/sources/missing",
            ),
            InvalidReferenceCase(
                name = "missing settings component",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    definitions: []
                    settings:
                      $ref: '#/components/settings/missing'
                components:
                  settings: {}
                """.trimIndent(),
                instanceLocation = "/systemUnderTest/service/settings",
                message = "Reference '#/components/settings/missing' $MISSING_MESSAGE #/components/settings/missing",
                loadError = "Component reference does not exist: #/components/settings/missing",
            ),
            InvalidReferenceCase(
                name = "missing run options component",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    definitions: []
                    runOptions:
                      $ref: '#/components/runOptions/missing'
                components:
                  runOptions: {}
                """.trimIndent(),
                instanceLocation = "/systemUnderTest/service/runOptions",
                message = "Reference '#/components/runOptions/missing' $MISSING_MESSAGE #/components/runOptions/missing",
            ),
            InvalidReferenceCase(
                name = "missing dictionary component",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    definitions: []
                    data:
                      dictionary:
                        $ref: '#/components/dictionaries/missing'
                components:
                  dictionaries: {}
                """.trimIndent(),
                instanceLocation = "/systemUnderTest/service/data/dictionary",
                message = "Reference '#/components/dictionaries/missing' $MISSING_MESSAGE #/components/dictionaries/missing",
            ),
            InvalidReferenceCase(
                name = "missing example directory component",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    definitions: []
                    data:
                      examples:
                        - $ref: '#/components/examples/missing'
                components:
                  examples: {}
                """.trimIndent(),
                instanceLocation = "/systemUnderTest/service/data/examples/0",
                message = "Reference '#/components/examples/missing' $MISSING_MESSAGE #/components/examples/missing",
            ),
            InvalidReferenceCase(
                name = "missing service data adapter component",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    definitions: []
                    data:
                      adapters:
                        $ref: '#/components/adapters/missing'
                components:
                  adapters: {}
                """.trimIndent(),
                instanceLocation = "/systemUnderTest/service/data/adapters",
                message = "Reference '#/components/adapters/missing' $MISSING_MESSAGE #/components/adapters/missing",
            ),
            InvalidReferenceCase(
                name = "missing proxy adapter component",
                configuration = $$"""
                version: 3
                proxies:
                  - proxy:
                      target: https://one.example.test
                      adapters:
                        $ref: '#/components/adapters/missing'
                components:
                  adapters: {}
                """.trimIndent(),
                instanceLocation = "/proxies/0/proxy/adapters",
                message = "Reference '#/components/adapters/missing' $MISSING_MESSAGE #/components/adapters/missing",
            ),
            InvalidReferenceCase(
                name = "missing Specmatic settings component",
                configuration = $$"""
                version: 3
                specmatic:
                  settings:
                    $ref: '#/components/settings/missing'
                components:
                  settings: {}
                """.trimIndent(),
                instanceLocation = "/specmatic/settings",
                message = "Reference '#/components/settings/missing' $MISSING_MESSAGE #/components/settings/missing",
            ),
            InvalidReferenceCase(
                name = "missing proxy certificate component",
                configuration = $$"""
                version: 3
                proxies:
                  - proxy:
                      target: https://one.example.test
                      cert:
                        $ref: '#/components/certificates/missing'
                components:
                  certificates: {}
                """.trimIndent(),
                instanceLocation = "/proxies/0/proxy/cert",
                message = "Reference '#/components/certificates/missing' $MISSING_MESSAGE #/components/certificates/missing",
            ),
        )

        @JvmStatic
        fun validTypedReferenceCases() = listOf(
            ValidReferenceCase(
                name = "service reference with compatible target",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    $ref: '#/components/services/sut'
                components:
                  services:
                    sut:
                      definitions: []
                """.trimIndent(),
            ),
            ValidReferenceCase(
                name = "source reference with compatible target",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    definitions:
                      - definition:
                          source:
                            $ref: '#/components/sources/local'
                          specs: [orders.yaml]
                components:
                  sources:
                    local:
                      filesystem: {}
                """.trimIndent(),
            ),
        )

        @JvmStatic
        fun nestedReferenceCases() = listOf(
            InvalidReferenceCase(
                name = "reference nested under inline service override",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    $ref: '#/components/services/sut'
                    runOptions:
                      $ref: '#/components/runOptions/missing'
                components:
                  services:
                    sut:
                      definitions: []
                  runOptions: {}
                """.trimIndent(),
                instanceLocation = "/systemUnderTest/service/runOptions",
                message = "Reference '#/components/runOptions/missing' $MISSING_MESSAGE #/components/runOptions/missing",
            ),
            InvalidReferenceCase(
                name = "reference nested inside inline service value",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    definitions: []
                    runOptions:
                      openapi:
                        type: test
                        cert:
                          $ref: '#/components/certificates/missing'
                components:
                  certificates: {}
                """.trimIndent(),
                instanceLocation = "/systemUnderTest/service/runOptions/openapi/cert",
                message = "Reference '#/components/certificates/missing' $MISSING_MESSAGE #/components/certificates/missing",
            ),
            InvalidReferenceCase(
                name = "reference nested inside inline examples exactly once",
                configuration = $$"""
                version: 3
                systemUnderTest:
                  service:
                    definitions: []
                    data:
                      examples:
                        - $ref: '#/components/examples/missing'
                components:
                  examples: {}
                """.trimIndent(),
                instanceLocation = "/systemUnderTest/service/data/examples/0",
                message = "Reference '#/components/examples/missing' $MISSING_MESSAGE #/components/examples/missing",
            ),
        )
    }
}
