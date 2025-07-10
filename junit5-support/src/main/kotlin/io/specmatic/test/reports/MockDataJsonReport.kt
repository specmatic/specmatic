package io.specmatic.test.reports

import io.specmatic.stub.report.MockInteractionData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

class MockDataJsonReport(private val mockInteractions: List<MockInteractionData>) {
    
    fun generateReport(): String {
        // Group interactions by path first, then method, then content type, then status code
        val pathGroups = mockInteractions.groupBy { it.path }
        
        val mockDataJson = buildJsonObject {
            pathGroups.forEach { (path, pathInteractions) ->
                putJsonObject(path) {
                    val methodGroups = pathInteractions.groupBy { it.method }
                    methodGroups.forEach { (method, methodInteractions) ->
                        putJsonObject(method) {
                            val contentTypeGroups = methodInteractions.groupBy { getContentType(it) }
                            contentTypeGroups.forEach { (contentType, contentTypeInteractions) ->
                                putJsonObject(contentType) {
                                    val statusGroups = contentTypeInteractions.groupBy { it.responseCode.toString() }
                                    statusGroups.forEach { (statusCode, statusInteractions) ->
                                        putJsonArray(statusCode) {
                                            statusInteractions.forEach { interaction ->
                                                add(buildJsonObject {
                                                    put("name", interaction.name)
                                                    put("baseUrl", interaction.baseUrl)
                                                    put("duration", interaction.duration)
                                                    put("valid", interaction.valid)
                                                    put("request", interaction.request)
                                                    put("requestTime", interaction.requestTime)
                                                    put("response", interaction.response)
                                                    put("responseTime", interaction.responseTime)
                                                    put("specFileName", interaction.specFileName)
                                                    put("details", interaction.details)
                                                    put("stubType", interaction.stubType)
                                                    put("method", interaction.method)
                                                    put("path", interaction.path)
                                                    put("responseCode", interaction.responseCode)
                                                    put("responseSource", interaction.responseSource)
                                                    put("stubToken", interaction.stubToken)
                                                    put("delayInMilliseconds", interaction.delayInMilliseconds)
                                                    put("sourceProvider", interaction.sourceProvider)
                                                    put("sourceRepository", interaction.sourceRepository)
                                                    put("sourceRepositoryBranch", interaction.sourceRepositoryBranch)
                                                    put("serviceType", interaction.serviceType)
                                                })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), mockDataJson)
    }
    
    fun writeToFile(filePath: String = "./mock_data.json") {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeText(generateReport())
    }
    
    private fun getContentType(interaction: MockInteractionData): String {
        // Extract content type from request or response, default to "application/json"
        return when {
            interaction.request.contains("application/xml", ignoreCase = true) -> "application/xml"
            interaction.request.contains("text/plain", ignoreCase = true) -> "text/plain"
            interaction.request.contains("application/json", ignoreCase = true) -> "application/json"
            interaction.response.contains("application/xml", ignoreCase = true) -> "application/xml"
            interaction.response.contains("text/plain", ignoreCase = true) -> "text/plain"
            else -> "application/json"
        }
    }
}