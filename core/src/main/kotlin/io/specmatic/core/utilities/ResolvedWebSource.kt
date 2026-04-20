package io.specmatic.core.utilities

import io.specmatic.core.DEFAULT_WORKING_DIRECTORY
import io.specmatic.core.git.SystemGit
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import java.io.File
import java.net.MalformedURLException
import java.net.URI
import java.net.URL

data class ResolvedWebSource(
    val baseUrl: String,
    override val testContracts: List<ContractSourceEntry>,
    override val stubContracts: List<ContractSourceEntry>
) : ContractSource {
    override val type: String = "web"

    override fun pathDescriptor(path: String): String = resolveSpecUrl(path)

    override fun directoryRelativeTo(workingDirectory: File): File = downloadRoot(workingDirectory, "")

    override fun getLatest(sourceGit: SystemGit) {
        logger.log("No need to get latest as this source is a web base URL")
    }

    override fun pushUpdates(sourceGit: SystemGit) {
        logger.log("No need to push updates as this source is a web base URL")
    }

    override fun loadContracts(
        selector: ContractsSelectorPredicate,
        workingDirectory: String,
        configFilePath: String
    ): List<ContractPathData> {
        val resolvedPath = downloadRoot(File(workingDirectory), configFilePath)

        return selector.select(this).map { entry ->
            val resolvedUrl = URL(resolveSpecUrl(entry.path))
            val initialDownloadPath = localPathFor(resolvedPath, baseUrl, entry.path).canonicalFile
            initialDownloadPath.parentFile.mkdirs()

            val actualDownloadPath = download(resolvedUrl, initialDownloadPath)

            ContractPathData(
                baseDir = resolvedPath.path,
                path = actualDownloadPath.path,
                provider = type,
                specificationPath = entry.path,
                baseUrl = entry.baseUrl,
                generative = entry.generative,
                exampleDirPaths = entry.exampleDirPaths
            )
        }
    }

    override fun stubDirectoryToContractPath(contractPathDataList: List<ContractPathData>): List<Pair<String, String>> {
        return emptyList()
    }

    fun resolveSpecUrl(specPath: String): String {
        validateRelativeSpecPath(specPath)
        return "${baseUrl.removeSuffix("/")}/${specPath.removePrefix("/")}"
    }

    companion object {
        fun validateRelativeSpecPath(specPath: String) {
            try {
                val url = URL(specPath)
                if (url.protocol.isNotBlank()) {
                    throw ContractException("Web source specifications must be relative paths, but got \"$specPath\"")
                }
            } catch (_: MalformedURLException) {
                // Relative paths are not valid URLs and are allowed here.
            }

            val parsed = runCatching { URI(specPath) }.getOrNull()
            if (parsed?.isAbsolute == true) {
                throw ContractException("Web source specifications must be relative paths, but got \"$specPath\"")
            }
        }

        fun localPathFor(rootDir: File, baseUrl: String, specPath: String): File {
            validateRelativeSpecPath(specPath)

            val parsedBaseUrl = try {
                URI(baseUrl)
            } catch (e: Exception) {
                throw ContractException("Invalid web source url \"$baseUrl\": ${e.message}")
            }

            val hostPath = parsedBaseUrl.host ?: throw ContractException("Invalid web source url \"$baseUrl\": missing host")
            val basePath = parsedBaseUrl.path.orEmpty().removePrefix("/").removeSuffix("/")
            val specRelativePath = specPath.removePrefix("/")

            val urlPath = listOf(hostPath, basePath, specRelativePath).filter { it.isNotBlank() }.joinToString("/")
            return rootDir.resolve(urlPath).canonicalFile
        }

        private fun downloadRoot(workingDirectory: File, configFilePath: String): File {
            val configDirectory = configFilePath.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile.parentFile }
            val root = configDirectory?.resolve(DEFAULT_WORKING_DIRECTORY) ?: workingDirectory
            return root.resolve("web")
        }

        private fun download(url: URL, specificationFile: File): File {
            val connection = url.openConnection()
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connect()

            connection.getInputStream().use { inputStream ->
                specificationFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            return specificationFile
        }
    }
}
