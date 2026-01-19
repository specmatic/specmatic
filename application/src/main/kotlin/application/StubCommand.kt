package application

import io.specmatic.core.*
import io.specmatic.core.Configuration.Companion.DEFAULT_HTTP_STUB_HOST
import io.specmatic.core.Configuration.Companion.DEFAULT_HTTP_STUB_PORT
import io.specmatic.core.config.Switch
import io.specmatic.core.filters.ExpressionStandardizer
import io.specmatic.core.filters.HttpStubFilterContext
import io.specmatic.core.filters.ScenarioMetadataFilter
import io.specmatic.core.log.*
import io.specmatic.core.utilities.*
import io.specmatic.core.utilities.ContractPathData.Companion.specToBaseUrlMap
import io.specmatic.core.loadSpecmaticConfigOrNull
import io.specmatic.core.utilities.Flags.Companion.MATCH_BRANCH
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_BASE_URL
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_STUB_DELAY
import io.specmatic.license.core.cli.Category
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.ContractStub
import io.specmatic.stub.HttpClientFactory
import io.specmatic.stub.endPointFromHostAndPort
import io.specmatic.stub.listener.MockEventListener
import picocli.CommandLine.*
import java.io.File
import java.util.concurrent.Callable

@Command(
    name = "stub",
    aliases = ["virtualize"],
    mixinStandardHelpOptions = true,
    description = ["Start a stub server with contract"]
)
@Category("Specmatic core")
class StubCommand(
    private val httpStubEngine: HTTPStubEngine = HTTPStubEngine(),
    private val stubLoaderEngine: StubLoaderEngine = StubLoaderEngine(),
    private val specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
    private val watchMaker: WatchMaker = WatchMaker(),
    private val httpClientFactory: HttpClientFactory = HttpClientFactory()
) : Callable<Unit> {
    var httpStub: ContractStub? = null

    @Parameters(arity = "0..*", description = ["Contract file paths", "Spec file paths"])
    var contractPaths: List<String> = mutableListOf()

    @Option(names = ["--data", "--examples"], description = ["Directories containing JSON examples"], required = false)
    var exampleDirs: List<String> = mutableListOf()

    @Option(names = ["--host"], description = ["Host for the http stub"], defaultValue = DEFAULT_HTTP_STUB_HOST)
    lateinit var host: String

    @Option(names = ["--port"], description = ["Port for the http stub"], defaultValue = DEFAULT_HTTP_STUB_PORT)
    var port: Int = 0

    @Option(names = ["--strict"], description = ["Start HTTP stub in strict mode"], required = false)
    var strictMode: Boolean? = null

    @Option(names = ["--passThroughTargetBase"], description = ["All requests that did not match a url in any contract will be forwarded to this service"])
    var passThroughTargetBase: String = ""

    @Option(names = ["--httpsKeyStore"], description = ["Run the proxy on https using a key in this store"])
    var keyStoreFile = ""

    @Option(names = ["--httpsKeyStoreDir"], description = ["Run the proxy on https, create a store named $APPLICATION_NAME_LOWER_CASE.jks in this directory"])
    var keyStoreDir = ""

    @Option(names = ["--httpsKeyStorePassword"], description = ["Run the proxy on https, password for pre-existing key store"])
    var keyStorePassword = "forgotten"

    @Option(names = ["--httpsKeyAlias"], description = ["Run the proxy on https using a key by this name"])
    var keyStoreAlias = "${APPLICATION_NAME_LOWER_CASE}proxy"

    @Option(names = ["--httpsPassword"], description = ["Key password if any"])
    var keyPassword = "forgotten"


    @Option(
        names= ["--filter"],
        description = [
            """Filter tests matching the specified filtering criteria

You can filter tests based on the following keys:
- `METHOD`: HTTP methods (e.g., GET, POST)
- `PATH`: Request paths (e.g., /users, /product)
- `STATUS`: HTTP response status codes (e.g., 200, 400)

You can find all available filters and their usage at:
https://docs.specmatic.io/documentation/contract_tests.html#supported-filters--operators"""
        ],
        required = false
    )
    var filter: String = ""

    @Option(names = ["--debug"], description = ["Debug logs"])
    var verbose = false

    @Option(names = ["--config"], description = ["Configuration file name ($APPLICATION_NAME_LOWER_CASE.json by default)"])
    var configFileName: String? = null

    @Option(names = ["--textLog"], description = ["Directory in which to write a text log"])
    var textLog: String? = null

    @Option(names = ["--jsonLog"], description = ["Directory in which to write a JSON log"])
    var jsonLog: String? = null

    @Option(names = ["--jsonConsoleLog"], description = ["Console log should be in JSON format"])
    var jsonConsoleLog: Boolean = false

    @Option(names = ["--noConsoleLog"], description = ["Don't log to console"])
    var noConsoleLog: Boolean = false

    @Option(names = ["--logPrefix"], description = ["Prefix of log file"])
    var logPrefix: String = "specmatic"

    @Option(names = ["--delay-in-ms"], description = ["Stub response delay in milliseconds"])
    var delayInMilliseconds: Long = 0

    @Option(names = ["--graceful-restart-timeout-in-ms"], description = ["Time to wait for the server to stop before starting it again"])
    var gracefulRestartTimeoutInMs: Long = 1000

    @Option(names = ["--hot-reload"], description = ["Time to wait for the server to stop before starting it again"])
    var hotReload: Switch? = null

    @Option(
        names = ["--match-branch"],
        description = ["Use the current branch name for contract source branch when not on default branch"],
        required = false
    )
    var useCurrentBranchForCentralRepo: Boolean = false

    @Option(names = ["--lenient"], description = ["Parse the OpenAPI Specification with leniency"], required = false)
    var lenientMode: Boolean = false

    private var contractSources: List<ContractPathData> = emptyList()

    var specmaticConfigPath: String? = null

    var listeners: List<MockEventListener> = emptyList()

    override fun call() {
        if (delayInMilliseconds > 0) {
            System.setProperty(SPECMATIC_STUB_DELAY, delayInMilliseconds.toString())
        }

        val logPrinters = configureLogPrinters()

        logger = if(verbose)
            Verbose(CompositePrinter(logPrinters))
        else
            NonVerbose(CompositePrinter(logPrinters))

        if (configFileName != null) {
            Configuration.configFilePath = configFileName as String
        } else {
            Configuration.configFilePath = getConfigFilePath()
        }

        val certInfo = CertInfo(keyStoreFile, keyStoreDir, keyStorePassword, keyStoreAlias, keyPassword)
        port = when (isDefaultPort(port)) {
            true -> if (portIsInUse(host, port)) findRandomFreePort() else port
            false -> port
        }
        val baseUrl = endPointFromHostAndPort(host, port, keyData = certInfo.getHttpsCert())
        System.setProperty(SPECMATIC_BASE_URL, baseUrl)

        try {
            val configMatchBranch = loadSpecmaticConfigOrNull(
                Configuration.configFilePath,
                explicitlySpecifiedByUser = configFileName != null
            )?.getMatchBranch() ?: false
            val matchBranchEnabled = useCurrentBranchForCentralRepo || Flags.getBooleanValue(MATCH_BRANCH, false) || configMatchBranch
            
            contractSources = when (contractPaths.isEmpty()) {
                true -> {
                    specmaticConfigPath = File(Configuration.configFilePath).canonicalPath

                    logger.debug("Using the spec paths configured for stubs in the configuration file '$specmaticConfigPath'")
                    specmaticConfig.contractStubPathData(matchBranchEnabled).map { it.copy(lenientMode = lenientMode) }
                }
                else -> contractPaths.map {
                    ContractPathData("", it, lenientMode = lenientMode)
                }
            }
            contractPaths = contractSources.map { it.path }
            exitIfAnyDoNotExist("The following specifications do not exist", contractPaths)
            validateContractFileExtensions(contractPaths)
            startServer()

            if (httpStub != null) {
                addShutdownHook()

                val configuredHotReload = configuredHotReload()

                when (configuredHotReload) {
                    Switch.enabled -> {
                        val watcher = watchMaker.make(contractPaths.plus(exampleDirs))
                        watcher.watchForChanges {
                            restartServer()
                        }
                    }
                    Switch.disabled -> {
                        Thread.currentThread().join()
                    }
                }
            }
        } catch (e: Throwable) {
            consoleLog(e)
        }
    }

    private fun configuredHotReload(): Switch =
        hotReload
            ?: loadSpecmaticConfigOrDefault(specmaticConfigPath ?: getConfigFilePath()).getHotReload()
            ?: Switch.enabled

    private fun configuredStrictMode(): Boolean =
        strictMode
            ?: loadSpecmaticConfigOrDefault(specmaticConfigPath ?: getConfigFilePath()).getStubStrictMode()
            ?: false

    private fun configureLogPrinters(): List<LogPrinter> {
        val consoleLogPrinter = configureConsoleLogPrinter()
        val textLogPrinter = configureTextLogPrinter()
        val jsonLogPrinter = configureJSONLogPrinter()

        return consoleLogPrinter.plus(textLogPrinter).plus(jsonLogPrinter)
    }

    private fun configureConsoleLogPrinter(): List<LogPrinter> {
        if (noConsoleLog)
            return emptyList()

        if (jsonConsoleLog)
            return listOf(JSONConsoleLogPrinter)

        return listOf(ConsolePrinter)
    }

    private fun configureJSONLogPrinter(): List<LogPrinter> = jsonLog?.let {
        listOf(JSONFilePrinter(LogDirectory(it, logPrefix, "-json.log")))
    } ?: emptyList()

    private fun configureTextLogPrinter(): List<LogPrinter> = textLog?.let {
        listOf(TextFilePrinter(LogDirectory(it, logPrefix, ".log")))
    } ?: emptyList()

    private fun startServer() {
        val workingDirectory = WorkingDirectory()
        val resolvedStrictMode = configuredStrictMode()
        if(resolvedStrictMode) throwExceptionIfDirectoriesAreInvalid(exampleDirs, "example directories")
        val stubData = stubLoaderEngine.loadStubs(
            contractPathDataList = contractSources,
            dataDirs = exampleDirs,
            specmaticConfigPath = specmaticConfigPath,
            strictMode = resolvedStrictMode,
        )

        logStubLoadingSummary(stubData)

        val filteredStubData = stubData.mapNotNull { (feature, scenarioStubs) ->
            val metadataFilter = ScenarioMetadataFilter.from(filter)
            val filteredScenarios = ScenarioMetadataFilter.filterUsing(
                feature.scenarios.asSequence(),
                metadataFilter,
            ).toList()
            val stubFilterExpression = ExpressionStandardizer.filterToEvalEx(filter)
            val filteredStubScenario = scenarioStubs.filter { it ->
                stubFilterExpression.with("context", HttpStubFilterContext(it)).evaluate().booleanValue
            }
            if (filteredScenarios.isNotEmpty()) {
                val updatedFeature = feature.copy(scenarios = filteredScenarios)
                updatedFeature to filteredStubScenario
            } else null
        }

        if (filter != "" && filteredStubData.isEmpty()) {
            consoleLog(StringLog("FATAL: No stubs found for the given filter: $filter"))
            return
        }

        val certInfo = CertInfo(keyStoreFile, keyStoreDir, keyStorePassword, keyStoreAlias, keyPassword)

        if (configuredHotReload() == Switch.disabled) {
            val warningMessage =
                "WARNING: Hot reload has been disabled. Specmatic will not restart the stub server automatically when the specifications or examples change."
            logger.boundary()
            logger.log(warningMessage)
            logger.boundary()
        }

        httpStub = httpStubEngine.runHTTPStub(
            stubs = filteredStubData,
            host = host,
            port = port,
            certInfo = certInfo,
            strictMode = resolvedStrictMode,
            passThroughTargetBase = passThroughTargetBase,
            specmaticConfigPath = specmaticConfigPath,
            httpClientFactory = httpClientFactory,
            workingDirectory = workingDirectory,
            gracefulRestartTimeoutInMs = gracefulRestartTimeoutInMs,
            specToBaseUrlMap = contractSources.specToBaseUrlMap(),
            listeners = listeners
        )

        LogTail.storeSnapshot()
    }

    private fun isDefaultPort(port:Int): Boolean {
        return DEFAULT_HTTP_STUB_PORT == port.toString()
    }

    private fun restartServer() {
        consoleLog(StringLog("Stopping servers..."))
        try {
            stopServer()
            consoleLog(StringLog("Stopped."))
        } catch (e: Throwable) {
            consoleLog(e,"Error stopping server")
        }

        try { startServer() } catch (e: Throwable) {
            consoleLog(e, "Error starting server")
        }
    }

    private fun stopServer() {
        httpStub?.close()
        httpStub = null
    }

    private fun addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                try {
                    consoleLog(StringLog("Shutting down stub servers"))
                    httpStub?.close()
                } catch (e: InterruptedException) {
                    currentThread().interrupt()
                }
            }
        })
    }

    private fun logStubLoadingSummary(stubData: List<Pair<Feature, List<ScenarioStub>>>) {
        val totalStubs = stubData.sumOf { it.second.size }

        if (verbose) {
            logger.boundary()
            consoleLog(StringLog("Loaded stubs:"))
            stubData.forEach { (feature, stubs) ->
                val featureName = feature.specification ?: feature.path
                stubs.forEach { stub ->
                    val stubDescription = buildStubDescription(stub)
                    consoleLog(StringLog("  - $featureName: $stubDescription"))
                }
            }
            consoleLog(StringLog("Total: $totalStubs example(s) loaded"))
        }

        logger.boundary()
    }
    
    private fun buildStubDescription(stub: ScenarioStub): String {
        val request = stub.partial?.request ?: stub.request
        val method = request.method
        val path = request.path
        return "$method $path"
    }
}

internal fun validateContractFileExtensions(contractPaths: List<String>) {
    contractPaths.map(::File).filter {
        it.isFile && it.extension !in CONTRACT_EXTENSIONS
    }.let {
        if (it.isNotEmpty()) {
            val files = it.joinToString("\n") { file -> file.path }
            exitWithMessage("The following files do not end with $CONTRACT_EXTENSIONS and cannot be used:\n$files")
        }
    }
}
