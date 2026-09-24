package io.specmatic.core.lifecycle

import io.specmatic.commons.shutdown.CoreShutdownIntent
import io.specmatic.commons.shutdown.ShutdownTask
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.license.core.Executor
import io.specmatic.license.core.util.LicenseConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class SpecmaticLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    @Nested
    inner class Configuration {
        @ParameterizedTest(name = "supports {0}")
        @ValueSource(strings = ["--config", "--config="])
        fun `uses config supplied in command line`(configOption: String) {
            val configFile = tempDir.resolve("custom-specmatic.yaml").apply {
                toFile().writeText("disableTelemetry: true")
            }

            val unselectedConfigFile = tempDir.resolve("unselected-specmatic.yaml").apply {
                toFile().writeText("disableTelemetry: false")
            }

            val originalConfigFilePath = System.getProperty(CONFIG_FILE_PATH)
            val originalShipDisabled = LicenseConfig.instance.utilization.shipDisabled
            try {
                System.setProperty(CONFIG_FILE_PATH, unselectedConfigFile.toString())
                LicenseConfig.instance.utilization.shipDisabled = false

                val args = if (configOption.endsWith('=')) {
                    listOf("test", "$configOption${configFile}")
                } else {
                    listOf("test", configOption, configFile.toString())
                }

                SpecmaticLifecycle.initialize(Executor.PROGRAMMATIC, args)
                assertThat(LicenseConfig.instance.utilization.shipDisabled).isTrue()
                assertThat(System.getProperty(CONFIG_FILE_PATH)).isEqualTo(unselectedConfigFile.toString())
            } finally {
                restoreSystemProperty(CONFIG_FILE_PATH, originalConfigFilePath)
                LicenseConfig.instance.utilization.shipDisabled = originalShipDisabled
            }
        }

        @Test
        fun `ignores malformed config option during bootstrap`() {
            val fallbackConfigFile = tempDir.resolve("fallback-specmatic.yaml").apply {
                toFile().writeText("disableTelemetry: true")
            }

            val originalConfigFilePath = System.getProperty(CONFIG_FILE_PATH)
            val originalShipDisabled = LicenseConfig.instance.utilization.shipDisabled
            try {
                System.setProperty(CONFIG_FILE_PATH, fallbackConfigFile.toString())
                LicenseConfig.instance.utilization.shipDisabled = false

                SpecmaticLifecycle.initialize(Executor.PROGRAMMATIC, listOf("test", "--config"))
                assertThat(LicenseConfig.instance.utilization.shipDisabled).isTrue()
                assertThat(System.getProperty(CONFIG_FILE_PATH)).isEqualTo(fallbackConfigFile.toString())
            } finally {
                restoreSystemProperty(CONFIG_FILE_PATH, originalConfigFilePath)
                LicenseConfig.instance.utilization.shipDisabled = originalShipDisabled
            }
        }

        @Suppress("SameParameterValue")
        private fun restoreSystemProperty(name: String, value: String?) {
            if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        }
    }

    @Nested
    inner class Ordering {
        @Test
        fun `runs shutdown phases in intent order`() {
            val events = CopyOnWriteArrayList<String>()
            val registrar = SpecmaticLifecycleRegistrar()

            registrar.register(task("general-first", events))
            registrar.register(task("prepare", events, CoreShutdownIntent.PREPARE_DATA))

            registrar.register(task("general-second", events))
            registrar.register(task("publish", events, CoreShutdownIntent.PUBLISH_DATA))

            registrar.shutdown()

            assertThat(events).hasSize(4)
            assertThat(setOf(events[0], events[1])).isEqualTo(setOf("general-first", "general-second"))
            assertThat(events[2]).isEqualTo("prepare")
            assertThat(events[3]).isEqualTo("publish")
        }

        @Test
        fun `runs reporter and license concurrently after regular tasks complete`() {
            val regularCompleted = AtomicBoolean(false)

            val reporterStarted = CountDownLatch(1)
            val reporterRanAfterRegular = AtomicBoolean(false)
            val reporterObservedLicense = AtomicBoolean(false)

            val licenseStarted = CountDownLatch(1)
            val licenseRanAfterRegular = AtomicBoolean(false)
            val licenseObservedReporter = AtomicBoolean(false)

            val registrar = SpecmaticLifecycleRegistrar()
            registrar.register(
                task = ShutdownTask(
                    id = "regular",
                    intent = CoreShutdownIntent.GENERAL,
                    action = { regularCompleted.set(true) },
                ),
            )

            registrar.register(
                task = ShutdownTask(
                    id = "reporter",
                    intent = CoreShutdownIntent.PUBLISH_DATA,
                    action = {
                        reporterRanAfterRegular.set(regularCompleted.get())
                        reporterStarted.countDown()
                        reporterObservedLicense.set(licenseStarted.await(5, SECONDS))
                    },
                ),
            )

            registrar.register(
                task = ShutdownTask(
                    id = "license",
                    intent = CoreShutdownIntent.PUBLISH_DATA,
                    action = {
                        licenseRanAfterRegular.set(regularCompleted.get())
                        licenseStarted.countDown()
                        licenseObservedReporter.set(reporterStarted.await(5, SECONDS))
                    },
                ),
            )

            registrar.shutdown()
            assertThat(reporterRanAfterRegular.get()).isTrue()
            assertThat(licenseRanAfterRegular.get()).isTrue()
            assertThat(reporterObservedLicense.get()).isTrue()
            assertThat(licenseObservedReporter.get()).isTrue()
        }
    }

    @Nested
    inner class Execution {
        @Test
        fun `runs each task once and continues when a task fails`() {
            val events = CopyOnWriteArrayList<String>()
            val registrar = SpecmaticLifecycleRegistrar()

            registrar.register(task = task("failing", events) { error("boom") })
            registrar.register(task("following", events))

            registrar.shutdown()
            registrar.shutdown()

            assertThat(events).containsExactlyInAnyOrder("failing", "following")
        }
    }

    @Nested
    inner class Registration {
        @Test
        fun `can retry hook installation after a non shutdown installation failure`() {
            val events = CopyOnWriteArrayList<String>()
            val registrar = SpecmaticLifecycleRegistrar()
            registrar.register(task("task", events))

            assertThatThrownBy { registrar.installShutdownHook { throw SecurityException("shutdown hook permission denied") } }
                .isExactlyInstanceOf(SecurityException::class.java)
                .hasMessage("shutdown hook permission denied")

            var installedHooks = 0
            registrar.installShutdownHook { installedHooks++ }
            registrar.shutdown()

            assertThat(installedHooks).isEqualTo(1)
            assertThat(events).containsExactly("task")
        }

        @Test
        fun `runs shutdown immediately when hook installation reports that shutdown has started`() {
            val events = CopyOnWriteArrayList<String>()
            val registrar = SpecmaticLifecycleRegistrar()
            registrar.register(task("task", events))

            registrar.installShutdownHook { throw IllegalStateException("JVM shutdown has started") }
            assertThat(events).containsExactly("task")

            registrar.shutdown()
            assertThat(events).containsExactly("task")
        }

        @Test
        fun `does not install a hook after shutdown has started`() {
            val registrar = SpecmaticLifecycleRegistrar()
            registrar.shutdown()

            var installedHooks = 0
            registrar.installShutdownHook { installedHooks++ }
            assertThat(installedHooks).isZero()
        }

        @Test
        fun `installs one hook`() {
            val installedHooks = AtomicInteger()
            val registrar = SpecmaticLifecycleRegistrar()

            registrar.installShutdownHook { installedHooks.incrementAndGet() }
            registrar.installShutdownHook { installedHooks.incrementAndGet() }
            assertThat(installedHooks.get()).isEqualTo(1)
        }

        @Test
        fun `allows multiple tasks with the same id`() {
            val events = CopyOnWriteArrayList<String>()
            val registrar = SpecmaticLifecycleRegistrar()

            registrar.register(task("mock", events))
            registrar.register(task("mock", events))

            registrar.shutdown()
            assertThat(events).containsExactly("mock", "mock")
        }

        @Test
        fun `closing a registration removes only its task`() {
            val events = CopyOnWriteArrayList<String>()
            val registrar = SpecmaticLifecycleRegistrar()

            val firstRegistration = registrar.register(task("first", events))
            registrar.register(task("second", events))

            firstRegistration.close()
            firstRegistration.close()
            registrar.shutdown()

            assertThat(events).containsExactly("second")
        }

        @Test
        fun `rejects registrations after shutdown starts`() {
            val registrar = SpecmaticLifecycleRegistrar()
            registrar.shutdown()
            assertThatThrownBy { registrar.register(task("late", mutableListOf())) }
                .isExactlyInstanceOf(IllegalStateException::class.java)
                .hasMessage("Cannot register shutdown task after shutdown has started")
        }
    }

    private fun task(
        id: String,
        events: MutableList<String>,
        intent: CoreShutdownIntent = CoreShutdownIntent.GENERAL,
        action: () -> Unit = {},
    ) = ShutdownTask(
        id = id,
        intent = intent,
        action = {
            events += id
            action()
        },
    )
}
