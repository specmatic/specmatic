package application.mcp.server.tools

import application.StubCommand
import io.mockk.*
import io.specmatic.stub.waitUntilConnectable
import kotlinx.coroutines.runBlocking
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
        
        // Use reflection to override the private mockRegistryFile to point to our temp directory
        val registryFileField = tool.javaClass.getDeclaredField("mockRegistryFile")
        registryFileField.isAccessible = true
        registryFileField.set(tool, tempDir.resolve("mock-servers.json"))
        
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
        
        // Verify registry was updated
        val registryFile = tempDir.resolve("mock-servers.json")
        assertThat(registryFile).exists()
        assertThat(registryFile.readText()).contains("9001")
        
        // Verify tool internal state
        assertThat(getRunningMocks()).containsKey(9001)
    }

    @Test
    fun `manageMockServer stop should return failure when port is not in registry`() {
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
        
        // Verify registry is empty
        val registryFile = tempDir.resolve("mock-servers.json")
        assertThat(registryFile.readText()).isEqualTo("[]")
        
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
