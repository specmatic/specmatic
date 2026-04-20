package io.specmatic.conformance_tests

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.opentest4j.AssertionFailedError
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

        try {
            invocation.proceed()
        } catch (e: Throwable) {
            println()
            println("Expected failure: $failureReason")
            println("Test: ${extensionContext.displayName}")
//            println("Failure message: ${e.message}")
            println("Status: Test failed as expected (known issue)")
            println()
            return
        }

        throw AssertionFailedError(
            "Test passed unexpectedly! The bug appears to be fixed. Remove `${annotation.tag}` from the spec file."
        )
    }
}
