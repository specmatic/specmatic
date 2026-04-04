package io.specmatic.core.log

import io.specmatic.core.pattern.ContractException

object LoggedFailureScope {
    private val loggedFailures = ScopedIdentitySet<Throwable>()

    fun markLogged(exception: Throwable) {
        loggedFailures.add(exception)
    }

    fun wasLogged(exception: ContractException): Boolean {
        return loggedFailures.anyMatch(exception.contractExceptionChain())
    }

    fun wasLogged(throwable: Throwable): Boolean {
        return throwable.throwableChain()
            .filterIsInstance<ContractException>()
            .any(::wasLogged)
    }

    fun <R> withFreshScope(fn: () -> R): R {
        return loggedFailures.withFreshScope(fn)
    }

    private fun ContractException.contractExceptionChain(): Sequence<ContractException> {
        return generateSequence(this) { current -> current.exceptionCause as? ContractException }
    }

    private fun Throwable.throwableChain(): Sequence<Throwable> {
        return generateSequence(this) { current -> current.cause }
    }
}
