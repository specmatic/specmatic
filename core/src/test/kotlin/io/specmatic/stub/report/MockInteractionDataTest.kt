package io.specmatic.stub.report

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MockInteractionDataTest {
    
    @Test
    fun `should create MockInteractionData with all required fields`() {
        val mockData = MockInteractionData(
            name = "Test Scenario",
            baseUrl = "http://localhost:9000",
            duration = 150,
            valid = true,
            request = "GET /api/users",
            requestTime = 1234567890,
            response = "200 OK",
            responseTime = 1234567990,
            specFileName = "users.yaml",
            stubType = "explicit",
            method = "GET",
            path = "/api/users",
            responseCode = 200
        )
        
        assertEquals("Test Scenario", mockData.name)
        assertEquals("http://localhost:9000", mockData.baseUrl)
        assertEquals(150, mockData.duration)
        assertEquals(true, mockData.valid)
        assertEquals("GET /api/users", mockData.request)
        assertEquals(1234567890, mockData.requestTime)
        assertEquals("200 OK", mockData.response)
        assertEquals(1234567990, mockData.responseTime)
        assertEquals("users.yaml", mockData.specFileName)
        assertEquals("explicit", mockData.stubType)
        assertEquals("GET", mockData.method)
        assertEquals("/api/users", mockData.path)
        assertEquals(200, mockData.responseCode)
    }
    
    @Test
    fun `should have default values for optional fields`() {
        val mockData = MockInteractionData(
            name = "Test",
            baseUrl = "http://localhost",
            duration = 100,
            valid = false,
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
        
        assertEquals("", mockData.details)
        assertEquals("", mockData.responseSource)
        assertNull(mockData.stubToken)
        assertEquals(0, mockData.delayInMilliseconds)
        assertEquals("", mockData.sourceProvider)
        assertEquals("", mockData.sourceRepository)
        assertEquals("", mockData.sourceRepositoryBranch)
        assertEquals("", mockData.serviceType)
    }
    
    @Test
    fun `should create MockInteractionData with all optional fields populated`() {
        val mockData = MockInteractionData(
            name = "Test with all fields",
            baseUrl = "http://localhost:8080",
            duration = 200,
            valid = true,
            request = "PUT /api/products/123",
            requestTime = 1234567890,
            response = "200 OK",
            responseTime = 1234568090,
            specFileName = "products.yaml",
            details = "Request matched example",
            stubType = "example",
            method = "PUT",
            path = "/api/products/123",
            responseCode = 200,
            responseSource = "LoadedStub",
            stubToken = "token-123",
            delayInMilliseconds = 500,
            sourceProvider = "github",
            sourceRepository = "myrepo",
            sourceRepositoryBranch = "main",
            serviceType = "REST"
        )
        
        assertEquals("Request matched example", mockData.details)
        assertEquals("LoadedStub", mockData.responseSource)
        assertEquals("token-123", mockData.stubToken)
        assertEquals(500, mockData.delayInMilliseconds)
        assertEquals("github", mockData.sourceProvider)
        assertEquals("myrepo", mockData.sourceRepository)
        assertEquals("main", mockData.sourceRepositoryBranch)
        assertEquals("REST", mockData.serviceType)
    }
}