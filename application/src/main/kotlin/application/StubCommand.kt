package application

import application.mock.MockInitializer
import application.mock.MockInitializerInputs
import io.specmatic.core.*
import io.specmatic.core.Configuration.Companion.DEFAULT_HTTP_STUB_HOST
import io.specmatic.core.Configuration.Companion.DEFAULT_HTTP_STUB_PORT
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.LoggingConfiguration.Companion.LoggingFromOpts
import io.specmatic.core.config.Switch
import io.specmatic.core.log.*
import io.specmatic.license.core.cli.Category
import io.specmatic.stub.ContractStub
import io.specmatic.stub.HttpClientFactory
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

    var listeners: List<MockEventListener> = emptyList()
    var registerShutdownHook: Boolean = true
    private val mockInitializer: MockInitializer = MockInitializer()

    override fun call() {
        configureLogging(LoggingFromOpts(
            debug = verbose,
            textLogDirectory = textLogDir,
            textConsoleLog = noConsoleLog?.let { !it },
            jsonConsoleLog = jsonConsoleLog,
            jsonLogDirectory = jsonLogDir,
            logPrefix = logPrefix
        ))

        try {
            val specmaticConfig = startServer()
            if (httpStub != null) {
                if (registerShutdownHook) addShutdownHook()
                val configuredHotReload = configuredHotReload(specmaticConfig)
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

    private fun configuredHotReload(specmaticConfig: io.specmatic.core.SpecmaticConfig): Switch {
        return hotReload ?: specmaticConfig.getHotReload() ?: Switch.enabled
    }

    private fun startServer(): io.specmatic.core.SpecmaticConfig {
        val input = buildStubCommandInputs()
        httpStub = mockInitializer.createAndStart(input)
        return mockInitializer.getSpecmaticConfig(input)
    }

    private fun buildStubCommandInputs(): MockInitializerInputs {
        return MockInitializerInputs(
            contractPaths = contractPaths,
            exampleDirs = exampleDirs,
            host = host,
            port = port,
            strictMode = strictMode,
            passThroughTargetBase = passThroughTargetBase,
            filter = filter,
            gracefulRestartTimeoutInMs = gracefulRestartTimeoutInMs,
            lenientMode = lenientMode,
            verbose = verbose,
            configFileName = configFileName,
            useCurrentBranchForCentralRepo = useCurrentBranchForCentralRepo,
            httpsOpts = HttpsConfiguration.Companion.HttpsFromOpts(keyStoreFile, keyStoreDir, keyStorePassword, keyStoreAlias, keyPassword),
            httpClientFactory = httpClientFactory,
            listeners = listeners,
            delayInMilliseconds = delayInMilliseconds,
            applicationSpecmaticConfig = specmaticConfig,
            httpStubEngine = httpStubEngine,
            stubLoaderEngine = stubLoaderEngine
        )
    }

    private fun restartServer() {
        consoleLog(StringLog("Stopping servers..."))
        try {
            stopServer()
            consoleLog(StringLog("Stopped."))
        } catch (e: Throwable) {
            consoleLog(e,"Error stopping server")
        }

        try {
            startServer()
        } catch (e: Throwable) {
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
                } catch (_: InterruptedException) {
                    currentThread().interrupt()
                }
            }
        })
    }
}
