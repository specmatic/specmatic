package io.specmatic.test.reports

import io.specmatic.stub.report.MockInteractionData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MockDataJsonReportTest {
    
    @Test
    fun `should generate JSON report with correct structure`() {
        val mockInteractions = listOf(
            MockInteractionData(
                name = "Test Scenario 1",
                baseUrl = "http://localhost:9000",
                duration = 150,
                valid = true,
                request = "GET /api/users HTTP/1.1\nContent-Type: application/json",
                requestTime = 1234567890,
                response = "HTTP/1.1 200 OK\nContent-Type: application/json\n{\"users\": []}",
                responseTime = 1234568040,
                specFileName = "users.yaml",
                details = "Request matched example",
                stubType = "example",
                method = "GET",
                path = "/api/users",
                responseCode = 200,
                responseSource = "LoadedStub",
                stubToken = null,
                delayInMilliseconds = 0,
                sourceProvider = "github",
                sourceRepository = "myrepo",
                sourceRepositoryBranch = "main",
                serviceType = "REST"
            )
        )
        
        val report = MockDataJsonReport(mockInteractions)
        val jsonString = report.generateReport()
        
        // Parse the JSON to verify structure
        val jsonObject = Json.parseToJsonElement(jsonString) as JsonObject
        
        assertThat(jsonObject.containsKey("/api/users")).isTrue()
        val pathObject = jsonObject["/api/users"] as JsonObject
        assertThat(pathObject.containsKey("GET")).isTrue()
        val methodObject = pathObject["GET"] as JsonObject
        assertThat(methodObject.containsKey("application/json")).isTrue()
        val contentTypeObject = methodObject["application/json"] as JsonObject
        assertThat(contentTypeObject.containsKey("200")).isTrue()
    }
    
    @Test
    fun `should write report to file`(@TempDir tempDir: File) {
        val mockInteractions = listOf(
            MockInteractionData(
                name = "Test Scenario",
                baseUrl = "http://localhost:9000",
                duration = 100,
                valid = true,
                request = "POST /api/orders",
                requestTime = 1234567890,
                response = "201 Created",
                responseTime = 1234567990,
                specFileName = "orders.yaml",
                stubType = "generated",
                method = "POST",
                path = "/api/orders",
                responseCode = 201
            )
        )
        
        val outputFile = File(tempDir, "test_mock_data.json")
        val report = MockDataJsonReport(mockInteractions)
        
        report.writeToFile(outputFile.absolutePath)
        
        assertThat(outputFile.exists()).isTrue()
        val content = outputFile.readText()
        assertThat(content).contains("/api/orders")
        assertThat(content).contains("POST")
    }
    
    @Test
    fun `should handle empty interactions list`() {
        val report = MockDataJsonReport(emptyList())
        val jsonString = report.generateReport()
        
        val jsonObject = Json.parseToJsonElement(jsonString) as JsonObject
        assertThat(jsonObject.isEmpty()).isTrue()
    }
    
    @Test
    fun `should group interactions by path, method, content type, and status code`() {
        val mockInteractions = listOf(
            MockInteractionData(
                name = "Test 1",
                baseUrl = "http://localhost:9000",
                duration = 100,
                valid = true,
                request = "GET /api/users",
                requestTime = 1234567890,
                response = "200 OK",
                responseTime = 1234567990,
                specFileName = "users.yaml",
                stubType = "example",
                method = "GET",
                path = "/api/users",
                responseCode = 200
            ),
            MockInteractionData(
                name = "Test 2",
                baseUrl = "http://localhost:9000",
                duration = 120,
                valid = true,
                request = "GET /api/users",
                requestTime = 1234568000,
                response = "200 OK",
                responseTime = 1234568120,
                specFileName = "users.yaml",
                stubType = "example",
                method = "GET",
                path = "/api/users",
                responseCode = 200
            )
        )
        
        val report = MockDataJsonReport(mockInteractions)
        val jsonString = report.generateReport()
        val jsonObject = Json.parseToJsonElement(jsonString) as JsonObject
        
        // Should have both interactions grouped under the same path/method/contentType/status
        val interactions = jsonObject["/api/users"]!!
            .jsonObject["GET"]!!
            .jsonObject["application/json"]!!
            .jsonObject["200"]!!
            .jsonArray
        
        assertThat(interactions.size).isEqualTo(2)
    }
}