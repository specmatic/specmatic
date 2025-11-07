package io.specmatic.core

import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import java.io.File

//TODO: This is a temporary solution need to delete this from Kafka and other dependencies
fun getConfigFileName(): String = getConfigFilePath()
fun getConfigFilePath(): String {
    return getStringValue(CONFIG_FILE_PATH)?.takeIf { File(it).exists() }
        ?: getConfigFilePathFromClasspath()?.takeIf { File(it).exists() }
        ?: getConfigFilePath(".")
}

private fun getConfigFilePathFromClasspath(): String? {
    return CONFIG_EXTENSIONS.firstNotNullOfOrNull {
        Configuration::class.java.getResource("/$CONFIG_FILE_NAME_WITHOUT_EXT.$it")?.path
    }
}

fun getConfigFilePath(filePathPrefix: String): String {
    val fileSystemPathSeparator = if (filePathPrefix.endsWith(File.separator)) "" else File.separator

    val configFileNameWithoutExtension = listOf(filePathPrefix, CONFIG_FILE_NAME_WITHOUT_EXT).joinToString(fileSystemPathSeparator)

    return CONFIG_EXTENSIONS.firstNotNullOfOrNull {
        val filePath = "$configFileNameWithoutExtension.$it"
        filePath.takeIf { File(filePath).exists() }
    } ?: "$configFileNameWithoutExtension.$YAML"
}

class Configuration {
    companion object {
        var gitCommand: String = System.getProperty("gitCommandPath") ?: System.getenv("SPECMATIC_GIT_COMMAND_PATH") ?: "git"
        const val TEST_BUNDLE_RELATIVE_PATH = ".${APPLICATION_NAME_LOWER_CASE}_test_bundle"

        var configFilePath: String
            get() = getConfigFilePath()
            set(value) {
                System.setProperty(CONFIG_FILE_PATH, value)
            }

        private const val ALL_IPV4_ADDRESS_ON_LOCAL_MACHINE = "0.0.0.0"
        const val DEFAULT_HTTP_STUB_HOST = ALL_IPV4_ADDRESS_ON_LOCAL_MACHINE
        const val DEFAULT_HTTP_STUB_PORT = "9000"
        const val DEFAULT_PROXY_PORT = DEFAULT_HTTP_STUB_PORT
        const val DEFAULT_PROXY_HOST = ALL_IPV4_ADDRESS_ON_LOCAL_MACHINE
        const val DEFAULT_HTTP_SCHEME = "http"
        const val DEFAULT_BASE_URL = "$DEFAULT_HTTP_SCHEME://$DEFAULT_HTTP_STUB_HOST:$DEFAULT_HTTP_STUB_PORT"
    }
}
