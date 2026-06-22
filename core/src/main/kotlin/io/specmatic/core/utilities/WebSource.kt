package io.specmatic.core.utilities

import io.specmatic.core.CONTRACT_EXTENSIONS
import io.specmatic.core.hostOrAuthority
import io.specmatic.core.git.SystemGit
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import java.io.File
import java.net.ServerSocket
import java.net.URI

class WebSource(override val testContracts: List<ContractSourceEntry>, override val stubContracts: List<ContractSourceEntry>) : ContractSource {
    override val type: String = "web"
    override fun pathDescriptor(path: String): String {
        return path
    }

    override fun directoryRelativeTo(workingDirectory: File): File {
        return File(".")
    }

    override fun getLatest(sourceGit: SystemGit) {
        logger.log("No need to get latest as this source is a URL")
    }

    override fun pushUpdates(sourceGit: SystemGit) {
        logger.log("No need to push updates as this source is a URL")
    }

    fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    override fun loadContracts(
        selector: ContractsSelectorPredicate,
        workingDirectory: String,
        configFilePath: String
    ): List<ContractPathData> {
        val resolvedPath = File(workingDirectory).resolve("web")
        return selector.select(this).map { (url, baseUrl, generative, examples) ->
            val uri = URI.create(url)
            val path = toSpecificationPath(uri)

            val initialDownloadPath = resolvedPath.resolve(path).canonicalFile
            initialDownloadPath.parentFile.mkdirs()

            val actualDownloadPath = download(uri, initialDownloadPath)

            ContractPathData(
                resolvedPath.path,
                actualDownloadPath.path,
                provider = type,
                specificationPath = initialDownloadPath.canonicalPath,
                baseUrl = baseUrl,
                generative = generative,
                exampleDirPaths = examples
            )
        }
    }

    override fun stubDirectoryToContractPath(contractPathDataList: List<ContractPathData>): List<Pair<String, String>> {
        return emptyList()
    }

    internal fun toSpecificationPath(url: URI): String {
        val path = url.hostOrAuthority() + "/" + url.rawPath.removePrefix("/")
        return path
    }

    private fun download(url: URI, specificationFile: File): File {
        val connection = url.toURL().openConnection()
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.connect()

        val inputStream = connection.getInputStream()
        val outputStream = specificationFile.outputStream()

        inputStream.copyTo(outputStream)

        inputStream.close()
        outputStream.close()

        if (specificationFile.extension in CONTRACT_EXTENSIONS)
            return specificationFile

        val text = specificationFile.readText().trim()
        val extension = if (text.startsWith("{")) {
            "json"
        } else {
            "yaml"
        }

        val renamedFile = File(specificationFile.path + ".$extension")
        if (!specificationFile.renameTo(renamedFile))
            throw ContractException("Could not rename file $specificationFile to $renamedFile")

        return renamedFile
    }
}
