package io.specmatic.conformance_tests

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.conformance_test_support.ConformanceTestRecord
import io.specmatic.conformance_test_support.ConformanceTestStatus
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.opentest4j.AssertionFailedError
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

class ConformanceTestResultExtension(private val findExtension: (String) -> String?) : InvocationInterceptor {
    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext
    ) {
        val succeedOnExpectedFailures = System.getProperty("succeedOnExpectedFailures")?.toBoolean() == true
        val annotation = extensionContext.requiredTestMethod.getAnnotation(ExpectFailureTag::class.java)
        val tag = annotation?.tag
        val failureReason = tag?.let { findExtension(it) }
        val expectedFailureTag = tag.takeIf { failureReason != null }
        val specRef = findExtension(SPEC_REF_KEY)?.takeIf { it.isNotBlank() }
        val displayName = extensionContext.parent.map { it.displayName }.orElse("")!!
        val testClass = extensionContext.requiredTestClass.name
        val testMethod = extensionContext.displayName

        if (failureReason != null && failureReason.isBlank()) {
            throw AssertionFailedError(
                "Spec declares `$tag` but has no failure reason. Add a reason to the spec file, e.g. `$tag: \"short explanation\"`."
            )
        }

        val invocationError: Throwable? = try {
            invocation.proceed()
            null
        } catch (t: Throwable) {
            t
        }

        when {
            invocationError != null && failureReason != null -> {
                logRecord(
                    ConformanceTestRecord(
                        status = ConformanceTestStatus.EXPECTED_FAILURE,
                        tag = expectedFailureTag,
                        displayName = displayName,
                        testClass = testClass,
                        testMethod = testMethod,
                        reason = failureReason,
                        specRef = specRef,
                    )
                )
                if (!succeedOnExpectedFailures) {
                    throw invocationError
                }
            }

            invocationError != null -> {
                logRecord(
                    ConformanceTestRecord(
                        status = ConformanceTestStatus.UNEXPECTED_FAILURE,
                        displayName = displayName,
                        testClass = testClass,
                        testMethod = testMethod,
                        specRef = specRef,
                        failureMessage = invocationError.message,
                    )
                )
                throw invocationError
            }

            failureReason != null -> {
                logRecord(
                    ConformanceTestRecord(
                        status = ConformanceTestStatus.UNEXPECTED_PASS,
                        tag = expectedFailureTag,
                        displayName = displayName,
                        testClass = testClass,
                        testMethod = testMethod,
                        reason = failureReason,
                        specRef = specRef,
                    )
                )
                throw AssertionFailedError(
                    "Test passed unexpectedly! The bug appears to be fixed. Remove `$tag` from the spec file."
                )
            }

            else -> {
                logRecord(
                    ConformanceTestRecord(
                        status = ConformanceTestStatus.PASSED,
                        displayName = displayName,
                        testClass = testClass,
                        testMethod = testMethod,
                        specRef = specRef,
                    )
                )
            }
        }
    }

    private fun logRecord(record: ConformanceTestRecord) {
        testResultsLogger.info(jsonMapper.writeValueAsString(record))
    }

    companion object {
        private const val SPEC_REF_KEY = "x-specmatic-spec-ref"
        private val jsonMapper = ObjectMapper()
        private val testResultsLogger = LoggerFactory.getLogger("conformance.test-results")
    }
}
