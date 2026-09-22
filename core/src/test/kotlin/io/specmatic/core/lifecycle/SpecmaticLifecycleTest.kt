package io.specmatic.core.lifecycle

import io.specmatic.commons.shutdown.LicenseShutdownIntent
import io.specmatic.commons.shutdown.ShutdownTask
import io.specmatic.reporter.ReporterShutdownIntent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class SpecmaticLifecycleTest {
    @Nested
    inner class Ordering {
        @Test
        fun `runs regular tasks sequentially in registration order with reporter and license last`() {
            val events = Collections.synchronizedList(mutableListOf<String>())
            val registrar = SpecmaticLifecycleRegistrar()

            registrar.register(task("mock", events))
            registrar.register(task("proxy", events))

            registrar.register(
                task = ShutdownTask(
                    id = "license",
                    action = { events += "license" },
                    intent = LicenseShutdownIntent.UTILIZATION_TRACKER,
                ),
            )

            registrar.register(
                task = ShutdownTask(
                    id = "reporter",
                    action = { events += "reporter" },
                    intent = ReporterShutdownIntent.REPORT_TRACKER,
                ),
            )

            registrar.register(task("late-mock", events))

            registrar.shutdown()
            assertThat(events.subList(0, 3)).containsExactly("mock", "proxy", "late-mock")
            assertThat(events.subList(3, 5)).containsExactlyInAnyOrder("reporter", "license")
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
                    intent = SpecmaticShutdownIntent.MOCK,
                    action = { regularCompleted.set(true) },
                ),
            )

            registrar.register(
                task = ShutdownTask(
                    id = "reporter",
                    intent = ReporterShutdownIntent.REPORT_TRACKER,
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
                    intent = LicenseShutdownIntent.UTILIZATION_TRACKER,
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
            val events = mutableListOf<String>()
            val registrar = SpecmaticLifecycleRegistrar()

            registrar.register(task = task("failing", events) { error("boom") })
            registrar.register(task("following", events))

            registrar.shutdown()
            registrar.shutdown()

            assertThat(events).containsExactly("failing", "following")
        }
    }

    @Nested
    inner class Registration {
        @Test
        fun `can retry hook installation after a non shutdown installation failure`() {
            val events = mutableListOf<String>()
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
            val events = mutableListOf<String>()
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
            val events = mutableListOf<String>()
            val registrar = SpecmaticLifecycleRegistrar()

            registrar.register(task("mock", events))
            registrar.register(task("mock", events))

            registrar.shutdown()
            assertThat(events).containsExactly("mock", "mock")
        }

        @Test
        fun `closing a registration removes only its task`() {
            val events = mutableListOf<String>()
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

    private fun task(id: String, events: MutableList<String>, action: () -> Unit = {}) = ShutdownTask(
        id = id,
        intent = SpecmaticShutdownIntent.MOCK,
        action = {
            events += id
            action()
        },
    )
}
