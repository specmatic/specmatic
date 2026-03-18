package io.specmatic.core

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value

class Workflow(
    val workflow: WorkflowDetails = WorkflowDetails.default,
) {
    var id: Value? = null

    fun extractDataFrom(response: HttpResponse, originalScenario: Scenario) {
        val extractLocation = workflow.getExtractForAPI(originalScenario.defaultAPIDescription) ?: return

        val locationPath = extractLocation.split(".")
        if(locationPath.isEmpty())
            return

        val area = locationPath[0]

        val path = locationPath.drop(1)

        when(area.uppercase()) {
            "BODY" -> {
                val responseBody = response.body

                if(path.isEmpty()) {
                    id = responseBody
                } else if(responseBody is JSONObjectValue) {
                    val data = responseBody.findFirstChildByPath(path.joinToString("."))
                    if(data != null)
                        id = data
                }
            }
            else -> {
                throw ContractException("Cannot extract data from $area yet")
            }
        }
    }

    fun updateRequest(request: HttpRequest, originalScenario: Scenario): HttpRequest {
        if(originalScenario.isNegative)
            return request

        val useLocation = workflow.getUseForAPI(originalScenario.defaultAPIDescription) ?: return request

        val locationPath = useLocation.split(".")
        if(locationPath.isEmpty())
            return request

        val area = locationPath[0]

        val path = locationPath.drop(1)

        return when(area.uppercase()) {
            "PATH" -> {
                if(path.isEmpty())
                    throw ContractException("Cannot use id $useLocation")

                if(path.size > 1)
                    throw ContractException("PATH.<name> must refer to the name of a path parameter")

                val pathParamName = path[0]
                val valueToInsert = id ?: return request
                updateAndValidatePath(request, originalScenario, pathParamName, valueToInsert)
            }
            else -> {
                throw ContractException("Cannot extract data from $area yet")
            }
        }
    }

    private fun updateAndValidatePath(request: HttpRequest, scenario: Scenario, parameterName: String, value: Value): HttpRequest {
        val pathPattern = scenario.httpRequestPattern.httpPathPattern ?: return request
        val originalPath = request.path.orEmpty()
        val updatedPath = pathPattern.updatePathParameter(originalPath, parameterName, value) ?: return request
        pathPattern.matches(updatedPath, scenario.resolver).throwOnFailure()
        return request.copy(path = updatedPath)
    }
}