package io.specmatic.test.reports

import io.specmatic.stub.HttpStub
import io.specmatic.stub.report.MockInteractionCollector

object MockReportingSupport {
    
    /**
     * Creates a MockDataReportListener that can be registered with a MockInteractionCollector
     * to collect mock interaction data and write it to a file when requested.
     * 
     * @param outputPath Path where the mock_data.json file should be written
     * @return MockDataReportListener that implements MockInteractionListener
     */
    fun createMockDataReportListener(outputPath: String = "./mock_data.json"): MockDataReportListener {
        return MockDataReportListener(outputPath)
    }
    
    /**
     * Creates a MockInteractionCollector configured with the given MockDataReportListener.
     * The collector should be registered with HttpStub as a MockEventListener.
     * 
     * @param baseUrl Base URL of the stub server
     * @param reportListener The listener that will collect and write mock data
     * @return MockInteractionCollector that can be registered with HttpStub
     */
    fun createMockInteractionCollector(
        baseUrl: String,
        reportListener: MockDataReportListener
    ): MockInteractionCollector {
        return MockInteractionCollector(
            baseUrl = baseUrl,
            mockInteractionListeners = listOf(reportListener)
        )
    }
    
    /**
     * Convenience method to create both the report listener and collector together.
     * 
     * @param baseUrl Base URL of the stub server
     * @param outputPath Path where the mock_data.json file should be written
     * @return Pair of (MockDataReportListener, MockInteractionCollector)
     */
    fun setupMockReporting(
        baseUrl: String,
        outputPath: String = "./mock_data.json"
    ): Pair<MockDataReportListener, MockInteractionCollector> {
        val reportListener = createMockDataReportListener(outputPath)
        val collector = createMockInteractionCollector(baseUrl, reportListener)
        return reportListener to collector
    }
}