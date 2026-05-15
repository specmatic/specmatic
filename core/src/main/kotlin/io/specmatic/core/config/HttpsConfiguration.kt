package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.ObjectNode
import io.specmatic.core.APPLICATION_NAME_LOWER_CASE
import io.specmatic.core.log.logger
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.resolve
import io.specmatic.core.config.v3.resolveOrNull
import io.specmatic.core.config.v3.wrap
import io.specmatic.core.config.v3.wrapOrNull
import java.io.File

@JsonDeserialize(using = KeyStoreConfiguration.Companion.KeyStoreConfigurationDeserializer::class)
@JsonIgnoreProperties("filePath", "directoryPath")
sealed interface KeyStoreConfiguration {
    val password: TemplateOrValue<String>?
    val alias: TemplateOrValue<String>?

    @get:JsonIgnore
    val resolvedPassword: String?
        get() = password.resolveOrNull()

    @get:JsonIgnore
    val resolvedAlias: String?
        get() = alias.resolveOrNull()

    fun getFilePath(): String? = (this as? FileBasedConfig)?.resolvedFile?.let(::File)?.canonicalPath
    fun getDirectoryPath(): String? = (this as? DirectoryBasedConfig)?.resolvedDirectory?.let(::File)?.canonicalPath

    fun overrideWith(other: KeyStoreConfiguration?): KeyStoreConfiguration

    data class FileBasedConfig(
        val file: TemplateOrValue<String>,
        override val password: TemplateOrValue<String>? = null,
        override val alias: TemplateOrValue<String>? = null
    ) : KeyStoreConfiguration {
        @get:JsonIgnore
        val resolvedFile: String
            get() = file.resolve()

        override fun overrideWith(other: KeyStoreConfiguration?): KeyStoreConfiguration {
            return when (other) {
                is FileBasedConfig -> copy(file = other.file, password = other.password ?: password, alias = other.alias ?: alias)
                is PartialConfig -> copy(password = other.password ?: password, alias = other.alias ?: alias)
                null -> this
                else -> other
            }
        }
    }

    data class DirectoryBasedConfig(
        val directory: TemplateOrValue<String>,
        override val password: TemplateOrValue<String>? = null,
        override val alias: TemplateOrValue<String>? = null
    ) : KeyStoreConfiguration {
        @get:JsonIgnore
        val resolvedDirectory: String
            get() = directory.resolve()

        override fun overrideWith(other: KeyStoreConfiguration?): KeyStoreConfiguration {
            return when (other) {
                is DirectoryBasedConfig -> copy(directory = other.directory, password = other.password ?: password, alias = other.alias ?: alias)
                is PartialConfig -> copy(password = other.password ?: password, alias = other.alias ?: alias)
                null -> this
                else -> other
            }
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
    }

    companion object {
        class KeyStoreConfigurationDeserializer : JsonDeserializer<KeyStoreConfiguration>() {
            override fun deserialize(p: JsonParser, ctxt: DeserializationContext): KeyStoreConfiguration? {
                val node = p.codec.readTree<ObjectNode>(p)
                val fileNode = node.get("file")
                val dirNode = node.get("directory")

                val hasFile = fileNode != null && !fileNode.isNull
                val hasDirectory = dirNode != null && !dirNode.isNull
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
                    hasFile -> p.codec.treeToValue(node, FileBasedConfig::class.java)
                    else -> p.codec.treeToValue(node, DirectoryBasedConfig::class.java)
                }
            }
        }
    }
}

data class HttpsConfiguration(
    val keyStore: KeyStoreConfiguration? = null,
    val keyStorePassword: TemplateOrValue<String>? = null,
    val mtlsEnabled: TemplateOrValue<Boolean>? = null
) {
    @get:JsonIgnore
    val resolvedKeyStorePassword: String?
        get() = keyStorePassword.resolveOrNull()

    @get:JsonIgnore
    val resolvedMtlsEnabled: Boolean?
        get() = mtlsEnabled.resolveOrNull()

    fun keyStoreFile(): String? = keyStore?.getFilePath()

    fun keyStoreDir(): String? = keyStore?.getDirectoryPath()

    fun keyStorePasswordOrDefault(): String = keyStore?.resolvedPassword ?: resolvedKeyStorePassword ?: "forgotten"

    fun keyStoreAliasOrDefault(defaultSuffix: String): String = keyStore?.resolvedAlias ?: "${APPLICATION_NAME_LOWER_CASE}$defaultSuffix"

    fun keyPasswordOrDefault(): String = keyStore?.resolvedPassword ?: "forgotten"

    @JsonIgnore
    fun isMtlsEnabled(): Boolean = resolvedMtlsEnabled == true

    fun overrideWith(other: HttpsConfiguration?): HttpsConfiguration {
        if (other == null) return this
        return this.copy(
            keyStorePassword = this.keyStorePassword ?: other.keyStorePassword,
            mtlsEnabled = this.mtlsEnabled ?: other.mtlsEnabled,
            keyStore = this.keyStore.nonNullElse(other.keyStore, KeyStoreConfiguration::overrideWith),
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
                keyStorePassword = opts.keyStorePassword.wrapOrNull(),
                keyStore = when {
                    opts.keyStoreFile != null -> KeyStoreConfiguration.FileBasedConfig(file = opts.keyStoreFile.wrap(), password = opts.keyPassword.wrapOrNull(), alias = opts.keyStoreAlias.wrapOrNull())
                    opts.keyStoreDir != null -> KeyStoreConfiguration.DirectoryBasedConfig(directory = opts.keyStoreDir.wrap(), password = opts.keyPassword.wrapOrNull(), alias = opts.keyStoreAlias.wrapOrNull())
                    else -> KeyStoreConfiguration.PartialConfig(password = opts.keyPassword.wrapOrNull(), alias = opts.keyStoreAlias.wrapOrNull())
                },
            )
        }
    }
}
