package io.specmatic.core.config

import io.specmatic.core.utilities.yamlMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KeyStoreConfigurationOverrideTest {
    @Nested
    inner class OverrideWith {
        @Test
        fun `file based parent is replaced by directory based child`() {
            val parent = KeyStoreConfiguration.FileBasedConfig(
                alias = "parent",
                file = "parent.p12",
                password = "parent-pass",
            )

            assertThat(parent.overrideWith(KeyStoreConfiguration.DirectoryBasedConfig(directory = "child-certs")))
                .isEqualTo(KeyStoreConfiguration.DirectoryBasedConfig(directory = "child-certs"))
        }

        @Test
        fun `directory based parent is replaced by file based child`() {
            val parent = KeyStoreConfiguration.DirectoryBasedConfig(
                alias = "parent",
                password = "parent-pass",
                directory = "parent-certs",
            )

            assertThat(parent.overrideWith(KeyStoreConfiguration.FileBasedConfig(file = "child.p12")))
                .isEqualTo(KeyStoreConfiguration.FileBasedConfig(file = "child.p12"))
        }

        @Test
        fun `partial file based child inherits omitted fields`() {
            val parent = KeyStoreConfiguration.FileBasedConfig(
                alias = "parent",
                file = "parent.p12",
                password = "parent-pass",
            )

            assertThat(parent.overrideWith(KeyStoreConfiguration.FileBasedConfig(file = "child.p12", alias = "child")))
                .isEqualTo(
                    KeyStoreConfiguration.FileBasedConfig(
                        alias = "child",
                        file = "child.p12",
                        password = "parent-pass",
                    ),
                )
        }

        @Test
        fun `partial directory based child inherits omitted fields`() {
            val parent = KeyStoreConfiguration.DirectoryBasedConfig(
                alias = "parent",
                password = "parent-pass",
                directory = "parent-certs",
            )

            assertThat(parent.overrideWith(KeyStoreConfiguration.DirectoryBasedConfig(directory = "child-certs", password = "child-pass")))
                .isEqualTo(
                    KeyStoreConfiguration.DirectoryBasedConfig(
                        alias = "parent",
                        password = "child-pass",
                        directory = "child-certs",
                    ),
                )
        }
    }

    @Nested
    inner class JacksonUpdate {
        @Test
        fun `deserializes a partial key store for type owned merging`() {
            val partial = yamlMapper.readValue(
                """{"password":"child-pass","alias":"child"}""",
                KeyStoreConfiguration::class.java,
            )

            assertThat(partial).isEqualTo(
                KeyStoreConfiguration.PartialConfig(password = "child-pass", alias = "child"),
            )
        }

        @Test
        fun `treats null partial fields as absent`() {
            val partial = yamlMapper.readValue(
                """{"password":null,"alias":"child"}""",
                KeyStoreConfiguration::class.java,
            )

            assertThat(partial).isEqualTo(KeyStoreConfiguration.PartialConfig(alias = "child"))
        }

        @Test
        fun `uses the existing override behavior when updating a deserialized key store`() {
            val parent: KeyStoreConfiguration = KeyStoreConfiguration.FileBasedConfig(
                alias = "parent",
                file = "parent.p12",
                password = "parent-pass",
            )

            val merged = yamlMapper.readerFor(KeyStoreConfiguration::class.java)
                .withValueToUpdate(parent)
                .readValue<KeyStoreConfiguration>("""{"directory":"child-certs"}""")

            assertThat(merged).isEqualTo(
                KeyStoreConfiguration.DirectoryBasedConfig(
                    directory = "child-certs",
                ),
            )
        }
    }
}
