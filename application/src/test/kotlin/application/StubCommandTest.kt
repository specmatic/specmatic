package application

import com.ginsberg.junit.exit.ExpectSystemExitWithStatus
import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.specmatic.core.CONTRACT_EXTENSION
import io.specmatic.core.parseGherkinStringToFeature
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.StubServerWatcher
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import picocli.CommandLine
import java.io.File
import java.nio.file.Path


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
    }

    @AfterEach
    fun cleanUp() {
        clearAllMocks()
        stubCommand.contractPaths = arrayListOf()
    }

    @Test
    fun `when contract files are not given it should load from specmatic config`(@TempDir tempDir: File) {
        val contractPath = tempDir.resolve("contract.$CONTRACT_EXTENSION").apply {
            writeText(
                """
                Feature: Example API
                  Scenario: Example scenario
                    When GET /
                    Then status 200
                """.trimIndent()
            )
        }.path
        val dummyConfigFile = tempDir.resolve("specmatic.yaml").apply { writeText("sources: []") }
        stubCommand.configFileName = dummyConfigFile.path

        every { specmaticConfig.contractStubPathData(false) }
            .returns(listOf(ContractPathData("", contractPath)))
        every { watchMaker.make(listOf(contractPath)) }.returns(watcher)
        every {
            stubLoaderEngine.loadStubs(
                listOf(contractPath).map { ContractPathData("", it) },
                emptyList(),
                any(),
                false
            )
        }.returns(emptyList())
        every {
            httpStubEngine.runHTTPStub(
                emptyList(),
                any(),
                any(),
                any(),
                any(),
                any(),
                specmaticConfigPath = any(),
                httpClientFactory = any(),
                workingDirectory = any(),
                gracefulRestartTimeoutInMs = any(),
                specToBaseUrlMap = any()
            )
        }.returns(mockk(relaxUnitFun = true))

        CommandLine(stubCommand).execute()

        verify(exactly = 1) { specmaticConfig.contractStubPathData(false) }
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
            val certInfo = CertInfo()
            val strictMode = false

            every {
                httpStubEngine.runHTTPStub(
                    stubInfo,
                    host,
                    port,
                    certInfo,
                    strictMode,
                    any(),
                    specmaticConfigPath = any(),
                    httpClientFactory = any(),
                    workingDirectory = any(),
                    gracefulRestartTimeoutInMs = any(),
                    specToBaseUrlMap = any()
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
                    certInfo,
                    strictMode,
                    any(),
                    specmaticConfigPath = any(),
                    httpClientFactory = any(),
                    workingDirectory = any(),
                    gracefulRestartTimeoutInMs = any(),
                    specToBaseUrlMap = any()
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
            val certInfo = CertInfo()
            val strictMode = false
            val passThroughTargetBase = "http://passthroughTargetBase"

            every {
                httpStubEngine.runHTTPStub(
                    stubInfo,
                    host,
                    port,
                    certInfo,
                    strictMode,
                    passThroughTargetBase,
                    specmaticConfigPath = any(),
                    httpClientFactory = any(),
                    workingDirectory = any(),
                    gracefulRestartTimeoutInMs = any(),
                    specToBaseUrlMap = any()
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
                    certInfo,
                    strictMode,
                    any(),
                    specmaticConfigPath = any(),
                    httpClientFactory = any(),
                    workingDirectory = any(),
                    gracefulRestartTimeoutInMs = any(),
                    specToBaseUrlMap = any()
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

    fun osAgnosticPath(path: String): String {
        return path.replace("/", File.separator)
    }
}
