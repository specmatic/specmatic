package application

import io.specmatic.core.DEFAULT_TIMEOUT_IN_MILLISECONDS
import io.specmatic.core.KeyData
import io.specmatic.core.ProxyConfig
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.LoggingConfiguration.Companion.LoggingFromOpts
import io.specmatic.core.getConfigFilePath
import io.specmatic.core.log.StringLog
import io.specmatic.core.log.configureLogging
import io.specmatic.core.log.consoleLog
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.consolePrintableURL
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.utilities.exitWithMessage
import io.specmatic.license.core.cli.Category
import io.specmatic.proxy.Proxy
import io.specmatic.stub.SpecmaticConfigSource
import picocli.CommandLine.*
import java.io.File
import java.lang.Thread.sleep
import java.util.concurrent.Callable

@Command(
    name = "proxy",
    mixinStandardHelpOptions = true,
    description = ["Proxies requests to the specified target and converts the result into contracts and stubs"],
)
@Category("Specmatic core")
open class ProxyCommand : Callable<Unit> {
    @Option(names = ["--target"], description = ["Base URL of the target to proxy"], required = false)
    var targetBaseURL: String? = null

    @Option(names = ["--host"], description = ["Host for the proxy"])
    var host: String? = null

    @Option(names = ["--port"], description = ["Port for the proxy"])
    var port: Int? = null

    @Parameters(description = ["Store data from the proxy interactions into this dir"], index = "0", arity = "0..1")
    var proxyDumpDirectory: File? = null

    @Option(names = ["--httpsKeyStore"], description = ["Run the proxy on https using a key in this store"])
    var keyStoreFile: String? = null

    @Option(names = ["--httpsKeyStoreDir"], description = ["Run the proxy on https, create a store named specmatic.jks in this directory"])
    var keyStoreDir: String? = null

    @Option(names = ["--httpsKeyStorePassword"], description = ["Run the proxy on https, password for pre-existing key store"])
    var keyStorePassword: String? = null

    @Option(names = ["--httpsKeyAlias"], description = ["Run the proxy on https using a key by this name"])
    var keyStoreAlias: String? = null

    @Option(names = ["--httpsPassword"], description = ["Key password if any"])
    var keyPassword: String? = null

    @Option(
        names= ["--filter"],
        hidden = true,
        description = [
            """Filter tests matching the specified filtering criteria

You can filter tests based on the following keys:
- `METHOD`: HTTP methods (e.g., GET, POST)
- `PATH`: Request paths (e.g., /users, /product)
- `STATUS`: HTTP response status codes (e.g., 200, 400)

You can find all available filters and their usage at:
https://docs.specmatic.io/documentation/contract_tests.html#supported-filters--operators"""
        ],
        required = false,
    )
    var filter: String = ""


    @Option(names = ["--debug"], description = ["Write verbose logs to console for debugging"])
    var debugLog: Boolean? = null

    @Option(names = ["--timeout-in-ms"], description = ["Response Timeout in milliseconds, Defaults to $DEFAULT_TIMEOUT_IN_MILLISECONDS"])
    var timeoutInMs: Long? = null

    var proxy: Proxy? = null

    private val specmaticConfigSource = if (File(getConfigFilePath()).exists()) {
        logger.log("Loading configuration from ${getConfigFilePath()}")
        SpecmaticConfigSource.fromPath(getConfigFilePath())
    } else {
        logger.log("No specmatic.yaml found in current directory")
        SpecmaticConfigSource.None
    }

    override fun call() {
        configureLogging(LoggingFromOpts(debug = debugLog))
        val specmaticConfigLoaded = specmaticConfigSource.load().config
        val fromCli = HttpsConfiguration.Companion.HttpsFromOpts(keyStoreFile, keyStoreDir, keyStorePassword, keyStoreAlias, keyPassword)
        val fromConfig = specmaticConfigLoaded.getProxyConfig()?.getHttpsConfig()
        val keyStoreData = CertInfo(fromCli, fromConfig).getHttpsCert(aliasSuffix = "proxy")

        proxy = createProxyServer(specmaticConfigLoaded, keyStoreData)
        addShutdownHook()
        logger.boundary()
        while(true) sleep(10000)
    }

    private fun createProxyServer(specmaticConfig: SpecmaticConfig, keyStoreData: KeyData?): Proxy {
        val configProxy = specmaticConfig.getProxyConfig() ?: ProxyConfig(target = "")
        val effectiveHost = host ?: configProxy.getHostOrDefault()
        val effectivePort = port.takeUnless { it == 0 || it == -1 } ?: configProxy.getPortOrDefault()
        val effectiveOutDir = proxyDumpDirectory ?: configProxy.getRecordingsDirectory()
        val effectiveTimeout = timeoutInMs ?: configProxy.getTimeoutInMillisecondsOrDefault()
        val effectiveTarget = targetBaseURL?.takeUnless(String::isBlank) ?: configProxy.getTargetUrl {
            throw ContractException("Proxy targetURL must be provided through CLI or Specmatic Config")
        }
        return createProxy(
            filter = filter,
            host = effectiveHost,
            port = effectivePort,
            outDir = effectiveOutDir,
            timeout = effectiveTimeout,
            target = effectiveTarget,
            keyData = keyStoreData,
            specmaticConfigSource = specmaticConfigSource,
        )
    }

    protected open fun createProxy(filter: String, host: String, port: Int, outDir: File, timeout: Long, target: String, keyData: KeyData?, specmaticConfigSource: SpecmaticConfigSource): Proxy {
        val startupLogs = "Proxy server is running on ${consolePrintableURL(host, port, keyData)}. Ctrl + C to stop."
        validatedProxySettings(targetBaseURL, outDir.canonicalPath)
        return Proxy(
            filter = filter,
            host = host,
            port = port,
            requestObserver = null,
            keyData = keyData,
            baseURL = target,
            timeoutInMilliseconds = timeout,
            specmaticConfigSource = specmaticConfigSource,
            proxySpecmaticDataDir = outDir.canonicalPath,
        ).also { consoleLog(StringLog(startupLogs)) }
    }

    private fun validatedProxySettings(unknownProxyTarget: String?, proxySpecmaticDataDir: String?) {
        if(unknownProxyTarget == null && proxySpecmaticDataDir == null) return

        if(unknownProxyTarget != null && proxySpecmaticDataDir != null) {
            val dataDirFile = File(proxySpecmaticDataDir)
            if(!dataDirFile.exists()) {
                try {
                    dataDirFile.mkdirs()
                } catch (e: Throwable) {
                    exitWithMessage(exceptionCauseMessage(e))
                }
            } else {
                if(dataDirFile.listFiles()?.isNotEmpty() == true) {
                    exitWithMessage("This data directory $proxySpecmaticDataDir must be empty if it exists")
                }
            }
        }
    }

    protected open fun addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                try {
                    println("Shutting down stub servers")
                    proxy?.close()
                } catch (e: InterruptedException) {
                    currentThread().interrupt()
                } catch (e: Throwable) {
                    logger.log(e)
                }
            }
        })
    }
}
