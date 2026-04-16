package io.specmatic.conformance_tests

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ExpectFailureTag(val tag: String)
