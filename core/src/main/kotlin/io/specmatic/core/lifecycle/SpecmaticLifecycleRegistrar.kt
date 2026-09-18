package io.specmatic.core.lifecycle

import io.specmatic.commons.shutdown.LicenseShutdownIntent
import io.specmatic.commons.shutdown.ShutdownIntent
import io.specmatic.commons.shutdown.ShutdownRegistrar
import io.specmatic.commons.shutdown.ShutdownRegistration
import io.specmatic.commons.shutdown.ShutdownTask
import io.specmatic.core.log.logger
import io.specmatic.reporter.ReporterShutdownIntent
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private class SpecmaticLifecycleRegistration(
    private val taskId: Long,
    private val registrar: SpecmaticLifecycleRegistrar,
) : ShutdownRegistration {
    override fun close() {
        registrar.unregister(taskId)
    }
}

typealias ShutdownHookInstaller = (Thread) -> Unit
internal class SpecmaticLifecycleRegistrar: ShutdownRegistrar {
    private val lock = ReentrantLock()
    private var nextRegistrationId = 0L
    private var state = LifecycleState.ACTIVE
    private val registrations = LinkedHashMap<Long, ShutdownTask>()
    private val shutdownHook = Thread({ shutdown() }, "specmatic-shutdown")

    fun installShutdownHook(shutdownHookInstaller: ShutdownHookInstaller = defaultShutdownHookInstaller) {
        val shutdownImmediately = lock.withLock { installHookIfNeeded(shutdownHookInstaller) }
        if (shutdownImmediately) shutdown()
    }

    private fun installHookIfNeeded(shutdownHookInstaller: ShutdownHookInstaller): Boolean {
        when (state) {
            LifecycleState.HOOK_INSTALLED -> {
                logger.debug("Specmatic shutdown hook already installed")
                return false
            }

            LifecycleState.SHUTTING_DOWN -> {
                logger.debug("Specmatic shutdown already started; shutdown hook will not be installed")
                return false
            }

            LifecycleState.ACTIVE -> Unit
        }

        logger.debug("Installing Specmatic shutdown hook")
        return try {
            shutdownHookInstaller(shutdownHook)
            markHookInstalled()
            false
        } catch (e: IllegalStateException) {
            logger.debug(e, "JVM shutdown has started; running Specmatic shutdown immediately")
            true
        } catch (e: Throwable) {
            logger.debug(e, "Failed to install Specmatic shutdown hook")
            throw e
        }
    }

    private fun markHookInstalled() {
        if (state == LifecycleState.ACTIVE) {
            state = LifecycleState.HOOK_INSTALLED
        } else {
            logger.debug("Specmatic shutdown started while installing the shutdown hook")
        }
    }

    override fun register(task: ShutdownTask): ShutdownRegistration {
        return lock.withLock {
            check(state != LifecycleState.SHUTTING_DOWN) { "Cannot register shutdown task after shutdown has started" }

            val registrationId = ++nextRegistrationId
            registrations[registrationId] = task
            logger.debug("Registered shutdown task '${task.id}'")
            SpecmaticLifecycleRegistration(taskId = registrationId, registrar = this)
        }
    }

    fun unregister(registrationId: Long) {
        lock.withLock {
            val task = registrations.remove(registrationId) ?: return
            logger.debug("Unregistered shutdown task '${task.id}'")
        }
    }

    fun shutdown() {
        val tasks = lock.withLock { takeShutdownTasks() } ?: return
        logger.debug("Starting Specmatic shutdown with ${tasks.size} task(s)")

        tasks.forEach(::runTask)
        logger.debug("Completed Specmatic shutdown")
    }

    private fun takeShutdownTasks(): List<ShutdownTask>? {
        if (state == LifecycleState.SHUTTING_DOWN) {
            logger.debug("Specmatic shutdown already started")
            return null
        }

        state = LifecycleState.SHUTTING_DOWN
        return registrations.values
            .sortedBy { it.intent.shutdownOrder() }
            .also { registrations.clear() }
    }

    private fun runTask(task: ShutdownTask) {
        logger.debug("Running shutdown task '${task.id}'")
        try {
            task.action.run()
            logger.debug("Completed shutdown task '${task.id}'")
        } catch (e: Throwable) {
            logger.debug(e, "Shutdown task '${task.id}' failed")
        }
    }

    private fun ShutdownIntent.shutdownOrder(): ShutdownOrder = when (this) {
        is ReporterShutdownIntent -> ShutdownOrder.REPORTER
        is LicenseShutdownIntent -> ShutdownOrder.LICENSE
        else -> ShutdownOrder.REGULAR
    }

    private enum class ShutdownOrder { REGULAR, REPORTER, LICENSE }
    private enum class LifecycleState { ACTIVE, HOOK_INSTALLED, SHUTTING_DOWN }
    private companion object {
        val defaultShutdownHookInstaller: ShutdownHookInstaller = { hook ->
            Runtime.getRuntime().addShutdownHook(hook)
        }
    }
}
