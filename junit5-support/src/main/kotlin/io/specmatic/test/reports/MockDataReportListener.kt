package io.specmatic.test.reports

import io.specmatic.stub.report.MockInteractionData
import io.specmatic.stub.report.MockInteractionListener
import java.util.concurrent.ConcurrentLinkedQueue

class MockDataReportListener(
    private val outputPath: String = "./mock_data.json"
) : MockInteractionListener {
    
    private val mockInteractions = ConcurrentLinkedQueue<MockInteractionData>()
    
    override fun onMockInteraction(mockInteractionData: MockInteractionData) {
        mockInteractions.add(mockInteractionData)
    }
    
    fun writeReport() {
        if (mockInteractions.isNotEmpty()) {
            val report = MockDataJsonReport(mockInteractions.toList())
            report.writeToFile(outputPath)
            println("Mock data report written to: $outputPath")
        }
    }
    
    fun clear() {
        mockInteractions.clear()
    }
    
    fun getInteractionCount(): Int = mockInteractions.size
}