package application

import io.specmatic.core.*
import io.specmatic.core.Configuration.Companion.DEFAULT_HTTP_STUB_HOST
import io.specmatic.core.Configuration.Companion.DEFAULT_HTTP_STUB_PORT
import io.specmatic.core.SpecmaticConfig.Companion.orDefault
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.LoggingConfiguration.Companion.LoggingFromOpts
import io.specmatic.core.config.Switch
import io.specmatic.core.filters.ExpressionStandardizer
import io.specmatic.core.filters.HttpStubFilterContext
import io.specmatic.core.filters.ScenarioMetadataFilter
import io.specmatic.core.log.*
import io.specmatic.core.utilities.*
import io.specmatic.core.utilities.ContractPathData.Companion.specToBaseUrlMap
import io.specmatic.core.loadSpecmaticConfigOrNull
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_BASE_URL
import io.specmatic.license.core.cli.Category
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.ContractStub
import io.specmatic.stub.HttpClientFactory
import io.specmatic.stub.endPointFromHostAndPort
import io.specmatic.stub.extractHost
import io.specmatic.stub.extractPort
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
    var keyStoreFile: String? = null

    @Option(names = ["--httpsKeyStoreDir"], description = ["Run the proxy on https, create a store named $APPLICATION_NAME_LOWER_CASE.jks in this directory"])
    var keyStoreDir: String? = null

    @Option(names = ["--httpsKeyStorePassword"], description = ["Run the proxy on https, password for pre-existing key store"])
    var keyStorePassword: String? = null

    @Option(names = ["--httpsKeyAlias"], description = ["Run the proxy on https using a key by this name"])
    var keyStoreAlias: String? = null

    @Option(names = ["--httpsPassword"], description = ["Key password if any"])
    var keyPassword: String? = null

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
    var filter: String? = null

    @Option(names = ["--debug"], description = ["Debug logs"])
    var verbose: Boolean? = null

    @Option(names = ["--config"], description = ["Configuration file name ($APPLICATION_NAME_LOWER_CASE.json by default)"])
    var configFileName: String? = null

    @Option(names = ["--textLog"], description = ["Directory in which to write a text log"])
    var textLogDir: File? = null

    @Option(names = ["--jsonLog"], description = ["Directory in which to write a JSON log"])
    var jsonLogDir: File? = null

    @Option(names = ["--jsonConsoleLog"], description = ["Console log should be in JSON format"])
    var jsonConsoleLog: Boolean? = null

    @Option(names = ["--noConsoleLog"], description = ["Don't log to console"])
    var noConsoleLog: Boolean? = null

    @Option(names = ["--logPrefix"], description = ["Prefix of log file"])
    var logPrefix: String? = null

    @Option(names = ["--delay-in-ms"], description = ["Stub response delay in milliseconds"])
    var delayInMilliseconds: Long? = null

    @Option(names = ["--graceful-restart-timeout-in-ms"], description = ["Time to wait for the server to stop before starting it again"])
    var gracefulRestartTimeoutInMs: Long? = null

    @Option(names = ["--hot-reload"], description = ["Time to wait for the server to stop before starting it again"])
    var hotReload: Switch? = null

    @Option(
        names = ["--match-branch"],
        description = ["Use the current branch name for contract source branch when not on default branch"],
        required = false
    )
    var useCurrentBranchForCentralRepo: Boolean? = null

    @Option(names = ["--lenient"], description = ["Parse the OpenAPI Specification with leniency"], required = false, hidden = true)
    var lenientMode: Boolean? = null

    private var contractSources: List<ContractPathData> = emptyList()

    var specmaticConfigPath: String? = null

    var listeners: List<MockEventListener> = emptyList()
    var registerShutdownHook: Boolean = true

    private val specmaticConfiguration: io.specmatic.core.SpecmaticConfig by lazy(LazyThreadSafetyMode.NONE) {
        if (configFileName != null) Configuration.configFilePath = configFileName as String
        val specmaticConfigPath = File(Configuration.configFilePath).canonicalPath
        val config = loadSpecmaticConfigOrNull(specmaticConfigPath, explicitlySpecifiedByUser = configFileName != null).orDefault()
        val sourcesUpdated = config.mapSources { source -> source.copy(matchBranch = useCurrentBranchForCentralRepo ?: source.matchBranch) }
        val stubConfigUpdated = sourcesUpdated.withStubModes(strictMode = strictMode).withStubFilter(filter = filter)
        delayInMilliseconds?.let(stubConfigUpdated::withGlobalMockDelay) ?: config
    }

    private val keyData: KeyData? by lazy(LazyThreadSafetyMode.NONE) {
        val fromCli = HttpsConfiguration.Companion.HttpsFromOpts(keyStoreFile, keyStoreDir, keyStorePassword, keyStoreAlias, keyPassword)
        val fromConfig = specmaticConfiguration.getStubHttpsConfiguration()
        CertInfo(fromCli, fromConfig).getHttpsCert(aliasSuffix = "mock")
    }

    override fun call() {
        configureLogging(LoggingFromOpts(
            debug = verbose,
            textLogDirectory = textLogDir,
            textConsoleLog = noConsoleLog?.let { !it },
            jsonConsoleLog = jsonConsoleLog,
            jsonLogDirectory = jsonLogDir,
            logPrefix = logPrefix
        ))

        val defaultHost = DEFAULT_HTTP_STUB_HOST
        val defaultPort = DEFAULT_HTTP_STUB_PORT.toInt()
        if (host == defaultHost && port == defaultPort) {
            resolveHostAndPortFromBaseUrl(specmaticConfiguration.getDefaultBaseUrl())?.let { (resolvedHost, resolvedPort) ->
                host = resolvedHost
                port = resolvedPort
            }
        }

        port = when (isDefaultPort(port)) {
            true -> if (portIsInUse(host, port)) findRandomFreePort() else port
            false -> port
        }
        val baseUrl = endPointFromHostAndPort(host, port, keyData = keyData)
        System.setProperty(SPECMATIC_BASE_URL, baseUrl)

        try {
            val matchBranchEnabled = specmaticConfiguration.getMatchBranchEnabled()
            val configuredLenientMode = configuredLenientMode()
            contractSources = when (contractPaths.isEmpty()) {
                true -> {
                    logger.debug("Using the spec paths configured for stubs in the configuration file '$specmaticConfigPath'")
                    specmaticConfig.contractStubPathData(matchBranchEnabled).map { it.copy(lenientMode = configuredLenientMode) }
                }
                else -> contractPaths.map {
                    ContractPathData("", it, lenientMode = configuredLenientMode)
                }
            }
            contractPaths = contractSources.map { it.path }
            exitIfAnyDoNotExist("The following specifications do not exist", contractPaths)
            validateContractFileExtensions(contractPaths)
            startServer()

            if (httpStub != null) {
                if (registerShutdownHook) addShutdownHook()

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

    private fun configuredHotReload(): Switch = hotReload ?: specmaticConfiguration.getHotReload() ?: Switch.enabled

    private fun configuredStrictMode(): Boolean = strictMode ?: specmaticConfiguration.getStubStrictMode() ?: false

    private fun configuredGracefulTimeout(): Long = gracefulRestartTimeoutInMs ?: specmaticConfiguration.getStubGracefulRestartTimeoutInMilliseconds() ?: 1000

    private fun configuredLenientMode(): Boolean = lenientMode ?: false

    private fun startServer() {
        val workingDirectory = WorkingDirectory()
        val resolvedStrictMode = configuredStrictMode()
        if (resolvedStrictMode) throwExceptionIfDirectoriesAreInvalid(exampleDirs, "example directories")
        val stubData = stubLoaderEngine.loadStubs(
            contractPathDataList = contractSources,
            dataDirs = exampleDirs,
            specmaticConfigPath = specmaticConfigPath,
            strictMode = resolvedStrictMode,
        )

        logStubLoadingSummary(stubData)
        val stubFilter = specmaticConfiguration.getStubFilter().orEmpty()
        val filteredStubData = stubData.mapNotNull { (feature, scenarioStubs) ->
            val metadataFilter = ScenarioMetadataFilter.from(stubFilter)
            val filteredScenarios = ScenarioMetadataFilter.filterUsing(
                feature.scenarios.asSequence(),
                metadataFilter,
            ).toList()
            val stubFilterExpression = ExpressionStandardizer.filterToEvalEx(stubFilter)
            val filteredStubScenario = scenarioStubs.filter { it ->
                stubFilterExpression.with("context", HttpStubFilterContext(it)).evaluate().booleanValue
            }
            if (filteredScenarios.isNotEmpty()) {
                val updatedFeature = feature.copy(scenarios = filteredScenarios)
                updatedFeature to filteredStubScenario
            } else null
        }

        if (stubFilter != "" && filteredStubData.isEmpty()) {
            consoleLog(StringLog("FATAL: No stubs found for the given filter: $stubFilter"))
            return
        }

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
            keyData = keyData,
            strictMode = resolvedStrictMode,
            passThroughTargetBase = passThroughTargetBase,
            specmaticConfig = specmaticConfiguration,
            httpClientFactory = httpClientFactory,
            workingDirectory = workingDirectory,
            gracefulRestartTimeoutInMs = configuredGracefulTimeout(),
            specToBaseUrlMap = contractSources.specToBaseUrlMap(),
            listeners = listeners
        )

        LogTail.storeSnapshot()
    }

    private fun isDefaultPort(port:Int): Boolean {
        return DEFAULT_HTTP_STUB_PORT == port.toString()
    }

    private fun resolveHostAndPortFromBaseUrl(baseUrl: String): Pair<String, Int>? {
        return try {
            val host = extractHost(baseUrl)
            val port = extractPort(baseUrl)
            if (host.isBlank()) null else host to port
        } catch (e: Throwable) {
            logger.log("Invalid stub baseUrl '$baseUrl' in config. Falling back to defaults.")
            null
        }
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

        if (verbose == true) {
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
