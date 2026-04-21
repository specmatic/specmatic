package io.specmatic.conformance_tests

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.conformance_test_support.ExpectedFailureRecord
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.opentest4j.AssertionFailedError
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

class ExpectedFailureExtension(private val findFailureReason: (String) -> String?) : InvocationInterceptor {
    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext
    ) {
        if (System.getProperty("succeedOnExpectedFailures")?.toBoolean() != true) {
            invocation.proceed()
            return
        }

        val annotation = extensionContext.requiredTestMethod.getAnnotation(ExpectFailureTag::class.java)
        if (annotation == null) {
            invocation.proceed()
            return
        }

        val failureReason = findFailureReason(annotation.tag)

        if (failureReason == null) {
            invocation.proceed()
            return
        }

        if (failureReason.isBlank()) {
            throw AssertionFailedError(
                "Spec declares `${annotation.tag}` but has no failure reason. Add a reason to the spec file, e.g. `${annotation.tag}: \"short explanation\"`."
            )
        }

        try {
            invocation.proceed()
        } catch (_: Throwable) {
            val record = ExpectedFailureRecord(
                tag = annotation.tag,
                displayName = extensionContext.parent.map { it.displayName }.orElse(""),
                testClass = extensionContext.requiredTestClass.name,
                testMethod = extensionContext.displayName,
                reason = failureReason,
            )
            expectedFailuresLogger.info(jsonMapper.writeValueAsString(record))
            return
        }

        throw AssertionFailedError(
            "Test passed unexpectedly! The bug appears to be fixed. Remove `${annotation.tag}` from the spec file."
        )
    }

    companion object {
        private val jsonMapper = ObjectMapper()
        private val expectedFailuresLogger = LoggerFactory.getLogger("conformance.expected-failures")
    }
}
