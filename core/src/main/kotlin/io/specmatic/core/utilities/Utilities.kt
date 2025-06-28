@file:JvmName("Utilities")

package io.specmatic.core.utilities

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.Configuration.Companion.DEFAULT_HTTP_STUB_HOST
import io.specmatic.core.Configuration.Companion.configFilePath
import io.specmatic.core.DEFAULT_WORKING_DIRECTORY
import io.specmatic.core.EXAMPLES_DIR_SUFFIX
import io.specmatic.core.HttpRequest
import io.specmatic.core.KeyData
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.azure.AzureAuthCredentials
import io.specmatic.core.git.GitCommand
import io.specmatic.core.git.SystemGit
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.log.consoleDebug
import io.specmatic.core.log.consoleLog
import io.specmatic.core.log.logger
import io.specmatic.core.nativeString
import io.specmatic.core.pattern.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.Node.COMMENT_NODE
import org.w3c.dom.Node.ELEMENT_NODE
import org.w3c.dom.Node.TEXT_NODE
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.net.MalformedURLException
import java.net.URISyntaxException
import java.net.URL
import java.util.concurrent.*
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.system.exitProcess

class SystemExitException(val code: Int, message: String?) : Exception(message)

object SystemExit {
    private val exitFunc: ThreadLocal<(Int, String?) -> Nothing> = ThreadLocal.withInitial { ::defaultExit }

    private fun defaultExit(code: Int, message: String? = null): Nothing {
        message?.let(logger::log)
        exitProcess(code)
    }

    fun exitWith(code: Int, message: String? = null): Nothing {
        exitFunc.get().invoke(code, message)
    }

    fun <T> throwOnExit(block: () -> T): T {
        return try {
            exitFunc.set { code, message -> throw SystemExitException(code, message) }
            block()
        } finally {
            exitFunc.remove()
        }
    }
}

fun exitWithMessage(message: String): Nothing = SystemExit.exitWith(1, "\n$message\n")

fun messageStringFrom(e: Throwable): String {
    val messageStack = exceptionMessageStack(e, emptyList())
    return messageStack.joinToString("; ") { it.trim().removeSuffix(".") }.trim()
}

fun exceptionCauseMessage(e: Throwable): String {
    return when(e) {
        is ContractException -> e.report()
        else -> messageStringFrom(e).ifEmpty {
            "Exception class=${e.javaClass.name}"
        }
    }
}

fun exceptionMessageStack(e: Throwable, messages: List<String>): List<String> {
    val message = e.localizedMessage ?: e.message
    val newMessages = if(message != null) messages.plus(message) else messages

    return when(val cause = e.cause) {
        null -> newMessages
        else -> exceptionMessageStack(cause, newMessages)
    }
}

fun readFile(filePath: String): String {
    return File(filePath).readText().trim()
}

fun parseXML(xmlData: String): Document {
    val builder = newXMLBuilder()
    return removeIrrelevantNodes(builder.parse(InputSource(StringReader(xmlData.removePrefix(UTF_BYTE_ORDER_MARK)))))
}

fun newXMLBuilder(): DocumentBuilder {
    val builderFactory = DocumentBuilderFactory.newInstance()
    val builder = builderFactory.newDocumentBuilder()
    builder.setErrorHandler(null)
    return builder
}

fun removeIrrelevantNodes(document: Document): Document {
    removeIrrelevantNodes(document.documentElement)
    return document
}

fun removeIrrelevantNodes(node: Node): Node {
    if(node.hasChildNodes() && !containsTextContent(node)) {
        val childNodes = 0.until(node.childNodes.length).map { i ->
            node.childNodes.item(i)
        }

        childNodes.forEach {
            if (isEmptyText(it, node) || it.nodeType == COMMENT_NODE)
                node.removeChild(it)
            else if (it.hasChildNodes())
                removeIrrelevantNodes(it)
        }
    }

    return node
}

private fun isEmptyText(it: Node, node: Node) =
    it.nodeType == TEXT_NODE && node.nodeType == ELEMENT_NODE && it.textContent.trim().isBlank()

private fun containsTextContent(node: Node) =
        node.childNodes.length == 1 && node.firstChild.nodeType == TEXT_NODE && node.nodeType == ELEMENT_NODE

fun xmlToString(node: Node): String {
    val writer = StringWriter()
    val result = StreamResult(writer)
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
    transformer.transform(DOMSource(node), result)
    return writer.toString()
}

fun getTransportCallingCallback(bearerToken: String? = null): TransportConfigCallback {
    return TransportConfigCallback { transport ->
        if (transport is SshTransport) {
            transport.sshSessionFactory = SshdSessionFactory()
        } else if(bearerToken != null && transport is TransportHttp) {
            logger.debug("Setting Authorization header")
            transport.additionalHeaders = mapOf("Authorization" to "Bearer $bearerToken")
        }
    }
}

fun strings(list: List<Value>): List<String> {
    return list.map {
        when(it) {
            is StringValue -> it.string
            else -> exitWithMessage("All members of the paths array must be strings, but found one (${it.toStringLiteral()}) which was not")
        }
    }
}

