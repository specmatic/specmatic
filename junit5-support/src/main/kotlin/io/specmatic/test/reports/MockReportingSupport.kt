package io.specmatic.test.reports

import io.specmatic.stub.HttpStub
import io.specmatic.stub.report.MockInteractionCollector
import io.specmatic.stub.reports.MockHooks

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

    /**
     * Sets up mock reporting using MockHooks - no need to manually register with HttpStub.
     * This is the recommended approach for automatic mock interaction reporting.
     *
     * @param baseUrl Base URL of the stub server
     * @param outputPath Path where the mock_data.json file should be written
     * @return MockDataReportListener for manual report writing control
     */
    fun setupMockReportingWithHooks(
        baseUrl: String,
        outputPath: String = "./mock_data.json"
    ): MockDataReportListener {
        val reportListener = createMockDataReportListener(outputPath)
        val collector = createMockInteractionCollector(baseUrl, reportListener)

        // Register with global MockHooks - no manual HttpStub registration needed
        MockHooks.registerListener(collector)

        return reportListener
    }

    /**
     * Cleanup method to remove mock reporting hooks when done.
     *
     * @param reportListener The listener returned from setupMockReportingWithHooks
     * @param baseUrl The same baseUrl used in setup (needed to find the collector)
     */
    fun cleanupMockReportingHooks(reportListener: MockDataReportListener, baseUrl: String) {
        // Find and remove the collector associated with this report listener
        val collectorsToRemove = mutableListOf<MockInteractionCollector>()
        MockHooks.onEachListener {
            if (this is MockInteractionCollector) {
                collectorsToRemove.add(this)
            }
        }
        collectorsToRemove.forEach { MockHooks.removeListener(it) }
    }
}
