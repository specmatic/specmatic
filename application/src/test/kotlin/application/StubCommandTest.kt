package application

import com.ginsberg.junit.exit.ExpectSystemExitWithStatus
import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.specmatic.core.CONTRACT_EXTENSION
import io.specmatic.core.KeyData
import io.specmatic.core.parseGherkinStringToFeature
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.core.utilities.StubServerWatcher
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import picocli.CommandLine
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.security.KeyStore
import kotlin.io.use


internal class StubCommandTest {

    @MockK
    lateinit var specmaticConfig: SpecmaticConfig

    @MockK
    lateinit var watchMaker: WatchMaker

    @MockK(relaxUnitFun = true)
    lateinit var watcher: StubServerWatcher

    @MockK
    lateinit var httpStubEngine: HTTPStubEngine

    @MockK
    lateinit var stubLoaderEngine: StubLoaderEngine

    @InjectMockKs
    lateinit var stubCommand: StubCommand

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        stubCommand.registerShutdownHook = false
    }

    @AfterEach
    fun cleanUp() {
        clearAllMocks()
        stubCommand.contractPaths = arrayListOf()
        stubCommand.specmaticConfigPath = null
    }

    @Test
    fun `when contract files are not given it should load from specmatic config`() {
        every { specmaticConfig.contractStubPathData() }.returns(arrayListOf("/config/path/to/contract.$CONTRACT_EXTENSION").map { ContractPathData("", it) })

        CommandLine(stubCommand).execute()

        verify(exactly = 1) { specmaticConfig.contractStubPathData() }
    }

    @Test
    fun `when contract files are given it should not load from specmatic config`() {
        every { specmaticConfig.contractStubPathData() }.returns(arrayListOf("/config/path/to/contract.$CONTRACT_EXTENSION").map { ContractPathData("", it) })

        CommandLine(stubCommand).execute("/parameter/path/to/contract.$CONTRACT_EXTENSION")

        verify(exactly = 0) { specmaticConfig.contractStubPathData() }
    }

    @Test
    fun `should attempt to start a HTTP stub`(@TempDir tempDir: File) {
        val contractPath = osAgnosticPath("${tempDir.path}/contract.$CONTRACT_EXTENSION")
        val contract = """
            Feature: Math API
              Scenario: Random API
                When GET /
                Then status 200
                And response-body (number)
        """.trimIndent()
        val file = File(contractPath).also { it.writeText(contract) }

        try {
            val feature = parseGherkinStringToFeature(contract)

            every { watchMaker.make(listOf(contractPath)) }.returns(watcher)

            val stubInfo = listOf(Pair(feature, emptyList<ScenarioStub>()))
            every {
                stubLoaderEngine.loadStubs(
                    listOf(contractPath).map { ContractPathData("", it) },
                    emptyList(),
                    any(),
                    false
                )
            }.returns(stubInfo)

            val host = "0.0.0.0"
            val port = 9000
            val strictMode = false

            every {
                httpStubEngine.runHTTPStub(
                    stubInfo,
                    host,
                    port,
                    null,
                    strictMode,
                    any(),
                    httpClientFactory = any(),
                    workingDirectory = any(),
                    gracefulRestartTimeoutInMs = any(),
                    specToBaseUrlMap = any(),
                    specmaticConfigSource = any(),
                )
            }.returns(
                mockk {
                   every { close() } returns Unit
                }
            )

            every { specmaticConfig.contractStubPaths() }.returns(arrayListOf(contractPath))

            val exitStatus = CommandLine(stubCommand).execute(contractPath)
            assertThat(exitStatus).isZero()

            verify(exactly = 1) {
                httpStubEngine.runHTTPStub(
                    any(),
                    any(),
                    any(),
                    null,
                    strictMode,
                    any(),
                    httpClientFactory = any(),
                    workingDirectory = any(),
                    gracefulRestartTimeoutInMs = any(),
                    specToBaseUrlMap = any(),
                    specmaticConfigSource = any(),
                )
            }
        } finally {
            file.delete()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [CONTRACT_EXTENSION])
    fun `when a contract with the correct extension is given it should be loaded`(extension: String, @TempDir tempDir: Path) {
        val validSpec = tempDir.resolve("contract.$extension")

        val specFilePath = validSpec.toAbsolutePath().toString()
        File(specFilePath).writeText("""
            Feature: Is a dummy feature
        """.trimIndent())

        every { watchMaker.make(listOf(specFilePath)) }.returns(watcher)
        every { specmaticConfig.contractStubPaths() }.returns(arrayListOf("/config/path/to/contract.$extension"))
        every { specmaticConfig.contractStubPathData() } returns emptyList()
        every { stubLoaderEngine.loadStubs(any(), any(), any(), any()) } returns emptyList()
        every { httpStubEngine.runHTTPStub(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            mockk<HttpStub> { every { close() } returns Unit }
        }

        val execute = CommandLine(stubCommand).execute(specFilePath)

        assertThat(execute).isEqualTo(0)
    }

    @Test
    @ExpectSystemExitWithStatus(1)
    fun `when a contract with the incorrect extension command should exit with non-zero`(@TempDir tempDir: Path) {
        val invalidSpec = tempDir.resolve("contract.contract")

        val specFilePath = invalidSpec.toAbsolutePath().toString()
        File(specFilePath).writeText("""
            Feature: Is a dummy feature
        """.trimIndent())

        every { watchMaker.make(listOf(specFilePath)) }.returns(watcher)
        every { specmaticConfig.contractStubPaths() }.returns(arrayListOf("/config/path/to/contract.$CONTRACT_EXTENSION"))

        CommandLine(stubCommand).execute(specFilePath)
    }

    @Test
    fun `should run the stub with the specified pass-through url target`(@TempDir tempDir: File) {
        val contractPath = osAgnosticPath("${tempDir.path}/contract.$CONTRACT_EXTENSION")
        val contract = """
            Feature: Simple API
              Scenario: GET request
                When GET /
                Then status 200
        """.trimIndent()

        val file = File(contractPath).also { it.writeText(contract) }

        try {
            val feature = parseGherkinStringToFeature(contract)

            every { watchMaker.make(listOf(contractPath)) }.returns(watcher)

            val stubInfo = listOf(Pair(feature, emptyList<ScenarioStub>()))
            every {
                stubLoaderEngine.loadStubs(
                    listOf(contractPath).map { ContractPathData("", it) },
                    emptyList(),
                    any(),
                    false
                )
            }.returns(stubInfo)

            val host = "0.0.0.0"
            val port = 9000
            val strictMode = false
            val passThroughTargetBase = "http://passthroughTargetBase"

            every {
                httpStubEngine.runHTTPStub(
                    stubInfo,
                    host,
                    port,
                    null,
                    strictMode,
                    passThroughTargetBase,
                    httpClientFactory = any(),
                    workingDirectory = any(),
                    gracefulRestartTimeoutInMs = any(),
                    specToBaseUrlMap = any(),
                    specmaticConfigSource = any(),
                )
            }.returns(
                mockk {
                    every { close() } returns Unit
                }
            )

            every { specmaticConfig.contractStubPaths() }.returns(arrayListOf(contractPath))

            val exitStatus = CommandLine(stubCommand).execute(
                "--passThroughTargetBase=$passThroughTargetBase",
                contractPath
            )
            assertThat(exitStatus).isZero()

            verify(exactly = 1) {
                httpStubEngine.runHTTPStub(
                    stubInfo,
                    host,
                    any(),
                    null,
                    strictMode,
                    any(),
                    httpClientFactory = any(),
                    workingDirectory = any(),
                    gracefulRestartTimeoutInMs = any(),
                    specToBaseUrlMap = any(),
                    specmaticConfigSource = any(),
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `should set specmatic_base_url property in accordance to passed host and port`() {
        every { stubLoaderEngine.loadStubs(any(), any(), any(), any()) } returns emptyList()
        every { watchMaker.make(any()) } returns watcher
        every { specmaticConfig.contractStubPaths() } returns emptyList()
        every { specmaticConfig.contractStubPathData() } returns emptyList()
        every {
            httpStubEngine.runHTTPStub(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns mockk { every { close() } returns Unit }

        try {
            val args = buildList {
                add("--host=localhost")
                add("--port=5000")
            }
            val exitStatus = CommandLine(stubCommand).execute(*args.toTypedArray())
            val specmaticBaseUrl = Flags.getStringValue(Flags.SPECMATIC_BASE_URL)

            assertThat(exitStatus).isZero()
            assertThat(specmaticBaseUrl).isEqualTo("http://localhost:5000")
        } finally {
            System.clearProperty(Flags.SPECMATIC_BASE_URL)
        }
    }

    @Test
    fun `uses config values when CLI is absent`(@TempDir tempDir: File) {
        val hostSlot = slot<String>()
        val portSlot = slot<Int>()
        val strictModeSlot = slot<Boolean>()
        val keyDataSlot = slot<KeyData>()
        val timeoutSlot = slot<Long>()

        val keystoreFile = tempDir.resolve("cli.jks")
        createEmptyKeyStore(keystoreFile, "cli-pass")

        val configFile = writeSpecmaticYaml(tempDir, """
        version: 2
        stub:
          strictMode: true
          gracefulRestartTimeoutInMilliseconds: 2000
          https:
            keyStore:
              password: cli-pass
              file: ${keystoreFile.canonicalPath}
        """.trimIndent())

        every { stubLoaderEngine.loadStubs(any(), any(), any(), any()) } returns emptyList()
        every { watchMaker.make(any()) } returns watcher
        every { specmaticConfig.contractStubPaths() } returns emptyList()
        every { specmaticConfig.contractStubPathData() } returns emptyList()
        every {
            httpStubEngine.runHTTPStub(
                any(),
                capture(hostSlot),
                capture(portSlot),
                capture(keyDataSlot),
                capture(strictModeSlot),
                any(),
                any(),
                any(),
                any(),
                capture(timeoutSlot),
                any(),
                any()
            )
        } returns mockk { every { close() } returns Unit }

        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            CommandLine(stubCommand).execute(
                "--host=cliHost",
                "--port=9999",
                "--strict",
                "--httpsKeyStore=${keystoreFile.canonicalPath}",
                "--httpsKeyStorePassword=cli-pass"
            )
        }

        assertThat(hostSlot.captured).isEqualTo("cliHost")
        assertThat(portSlot.captured).isEqualTo(9999)
        assertThat(strictModeSlot.captured).isTrue()
        assertThat(keyDataSlot.captured).isNotNull
        assertThat(timeoutSlot.captured).isEqualTo(2000L)
    }

    @Test
    fun `CLI overrides config values`(@TempDir tempDir: File) {
        val hostSlot = slot<String>()
        val portSlot = slot<Int>()
        val strictModeSlot = slot<Boolean>()
        val keyDataSlot = slot<KeyData>()
        val timeoutSlot = slot<Long>()
        val passThroughSlot = slot<String>()

        val keystoreFile = tempDir.resolve("config.jks")
        createEmptyKeyStore(keystoreFile, "pass")
        val configFile = writeSpecmaticYaml(tempDir, """
        version: 2
        stub:
          strictMode: false
          gracefulRestartTimeoutInMilliseconds: 1500
          https:
            keyStore:
              file: ${keystoreFile.canonicalPath}
        """.trimIndent())

        every { stubLoaderEngine.loadStubs(any(), any(), any(), any()) } returns emptyList()
        every { watchMaker.make(any()) } returns watcher
        every { specmaticConfig.contractStubPaths() } returns emptyList()
        every { specmaticConfig.contractStubPathData() } returns emptyList()

        every {
            httpStubEngine.runHTTPStub(
                any(),
                capture(hostSlot),
                capture(portSlot),
                capture(keyDataSlot),
                capture(strictModeSlot),
                capture(passThroughSlot),
                any(),
                any(),
                any(),
                capture(timeoutSlot),
                any(),
                any()
            )
        } returns mockk { every { close() } returns Unit }

        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            CommandLine(stubCommand).execute(
                "--host=cliHost",
                "--port=9999",
                "--strict",
                "--httpsKeyStore=${keystoreFile.canonicalPath}",
                "--httpsKeyStorePassword=pass",
                "--passThroughTargetBase=http://passthrough"
            )
        }

        assertThat(hostSlot.captured).isEqualTo("cliHost")
        assertThat(portSlot.captured).isEqualTo(9999)
        assertThat(strictModeSlot.captured).isTrue()
        assertThat(keyDataSlot.captured).isNotNull
        assertThat(timeoutSlot.captured).isEqualTo(1500L)
        assertThat(passThroughSlot.captured).isEqualTo("http://passthrough")
    }

    @Test
    fun `falls back to defaults when neither CLI nor config is present`() {
        val hostSlot = slot<String>()
        val portSlot = slot<Int>()
        val strictModeSlot = slot<Boolean>()
        val timeoutSlot = slot<Long>()
        var capturedKeyData: KeyData? = null

        every { stubLoaderEngine.loadStubs(any(), any(), any(), any()) } returns emptyList()
        every { watchMaker.make(any()) } returns watcher
        every { specmaticConfig.contractStubPaths() } returns emptyList()
        every { specmaticConfig.contractStubPathData() } returns emptyList()

        every {
            httpStubEngine.runHTTPStub(
                any(),
                capture(hostSlot),
                capture(portSlot),
                any(),
                capture(strictModeSlot),
                any(),
                any(),
                any(),
                any(),
                capture(timeoutSlot),
                any(),
                any()
            )
        } answers {
            capturedKeyData = arg(3) as KeyData?
            mockk<HttpStub> { every { close() } returns Unit }
        }

        CommandLine(stubCommand).execute()

        assertThat(capturedKeyData).isNull()
        assertThat(hostSlot.captured).isEqualTo("0.0.0.0")
        assertThat(portSlot.captured).isEqualTo(9000)
        assertThat(strictModeSlot.captured).isFalse()
        assertThat(timeoutSlot.captured).isEqualTo(1000L)
    }

    @Test
    fun `uses HTTPS key store from CLI if provided`(@TempDir tempDir: File) {
        val keystoreFile = tempDir.resolve("cli.jks")
        createEmptyKeyStore(keystoreFile, "cli-pass")

        val keyDataSlot = slot<KeyData>()
        every { stubLoaderEngine.loadStubs(any(), any(), any(), any()) } returns emptyList()
        every { watchMaker.make(any()) } returns watcher
        every { specmaticConfig.contractStubPaths() } returns emptyList()
        every { specmaticConfig.contractStubPathData() } returns emptyList()
        every {
            httpStubEngine.runHTTPStub(
                any(),
                any(),
                any(),
                capture(keyDataSlot),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns mockk { every { close() } returns Unit }

        CommandLine(stubCommand).execute("--httpsKeyStore=${keystoreFile.canonicalPath}", "--httpsKeyStorePassword=cli-pass")
        assertThat(keyDataSlot.captured).isNotNull
    }

    @Test
    fun `uses HTTPS key store from config if provided`(@TempDir tempDir: File) {
        val keyDataSlot = slot<KeyData>()
        val keystoreFile = tempDir.resolve("config.jks")
        createEmptyKeyStore(keystoreFile, "pass")
        val configFile = writeSpecmaticYaml(tempDir, """
        version: 2
        stub:
          https:
            keyStorePassword: pass
            keyStore:
              file: ${keystoreFile.canonicalPath}
        """.trimIndent())

        every { stubLoaderEngine.loadStubs(any(), any(), any(), any()) } returns emptyList()
        every { watchMaker.make(any()) } returns watcher
        every { specmaticConfig.contractStubPaths() } returns emptyList()
        every { specmaticConfig.contractStubPathData() } returns emptyList()
        every {
            httpStubEngine.runHTTPStub(
                any(),
                any(),
                any(),
                capture(keyDataSlot),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns mockk { every { close() } returns Unit }

        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) { CommandLine(stubCommand).execute() }
        assertThat(keyDataSlot.captured).isNotNull
    }

    @Test
    fun `CLI overrides some config values while config fills missing ones`(@TempDir tempDir: File) {
        val hostSlot = slot<String>()
        val strictModeSlot = slot<Boolean>()
        val keyDataSlot = slot<KeyData>()
        val timeoutSlot = slot<Long>()

        val keystoreFile = tempDir.resolve("config.jks")
        createEmptyKeyStore(keystoreFile, "key-pass")
        val configFile = writeSpecmaticYaml(tempDir, """
        version: 2
        stub:
          strictMode: false
          gracefulRestartTimeoutInMilliseconds: 2500
          https:
            keyStore:
              file: ${keystoreFile.canonicalPath}
              password: pass
        """.trimIndent())

        every { stubLoaderEngine.loadStubs(any(), any(), any(), any()) } returns emptyList()
        every { watchMaker.make(any()) } returns watcher
        every { specmaticConfig.contractStubPaths() } returns emptyList()
        every { specmaticConfig.contractStubPathData() } returns emptyList()
        every {
            httpStubEngine.runHTTPStub(
                any(),
                capture(hostSlot),
                any(),
                capture(keyDataSlot),
                capture(strictModeSlot),
                any(),
                any(),
                any(),
                any(),
                capture(timeoutSlot),
                any(),
                any()
            )
        } returns mockk { every { close() } returns Unit }

        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            CommandLine(stubCommand).execute("--host=cliHost", "--strict", "--httpsKeyStorePassword=key-pass")
        }

        assertThat(hostSlot.captured).isEqualTo("cliHost")
        assertThat(strictModeSlot.captured).isTrue()
        assertThat(keyDataSlot.captured).isNotNull
        assertThat(timeoutSlot.captured).isEqualTo(2500L)
    }

    fun osAgnosticPath(path: String): String {
        return path.replace("/", File.separator)
    }

    private fun writeSpecmaticYaml(dir: File, content: String): File = dir.resolve("specmatic.yaml").also { it.writeText(content) }

    private fun createEmptyKeyStore(file: File, password: String) {
        val keyStore = KeyStore.getInstance("JKS")
        keyStore.load(null, password.toCharArray())
        FileOutputStream(file).use { keyStore.store(it, password.toCharArray()) }
    }
}
