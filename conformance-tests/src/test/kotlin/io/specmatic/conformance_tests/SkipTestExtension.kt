package io.specmatic.conformance_tests

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File

class SkipTestExtension : ExecutionCondition {

    private val yamlMapper = ObjectMapper(YAMLFactory())

    private val methodToXField = mapOf(
        "loop tests should succeed" to "x-specmatic-expect-failure-loop",
        "should only exercise all operations in the openAPI spec and not make any additional non-compliant requests" to "x-specmatic-expect-failure-operations",
        "should send valid request bodies" to "x-specmatic-expect-failure-request-bodies",
        "should return valid response bodies" to "x-specmatic-expect-failure-response-bodies"
    )

    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        // Only evaluate for test methods, not at the class level
        // Check if requiredTestMethod is available (only present at method level)
        val testMethod = try {
            context.requiredTestMethod
        } catch (e: Exception) {
            return ConditionEvaluationResult.enabled("Not at test method level")
        }

        val methodName = testMethod.name
        val testInstance = try {
            context.requiredTestInstance
        } catch (e: Exception) {
            return ConditionEvaluationResult.enabled("Could not get test instance")
        }

        // Get the original spec file path from the AbstractConformanceTest instance
        val specFile = try {
            val field = testInstance.javaClass.superclass.getDeclaredField("openAPISpecFile")
            field.isAccessible = true
            field.get(testInstance) as? String ?: return ConditionEvaluationResult.enabled("Could not access spec file")
        } catch (e: Exception) {
            return ConditionEvaluationResult.enabled("Could not read spec file from test instance: ${e.message}")
        }

        val xFieldValue = readXField(specFile, methodName)

        return if (xFieldValue == true) {
            val xFieldName = methodToXField[methodName] ?: "unknown"
            ConditionEvaluationResult.disabled("Skipping due to $xFieldName: true")
        } else {
            ConditionEvaluationResult.enabled("Test should run")
        }
    }

    private fun readXField(specPath: String, methodName: String): Boolean? {
        return try {
            val specFile = File("src/test/resources/specs/$specPath")
            if (!specFile.exists()) return null

            val yaml = yamlMapper.readTree(specFile)
            val xFieldName = methodToXField[methodName] ?: return null

            yaml.get(xFieldName)?.asBoolean()
        } catch (e: Exception) {
            null
        }
    }
}
