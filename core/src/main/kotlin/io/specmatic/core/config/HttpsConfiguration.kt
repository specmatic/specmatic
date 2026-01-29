package io.specmatic.core.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.ObjectNode
import io.specmatic.core.APPLICATION_NAME_LOWER_CASE
import io.specmatic.core.log.logger
import net.minidev.json.annotate.JsonIgnore
import java.io.File

@JsonDeserialize(using = KeyStoreConfiguration.Companion.KeyStoreConfigurationDeserializer::class)
sealed interface KeyStoreConfiguration {
    val password: String?
    val alias: String?

    @JsonIgnore
    fun getFilePath(): String? = (this as? FileBasedConfig)?.file?.canonicalPath

    @JsonIgnore
    fun getDirectoryPath(): String? = (this as? DirectoryBasedConfig)?.directory?.canonicalPath

    fun overrideWith(other: KeyStoreConfiguration?): KeyStoreConfiguration

    data class FileBasedConfig(
        val file: File,
        override val password: String? = null,
        override val alias: String? = null
    ) : KeyStoreConfiguration {
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
        val directory: File,
        override val password: String? = null,
        override val alias: String? = null
    ) : KeyStoreConfiguration {
        override fun overrideWith(other: KeyStoreConfiguration?): KeyStoreConfiguration {
            return when (other) {
                is DirectoryBasedConfig -> copy(directory = other.directory, password = other.password ?: password, alias = other.alias ?: alias)
                is PartialConfig -> copy(password = other.password ?: password, alias = other.alias ?: alias)
                null -> this
                else -> other
            }
        }
    }

    data class PartialConfig(override val password: String? = null, override val alias: String? = null): KeyStoreConfiguration {
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
                val password = node.get("password")?.asText()
                val alias = node.get("alias")?.asText()

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
                    hasFile -> FileBasedConfig(file = File(fileNode.asText()), password = password, alias = alias)
                    else -> DirectoryBasedConfig(directory = File(dirNode.asText()), password = password, alias = alias)
                }
            }
        }
    }
}

data class HttpsConfiguration(private val keyStore: KeyStoreConfiguration? = null, private val keyStorePassword: String? = null) {
    fun keyStoreFile(): String? = keyStore?.getFilePath()

    fun keyStoreDir(): String? = keyStore?.getDirectoryPath()

    fun keyStorePasswordOrDefault(): String = keyStorePassword ?: "forgotten"

    fun keyStoreAliasOrDefault(defaultSuffix: String): String = keyStore?.alias ?: "${APPLICATION_NAME_LOWER_CASE}$defaultSuffix"

    fun keyPasswordOrDefault(): String = keyStore?.password ?: "forgotten"

    fun overrideWith(other: HttpsConfiguration?): HttpsConfiguration {
        if (other == null) return this
        return this.copy(
            keyStorePassword = this.keyStorePassword ?: other.keyStorePassword,
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
                keyStorePassword = opts.keyStorePassword,
                keyStore = when {
                    opts.keyStoreFile != null -> KeyStoreConfiguration.FileBasedConfig(file = File(opts.keyStoreFile), password = opts.keyPassword, alias = opts.keyStoreAlias)
                    opts.keyStoreDir != null -> KeyStoreConfiguration.DirectoryBasedConfig(directory = File(opts.keyStoreDir), password = opts.keyPassword, alias = opts.keyStoreAlias)
                    else -> KeyStoreConfiguration.PartialConfig(password = opts.keyPassword, alias = opts.keyStoreAlias)
                },
            )
        }
    }
}
