@file:JvmName("Utilities")

package `in`.specmatic.core.utilities

import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.InputSource
import `in`.specmatic.core.log.consoleLog
import `in`.specmatic.core.*
import `in`.specmatic.core.Configuration.Companion.globalConfigFileName
import `in`.specmatic.core.azure.AzureAuthCredentials
import `in`.specmatic.core.git.GitCommand
import `in`.specmatic.core.git.SystemGit
import `in`.specmatic.core.log.logger
import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.core.pattern.NullPattern
import `in`.specmatic.core.pattern.NumberPattern
import `in`.specmatic.core.pattern.parsedJSON
import `in`.specmatic.core.value.JSONArrayValue
import `in`.specmatic.core.value.JSONObjectValue
import `in`.specmatic.core.value.StringValue
import `in`.specmatic.core.value.Value
import org.w3c.dom.Node.*
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.system.exitProcess

fun exitWithMessage(message: String): Nothing {
    logger.log(message)
    exitProcess(1)
}

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
    return removeIrrelevantNodes(builder.parse(InputSource(StringReader(xmlData))))
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

fun xmlToString(node: Node): String = xmlToString(DOMSource(node))

private fun xmlToString(domSource: DOMSource, configureTransformer: (Transformer) -> Unit = {}): String {
    val writer = StringWriter()
    val result = StreamResult(writer)
    val tf = TransformerFactory.newInstance()
    val transformer = tf.newTransformer()
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
    configureTransformer(transformer)
    transformer.transform(domSource, result)
    return writer.toString()
}

val contractFilePath: String
    get() = currentDirectory + defaultContractFilePath

private const val currentDirectory = "./"
private const val contractDirectory = "contract"
private const val defaultContractFilePath = "$contractDirectory/service.contract"

fun getTransportCallingCallback(bearerToken: String? = null): TransportConfigCallback {
    return TransportConfigCallback { transport ->
        if (transport is SshTransport) {
            transport.sshSessionFactory = SshdSessionFactory()
        } else if(bearerToken != null && transport is TransportHttp) {
            logger.debug("Setting Authorization header")
            transport.setAdditionalHeaders(mapOf("Authorization" to "Bearer $bearerToken"))
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

fun loadSources(configFilePath: String): List<ContractSource> = loadSources(loadSpecmaticJsonConfig(configFilePath))

fun loadConfigJSON(configFile: File): JSONObjectValue {
    val configJson = try {
        parsedJSON(configFile.readText())
    } catch (e: Throwable) {
        throw ContractException("Error reading the $globalConfigFileName: ${exceptionCauseMessage(e)}",
            exceptionCause = e)
    }

    if (configJson !is JSONObjectValue)
        throw ContractException("The contents of $globalConfigFileName must be a json object")

    return configJson
}

fun loadSources(specmaticConfigJson: SpecmaticConfigJson): List<ContractSource> {
    return specmaticConfigJson.sources.map { source ->
        when(source.provider) {
            SourceProvider.git -> {
                val stubPaths = source.stub ?: emptyList()
                val testPaths = source.test ?: emptyList()

                when (source.repository) {
                    null -> GitMonoRepo(testPaths, stubPaths)
                    else -> GitRepo(source.repository, source.branch, testPaths, stubPaths)
                }
            }
        }
    }
}

fun loadSources(configJson: JSONObjectValue): List<ContractSource> {
    val sources = configJson.jsonObject.getOrDefault("sources", null)

    if(sources !is JSONArrayValue)
        throw ContractException("The \"sources\" key must hold a list of sources.")

    return sources.list.map { source ->
        if (source !is JSONObjectValue)
            throw ContractException("Every element of the sources json array must be a json object, but got this: ${source.toStringLiteral()}")

        when(nativeString(source.jsonObject, "provider")) {
            "git" -> {
                val repositoryURL = nativeString(source.jsonObject, "repository")
                val branch = nativeString(source.jsonObject, "branch")

                val stubPaths = jsonArray(source, "stub")
                val testPaths = jsonArray(source, "test")

                when (repositoryURL) {
                    null -> GitMonoRepo(testPaths, stubPaths)
                    else -> GitRepo(repositoryURL, branch, testPaths, stubPaths)
                }
            }
            else -> throw ContractException("Provider ${nativeString(source.jsonObject, "provider")} not recognised in $globalConfigFileName")
        }
    }
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

fun exitIfDoesNotExist(label: String, filePath: String) {
    if(!File(filePath).exists())
        exitWithMessage("${label.capitalizeFirstChar()} $filePath does not exist")
}

// Used by SpecmaticJUnitSupport users for loading contracts to stub or mock
fun contractStubPaths(): List<ContractPathData> =
        contractFilePathsFrom(globalConfigFileName, DEFAULT_WORKING_DIRECTORY) { source -> source.stubContracts }

fun interface ContractsSelectorPredicate {
    fun select(source: ContractSource): List<String>
}

fun contractTestPathsFrom(configFilePath: String, workingDirectory: String): List<ContractPathData> {
    return contractFilePathsFrom(configFilePath, workingDirectory) { source -> source.testContracts }
}

fun gitRootDir(): String {
    val gitRoot = SystemGit().gitRoot()
    return gitRoot.substring(gitRoot.lastIndexOf('/') + 1)
}

data class ContractPathData(val baseDir: String, val path: String) {
    val relativePath: String
      get() {
          return File(this.path).relativeTo(File(this.baseDir)).path.let {
              when(it[0]) {
                  '/' -> it
                  else -> "/$it"
              }
          }
      }
}

fun contractFilePathsFrom(configFilePath: String, workingDirectory: String, selector: ContractsSelectorPredicate): List<ContractPathData> {
    logger.log("Loading config file $configFilePath")
    val sources = loadSources(configFilePath)

    val contractPathData = sources.flatMap {
        it.loadContracts(selector, workingDirectory, configFilePath)
    }

    logger.debug("Contract file paths in $configFilePath:")
    logger.debug(contractPathData.joinToString(System.lineSeparator()) { it.path }.prependIndent("  "))

    return contractPathData
}

fun reportConfigurationFrom(configFilePath: String) : ReportConfiguration? {
    val specmaticConfigJson = loadSpecmaticJsonConfig(configFilePath)
    return specmaticConfigJson.report
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
