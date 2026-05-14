package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.ObjectNode
import io.specmatic.core.APPLICATION_NAME_LOWER_CASE
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
import io.specmatic.core.log.logger
import java.io.File

@JsonDeserialize(using = KeyStoreConfiguration.Companion.KeyStoreConfigurationDeserializer::class)
@JsonIgnoreProperties("filePath", "directoryPath")
sealed interface KeyStoreConfiguration {
    val password: TemplateOrValue<String>?
    val alias: TemplateOrValue<String>?

    fun getFilePath(): String? = (this as? FileBasedConfig)?.file?.resolve()?.let(::File)?.canonicalPath
    fun getDirectoryPath(): String? = (this as? DirectoryBasedConfig)?.directory?.resolve()?.let(::File)?.canonicalPath
    fun getPassword(): String? = password?.resolve()
    fun getAlias(): String? = alias?.resolve()

    fun overrideWith(other: KeyStoreConfiguration?): KeyStoreConfiguration

    data class FileBasedConfig(
        val file: TemplateOrValue<String>,
        override val password: TemplateOrValue<String>? = null,
        override val alias: TemplateOrValue<String>? = null
    ) : KeyStoreConfiguration {
        override fun overrideWith(other: KeyStoreConfiguration?): KeyStoreConfiguration {
            return when (other) {
                is FileBasedConfig -> copy(file = other.file, password = other.password ?: password, alias = other.alias ?: alias)
                is PartialConfig -> copy(password = other.password ?: password, alias = other.alias ?: alias)
                null -> this
                else -> other
            }
        }

        companion object {
            fun from(file: String, password: String? = null, alias: String? = null) = FileBasedConfig(
                file = wrap(file),
                password = password?.let(::wrap),
                alias = alias?.let(::wrap)
            )
        }
    }

    data class DirectoryBasedConfig(
        val directory: TemplateOrValue<String>,
        override val password: TemplateOrValue<String>? = null,
        override val alias: TemplateOrValue<String>? = null
    ) : KeyStoreConfiguration {
        override fun overrideWith(other: KeyStoreConfiguration?): KeyStoreConfiguration {
            return when (other) {
                is DirectoryBasedConfig -> copy(directory = other.directory, password = other.password ?: password, alias = other.alias ?: alias)
                is PartialConfig -> copy(password = other.password ?: password, alias = other.alias ?: alias)
                null -> this
                else -> other
            }
        }

        companion object {
            fun from(directory: String, password: String? = null, alias: String? = null) = DirectoryBasedConfig(
                directory = wrap(directory),
                password = password?.let(::wrap),
                alias = alias?.let(::wrap)
            )
        }
    }

    data class PartialConfig(override val password: TemplateOrValue<String>? = null, override val alias: TemplateOrValue<String>? = null): KeyStoreConfiguration {
        override fun overrideWith(other: KeyStoreConfiguration?): KeyStoreConfiguration {
            return when (other) {
                is FileBasedConfig -> other.copy(password = other.password ?: password, alias = other.alias ?: alias)
                is DirectoryBasedConfig -> other.copy(password = other.password ?: password, alias = other.alias ?: alias)
                is PartialConfig -> copy(password = other.password ?: password, alias = other.alias ?: alias)
                null -> this
            }
        }

        companion object {
            fun from(password: String? = null, alias: String? = null) = PartialConfig(
                password = password?.let(::wrap),
                alias = alias?.let(::wrap)
            )
        }
    }

    companion object {
        class KeyStoreConfigurationDeserializer : JsonDeserializer<KeyStoreConfiguration>() {
            override fun deserialize(p: JsonParser, ctxt: DeserializationContext): KeyStoreConfiguration? {
                val codec = p.codec
                val node = codec.readTree<ObjectNode>(p)

                val hasFile = node.hasNonNull("file")
                val hasDirectory = node.hasNonNull("directory")
                if (!hasFile && !hasDirectory) {
                    ctxt.reportInputMismatch<KeyStoreConfiguration>(
                        KeyStoreConfiguration::class.java,
                        "Invalid keystore configuration: expected either 'file' or 'directory' to be set"
                    )
                }

                if (hasFile && hasDirectory) {
                    logger.log("Both 'file' and 'directory' are set for Keystore configuration, Preferring 'file'; please remove 'directory'")
                }

                return when {
                    hasFile -> codec.treeToValue(node, FileBasedConfig::class.java)
                    else -> codec.treeToValue(node, DirectoryBasedConfig::class.java)
                }
            }
        }
    }
}

data class HttpsConfiguration(
    val keyStore: TemplateOrValue<KeyStoreConfiguration>? = null,
    val keyStorePassword: TemplateOrValue<String>? = null,
    val mtlsEnabled: TemplateOrValue<Boolean>? = null
) {
    fun keyStoreFile(): String? = keyStore?.resolve()?.getFilePath()

    fun keyStoreDir(): String? = keyStore?.resolve()?.getDirectoryPath()

    fun keyStorePasswordOrDefault(): String = keyStorePassword?.resolve() ?: "forgotten"

    fun keyStoreAliasOrDefault(defaultSuffix: String): String = keyStore?.resolve()?.getAlias() ?: "${APPLICATION_NAME_LOWER_CASE}$defaultSuffix"

    fun keyPasswordOrDefault(): String = keyStore?.resolve()?.getPassword() ?: "forgotten"

    @JsonIgnore
    fun isMtlsEnabled(): Boolean = mtlsEnabled?.resolve() == true

    fun overrideWith(other: HttpsConfiguration?): HttpsConfiguration {
        if (other == null) return this
        return this.copy(
            keyStorePassword = this.keyStorePassword ?: other.keyStorePassword,
            mtlsEnabled = this.mtlsEnabled ?: other.mtlsEnabled,
            keyStore = this.keyStore.nonNullElse(other.keyStore) { current, fallback ->
                current.resolve().overrideWith(fallback.resolve()).let(::wrap)
            },
        )
    }

    companion object {
        data class HttpsFromOpts(
            val keyStoreFile: String? = null,
            val keyStoreDir: String? = null,
            val keyStorePassword: String? = null,
            val keyStoreAlias: String? = null,
            val keyPassword: String? = null
        )

        fun default(): HttpsConfiguration { return HttpsConfiguration() }

        fun from(opts: HttpsFromOpts): HttpsConfiguration {
            return HttpsConfiguration(
                keyStorePassword = opts.keyStorePassword?.let(::wrap),
                keyStore = when {
                    opts.keyStoreFile != null -> KeyStoreConfiguration.FileBasedConfig.from(file = opts.keyStoreFile, password = opts.keyPassword, alias = opts.keyStoreAlias)
                    opts.keyStoreDir != null -> KeyStoreConfiguration.DirectoryBasedConfig.from(directory = opts.keyStoreDir, password = opts.keyPassword, alias = opts.keyStoreAlias)
                    else -> KeyStoreConfiguration.PartialConfig.from(password = opts.keyPassword, alias = opts.keyStoreAlias)
                }.let(::wrap),
            )
        }
    }
}
