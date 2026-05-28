package application.mcp.server.tools

import application.StubCommand
import io.mockk.*
import io.specmatic.stub.waitUntilConnectable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MockServerToolTest {

    private lateinit var tool: MockServerTool
    
    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        tool = MockServerTool()
        
        // Mock the top-level function in SpecmaticMockRunner.kt
        mockkStatic("io.specmatic.stub.SpecmaticMockRunnerKt")
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun getRunningMocks(): ConcurrentHashMap<Int, StubCommand> {
        val field = tool.javaClass.getDeclaredField("runningMocks")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(tool) as ConcurrentHashMap<Int, StubCommand>
    }

    private fun extractLogDir(result: String): String {
        return result.lines().find { it.contains("Log directory:") }
            ?.substringAfter("Log directory: ")
            ?.trim() ?: ""
    }

    @Test
    fun `manageMockServer list should return empty message when no servers are running`() {
        val args = ManageMockServerArgs(command = "list")
        val result = tool.manageMockServer(args)

        assertThat(result).contains("Running Mock Servers: 0")
        assertThat(result).contains("No mock servers are currently running.")
    }

    @Test
    fun `manageMockServer start should succeed when inputs are valid`() {
        mockkConstructor(StubCommand::class)
        every { anyConstructed<StubCommand>().call() } returns 0
        every { anyConstructed<StubCommand>().close() } just Runs
        
        coEvery { waitUntilConnectable(any(), any(), any()) } returns true

        val args = ManageMockServerArgs(
            command = "start",
            openApiSpec = "openapi: 3.0.0...",
            port = 9001
        )
        
        val result = tool.manageMockServer(args)

        assertThat(result).contains("Mock server started successfully")
        assertThat(result).contains("Server URL: http://localhost:9001")
        assertThat(result).contains("Log directory:")
        
        val logDir = extractLogDir(result)
        assertThat(File(logDir)).isDirectory()
        
        // Verify tool internal state
        assertThat(getRunningMocks()).containsKey(9001)
    }

    @Test
    fun `should start MCP managed mocks with text file logging and console logging disabled`() {
        val commandArgsMethod = tool.javaClass.getDeclaredMethod("stubCommandArgs", Int::class.javaPrimitiveType, File::class.java, File::class.java)
        commandArgsMethod.isAccessible = true

        val mockTempDir = tempDir.resolve("mock-runtime").apply { mkdirs() }
        val specFile = mockTempDir.resolve("spec.yaml").apply { writeText("openapi: 3.0.0") }

        @Suppress("UNCHECKED_CAST")
        val args = commandArgsMethod.invoke(tool, 9001, mockTempDir, specFile) as List<String>

        assertThat(args).containsSequence("--textLog", mockTempDir.canonicalPath)
        assertThat(args).contains("--noConsoleLog")
        assertThat(args).containsSequence("--hot-reload", "disabled")
        assertThat(args.last()).isEqualTo(specFile.canonicalPath)
    }

    @Test
    fun `manageMockServer stop should return failure when port is not running`() {
        val args = ManageMockServerArgs(command = "stop", port = 9005)
        val result = tool.manageMockServer(args)

        assertThat(result).contains("Failed to stop mock server")
        assertThat(result).contains("No mock server is running on port 9005")
    }

    @Test
    fun `manageMockServer stop should succeed when server is running`() {
        mockkConstructor(StubCommand::class)
        every { anyConstructed<StubCommand>().call() } returns 0
        every { anyConstructed<StubCommand>().close() } just Runs
        coEvery { waitUntilConnectable(any(), any(), any()) } returns true

        // Start a server first
        tool.manageMockServer(ManageMockServerArgs(command = "start", openApiSpec = "...", port = 9006))
        assertThat(getRunningMocks()).containsKey(9006)
        
        // Stop it
        val result = tool.manageMockServer(ManageMockServerArgs(command = "stop", port = 9006))

        assertThat(result).contains("Mock server stopped successfully")
        assertThat(result).contains("Port: 9006")
        
        // Verify internal state
        assertThat(getRunningMocks()).doesNotContainKey(9006)
    }

    @Test
    fun `manageMockServer list should return running servers`() {
        mockkConstructor(StubCommand::class)
        every { anyConstructed<StubCommand>().call() } returns 0
        every { anyConstructed<StubCommand>().close() } just Runs
        coEvery { waitUntilConnectable(any(), any(), any()) } returns true

        tool.manageMockServer(ManageMockServerArgs(command = "start", openApiSpec = "...", port = 9010))
        tool.manageMockServer(ManageMockServerArgs(command = "start", openApiSpec = "...", port = 9011))
        
        val result = tool.manageMockServer(ManageMockServerArgs(command = "list"))

        assertThat(result).contains("Running Mock Servers: 2")
        assertThat(result).contains("http://localhost:9010")
        assertThat(result).contains("http://localhost:9011")
    }

    @Test
    fun `manageMockServer start should fail if openApiSpec is missing`() {
        val args = ManageMockServerArgs(command = "start", port = 9000)
        
        try {
            tool.manageMockServer(args)
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).isEqualTo("openApiSpec is required for 'start' command")
        }
    }
}
