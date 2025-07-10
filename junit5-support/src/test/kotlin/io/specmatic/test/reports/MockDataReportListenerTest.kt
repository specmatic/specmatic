package io.specmatic.test.reports

import io.specmatic.stub.report.MockInteractionData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MockDataReportListenerTest {
    
    @Test
    fun `should collect mock interactions`() {
        val listener = MockDataReportListener()
        
        val mockData = MockInteractionData(
            name = "Test Scenario",
            baseUrl = "http://localhost:9000",
            duration = 100,
            valid = true,
            request = "GET /api/test",
            requestTime = 1234567890,
            response = "200 OK",
            responseTime = 1234567990,
            specFileName = "test.yaml",
            stubType = "example",
            method = "GET",
            path = "/api/test",
            responseCode = 200
        )
        
        listener.onMockInteraction(mockData)
        
        assertThat(listener.getInteractionCount()).isEqualTo(1)
    }
    
    @Test
    fun `should write report when requested`(@TempDir tempDir: File) {
        val outputFile = File(tempDir, "test_output.json")
        val listener = MockDataReportListener(outputFile.absolutePath)
        
        val mockData = MockInteractionData(
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
        
        listener.onMockInteraction(mockData)
        listener.writeReport()
        
        assertThat(outputFile.exists()).isTrue()
        val content = outputFile.readText()
        assertThat(content).contains("/api/orders")
        assertThat(content).contains("POST")
        assertThat(content).contains("Test Scenario")
    }
    
    @Test
    fun `should clear interactions when requested`() {
        val listener = MockDataReportListener()
        
        val mockData = MockInteractionData(
            name = "Test",
            baseUrl = "http://localhost:9000",
            duration = 100,
            valid = true,
            request = "GET /api/test",
            requestTime = 1234567890,
            response = "200 OK",
            responseTime = 1234567990,
            specFileName = "test.yaml",
            stubType = "example",
            method = "GET",
            path = "/api/test",
            responseCode = 200
        )
        
        listener.onMockInteraction(mockData)
        assertThat(listener.getInteractionCount()).isEqualTo(1)
        
        listener.clear()
        assertThat(listener.getInteractionCount()).isEqualTo(0)
    }
    
    @Test
    fun `should handle multiple interactions`() {
        val listener = MockDataReportListener()
        
        repeat(5) { index ->
            val mockData = MockInteractionData(
                name = "Test $index",
                baseUrl = "http://localhost:9000",
                duration = 100,
                valid = true,
                request = "GET /api/test/$index",
                requestTime = 1234567890,
                response = "200 OK",
                responseTime = 1234567990,
                specFileName = "test.yaml",
                stubType = "example",
                method = "GET",
                path = "/api/test/$index",
                responseCode = 200
            )
            listener.onMockInteraction(mockData)
        }
        
        assertThat(listener.getInteractionCount()).isEqualTo(5)
    }
}