fun loadSources(configFilePath: String): List<ContractSource> = loadSpecmaticConfig(configFilePath).loadSources()

fun loadConfigJSON(configFile: File): JSONObjectValue {
    val configJson = try {
        parsedJSON(configFile.readText())
    } catch (e: Throwable) {
        throw ContractException("Error reading the $configFilePath: ${exceptionCauseMessage(e)}",
            exceptionCause = e)
    }

    if (configJson !is JSONObjectValue)
        throw ContractException("The contents of $configFilePath must be a json object")

    return configJson
}

fun loadSources(configJson: JSONObjectValue): List<ContractSource> {
    val sources = configJson.jsonObject.getOrDefault("sources", null)

    if(sources !is JSONArrayValue)
        throw ContractException("The \"sources\" key must hold a list of sources.")

    return sources.list.map { source ->
        if (source !is JSONObjectValue)
            throw ContractException("Every element of the sources json array must be a json object, but got this: ${source.toStringLiteral()}")

        val type = nativeString(source.jsonObject, "provider")

        when(nativeString(source.jsonObject, "provider")) {
            "git" -> {
                val repositoryURL = nativeString(source.jsonObject, "repository")
                val branch = nativeString(source.jsonObject, "branch")

                val stubPaths = jsonArray(source, "stub")
                val testPaths = jsonArray(source, "test")

                when (repositoryURL) {
                    null -> GitMonoRepo(testPaths.toContractSourceEntries(), stubPaths.toContractSourceEntries(), type)
                    else -> GitRepo(repositoryURL, branch, testPaths.toContractSourceEntries(), stubPaths.toContractSourceEntries(), type)
                }
            }
            "filesystem" -> {
                val directory = nativeString(source.jsonObject, "directory") ?: throw ContractException("The \"directory\" key is required for the local source provider")
                val stubPaths = jsonArray(source, "stub")
                val testPaths = jsonArray(source, "test")

                LocalFileSystemSource(directory, testPaths.toContractSourceEntries(), stubPaths.toContractSourceEntries())
            }
            "web" -> {
                val stubPaths = jsonArray(source, "stub")
                val testPaths = jsonArray(source, "test")
                WebSource(testPaths.toContractSourceEntries(), stubPaths.toContractSourceEntries())
            }
            else -> throw ContractException("Provider ${nativeString(source.jsonObject, "provider")} not recognised in $configFilePath")
        }
    }
}

private fun List<String>.toContractSourceEntries(): List<ContractSourceEntry> {
    return this.map { ContractSourceEntry(it) }
}

internal fun jsonArray(source: JSONObjectValue, key: String): List<String> {
    return when(val value = source.jsonObject[key]) {
        is JSONArrayValue -> value.list.map { it.toStringLiteral() }
        null -> emptyList()
        else -> throw ContractException("Expected $key to be an array")
    }
}

fun createIfDoesNotExist(workingDirectoryPath: String) {
    val workingDirectory = File(workingDirectoryPath)

    if(!workingDirectory.exists()) {
        try {
            workingDirectory.mkdirs()
        } catch (e: Throwable) {
            exitWithMessage(exceptionCauseMessage(e))
        }
    }
}

fun throwExceptionIfDirectoriesAreInvalid(directoryPathsToVerify: List<String>, natureOfDirectoryPaths: String) {
    val invalidDataDirs = directoryPathsToVerify.filter { File(it).exists().not() || File(it).isDirectory.not() }
    if (invalidDataDirs.isNotEmpty()) {
        throw Exception("The following $natureOfDirectoryPaths are invalid: ${invalidDataDirs.joinToString(", ")}. Please provide the valid $natureOfDirectoryPaths.")
    }
}

fun exitIfAnyDoNotExist(label: String, filePaths: List<String>) {
    filePaths.filterNot {contractPath ->
        File(contractPath).exists()
    }.also {
        if(it.isNotEmpty())
            throw ContractException("$label: ${it.joinToString(", ")}")
    }
}

// Used by SpecmaticJUnitSupport users for loading contracts to stub or mock
fun contractStubPaths(configFileName: String): List<ContractPathData> {
    return contractFilePathsFrom(configFileName, DEFAULT_WORKING_DIRECTORY) { source -> source.stubContracts }
}

fun interface ContractsSelectorPredicate {
    fun select(source: ContractSource): List<ContractSourceEntry>
}

fun contractTestPathsFrom(configFilePath: String, workingDirectory: String): List<ContractPathData> {
    return contractFilePathsFrom(configFilePath, workingDirectory) { source -> source.testContracts }
}

fun gitRootDir(): String {
    val gitRoot = SystemGit().gitRoot()
    return gitRoot.substring(gitRoot.lastIndexOf('/') + 1)
}

data class ContractPathData(
    val baseDir: String,
    val path: String,
    val provider: String? = null,
    val repository: String? = null,
    val branch: String? = null,
    val specificationPath: String? = null,
    val baseUrl: String? = null
) {
    companion object {
        fun List<ContractPathData>.specToBaseUrlMap(): Map<String, String?> {
            return this.associate { File(it.path).path to it.baseUrl }
        }
    }
}

