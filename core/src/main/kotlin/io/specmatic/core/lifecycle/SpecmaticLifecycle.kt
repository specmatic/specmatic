package io.specmatic.core.lifecycle

import io.specmatic.commons.shutdown.ShutdownRegistrar
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.loadSpecmaticConfigOrDefaultCatching
import io.specmatic.core.log.logger
import io.specmatic.license.core.Executor
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.util.LicenseConfig
import io.specmatic.reporter.ReportTracker
import io.specmatic.reporter.commands.InsightsReportOptionsWithConfig
import picocli.CommandLine

object SpecmaticLifecycle {
    private val initializationLock = Any()
    private var lifecycleComponentsRegistered = false
    private val registrar = SpecmaticLifecycleRegistrar()

    val shutdownRegistrar: ShutdownRegistrar
        get() = registrar

    @JvmStatic
    fun initialize(executor: Executor, args: List<String>) {
        val specmaticConfigFilePath = configFilePathFrom(args)
        val specmaticConfig = loadSpecmaticConfigOrDefaultCatching(configFileName = specmaticConfigFilePath)
        initialize(executor, specmaticConfig)
    }

    @JvmStatic
    fun initialize(executor: Executor, specmaticConfig: SpecmaticConfig = loadSpecmaticConfigOrDefaultCatching()) {
        synchronized(initializationLock) {
            configureExecutor(executor)
            configureTelemetry(specmaticConfig)

            if (lifecycleComponentsRegistered) {
                logger.debug("Specmatic lifecycle already initialized")
                return
            }

            logger.debug("Initializing Specmatic lifecycle")
            registerShutdownComponents()
            lifecycleComponentsRegistered = true
        }
    }

    private fun configFilePathFrom(args: List<String>): String? {
        val options = InsightsReportOptionsWithConfig()
        return try {
            CommandLine(options).setUnmatchedArgumentsAllowed(true).parseArgs(*args.toTypedArray())
            options.configPath
        } catch (_: Throwable) {
            null
        }
    }

    private fun configureExecutor(executor: Executor) {
        LicenseResolver.setCurrentExecutorIfNotSet(executor)
    }

    private fun configureTelemetry(specmaticConfig: SpecmaticConfig) {
        LicenseConfig.instance.utilization.shipDisabled =
            LicenseConfig.instance.utilization.shipDisabled || specmaticConfig.isTelemetryDisabled()
    }

    private fun registerShutdownComponents() {
        try {
            ReportTracker.registerShutdown(registrar)
            LicenseResolver.registerShutdown(registrar)
            registrar.installShutdownHook()
        } catch (e: Throwable) {
            registrar.shutdown()
            throw e
        }
    }
}
