package io.specmatic.test.reports

import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue
import io.specmatic.stub.listener.MockEvent
import io.specmatic.stub.report.MockInteractionData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MockReportingSupportTest {
    
    @Test
    fun `should create mock data report listener with correct output path`() {
        val outputPath = "/tmp/test_mock_data.json"
        val listener = MockReportingSupport.createMockDataReportListener(outputPath)
        
        assertThat(listener).isNotNull()
        assertThat(listener.getInteractionCount()).isEqualTo(0)
    }
    
    @Test
    fun `should create mock interaction collector with report listener`() {
        val baseUrl = "http://localhost:9000"
        val reportListener = MockReportingSupport.createMockDataReportListener()
        val collector = MockReportingSupport.createMockInteractionCollector(baseUrl, reportListener)
        
        assertThat(collector).isNotNull()
        
        // Test that the collector properly forwards to the report listener
        val mockEvent = MockEvent(
            name = "Test",
            details = "Request matched example",
            request = HttpRequest(method = "GET", path = "/test"),
            requestTime = 1234567890,
            response = HttpResponse(status = 200, body = StringValue("OK")),
            responseTime = 1234567990,
            scenario = Scenario(
                name = "Test Scenario",
                httpRequestPattern = HttpRequestPattern(method = "GET"),
                httpResponsePattern = HttpResponsePattern(status = 200),
                specification = "test.yaml"
            ),
            result = TestResult.Success
        )
        
        collector.onRespond(mockEvent)
        assertThat(reportListener.getInteractionCount()).isEqualTo(1)
    }
    
    @Test
    fun `should setup mock reporting with convenience method`(@TempDir tempDir: File) {
        val baseUrl = "http://localhost:8080"
        val outputPath = File(tempDir, "mock_report.json").absolutePath
        
        val (reportListener, collector) = MockReportingSupport.setupMockReporting(baseUrl, outputPath)
        
        assertThat(reportListener).isNotNull()
        assertThat(collector).isNotNull()
        
        // Test the complete flow
        val mockEvent = MockEvent(
            name = "Integration Test",
            details = "Generated response",
            request = HttpRequest(method = "POST", path = "/api/orders"),
            requestTime = 1234567890,
            response = HttpResponse(status = 201, body = StringValue("Created")),
            responseTime = 1234568040,
            scenario = Scenario(
                name = "Orders Scenario",
                httpRequestPattern = HttpRequestPattern(method = "POST"),
                httpResponsePattern = HttpResponsePattern(status = 201),
                specification = "orders.yaml"
            ),
            result = TestResult.Success
        )
        
        collector.onRespond(mockEvent)
        assertThat(reportListener.getInteractionCount()).isEqualTo(1)
        
        reportListener.writeReport()
        val reportFile = File(outputPath)
        assertThat(reportFile.exists()).isTrue()
        
        val content = reportFile.readText()
        assertThat(content).contains("/api/orders")
        assertThat(content).contains("POST")
        assertThat(content).contains("Integration Test")
    }
    
    @Test
    fun `should setup mock reporting with hooks`(@TempDir tempDir: File) {
        val baseUrl = "http://localhost:8080"
        val outputPath = File(tempDir, "hooks_mock_report.json").absolutePath
        
        val reportListener = MockReportingSupport.setupMockReportingWithHooks(baseUrl, outputPath)
        
        assertThat(reportListener).isNotNull()
        assertThat(reportListener.getInteractionCount()).isEqualTo(0)
        
        // Cleanup
        MockReportingSupport.cleanupMockReportingHooks(reportListener, baseUrl)
    }
}