fun contractFilePathsFrom(configFilePath: String, workingDirectory: String, selector: ContractsSelectorPredicate): List<ContractPathData> {
    logger.log("Loading config file $configFilePath")
    val sources = loadSources(configFilePath)

    val contractPathData = sources.flatMap {
        it.loadContracts(selector, workingDirectory, configFilePath)
    }

    logger.debug("Spec file paths in $configFilePath:")
    logger.debug(contractPathData.joinToString(System.lineSeparator()) { "- ${it.path}" })

    return contractPathData
}

fun getSystemGit(path: String) : GitCommand {
    return SystemGit(path)
}

fun getSystemGitWithAuth(path: String) : GitCommand {
    return SystemGit(path, authCredentials = AzureAuthCredentials)
}

class UncaughtExceptionHandler: Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread?, e: Throwable?) {
        if(e != null)
            consoleLog(logger.ofTheException(e))

        exitProcess(1)
    }
}

internal fun withNullPattern(resolver: Resolver): Resolver {
    return resolver.copy(newPatterns = resolver.newPatterns.plus("(empty)" to NullPattern))
}

internal fun withNumberType(resolver: Resolver) =
        resolver.copy(newPatterns = resolver.newPatterns.plus("(number)" to NumberPattern()))

fun String.capitalizeFirstChar() = this.replaceFirstChar { it.uppercase() }

fun saveJsonFile(jsonString: String, path: String, fileName: String) {
    val directory = File(path)
    directory.mkdirs()
    File(directory, fileName).writeText(jsonString)
}

fun readEnvVarOrProperty(envVarName: String, propertyName: String): String? {
    return System.getenv(envVarName) ?: System.getProperty(propertyName)
}

fun examplesDirFor(openApiFilePath: String, alternateSuffix: String): File {
    val examplesDir = getExamplesDir(openApiFilePath, EXAMPLES_DIR_SUFFIX)
    return if (examplesDir.isDirectory)
        examplesDir
    else
        getExamplesDir(openApiFilePath, alternateSuffix)
}

private fun getExamplesDir(openApiFilePath: String, suffix: String): File =
    File(openApiFilePath).canonicalFile.let {
        it.parentFile.resolve("${it.parent}/${it.nameWithoutExtension}$suffix")
    }

fun nullOrExceptionString(fn: () -> Result): String? {
    return try {
        val result = fn()
        if(result is Result.Failure)
            return result.reportString()

        return null
    }
    catch(t: Throwable) {
        logger.exceptionString(t)
    }
}

fun uniqueNameForApiOperation(httpRequest: HttpRequest, baseURL: String, responseStatus: Int): String {
    val (method, path, headers) = httpRequest
    val contentType = if(method == "PATCH")
        "_" + headers[CONTENT_TYPE].orEmpty().replace("/", "_")
    else ""
    val formattedPath = path?.replace(baseURL, "")
        ?.replace("/", "_")
        ?.drop(1)
        .orEmpty()
    if (formattedPath.isEmpty()) return "${method}_${responseStatus}"
    return "${formattedPath}_${method}_${responseStatus}$contentType"
}

fun consolePrintableURL(host: String, port: Int, keyStoreData: KeyData? = null): String {
    val protocol = keyStoreData?.let { "https" } ?: "http"
    val displayableHost = if (host == DEFAULT_HTTP_STUB_HOST) "localhost" else host
    return "$protocol://$displayableHost:$port"
}

fun <T> runWithTimeout(timeout: Long, task: Callable<T>): T {
    val unit = TimeUnit.MILLISECONDS

    val executor = Executors.newSingleThreadExecutor()
    val future = executor.submit(task)

    try {
        return future.get(timeout, unit)
    } catch (e: TimeoutException) {
        future.cancel(true)
        throw e
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } finally {
        executor.shutdown() // Shut down the executor
    }
}

enum class URIValidationResult(val message: String) {
    URIParsingError("Please specify a valid URL in 'scheme://host[:port][path]' format"),
    InvalidURLSchemeError("Please specify a valid scheme / protocol (http or https)"),
    MissingHostError("Please specify a valid host name"),
    InvalidPortError("Please specify a valid port number"),
    Success("This URL is valid");
}

fun validateTestOrStubUri(uri: String): URIValidationResult {
    val parsedURI = try {
        URL(uri).toURI()
    } catch (e: URISyntaxException) {
        consoleDebug(e)
        return URIValidationResult.URIParsingError
    } catch(e: MalformedURLException) {
        consoleDebug(e)
        return URIValidationResult.URIParsingError
    }

    val validProtocols = listOf("http", "https")
    val validPorts = 1..65535

    return when {
        !validProtocols.contains(parsedURI.scheme) -> URIValidationResult.InvalidURLSchemeError
        parsedURI.host.isNullOrBlank() -> URIValidationResult.MissingHostError
        parsedURI.port != -1 && !validPorts.contains(parsedURI.port) -> URIValidationResult.InvalidPortError
        else -> URIValidationResult.Success
    }
}
