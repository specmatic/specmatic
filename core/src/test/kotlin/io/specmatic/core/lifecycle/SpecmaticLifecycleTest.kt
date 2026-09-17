package io.specmatic.core.lifecycle

import io.specmatic.commons.shutdown.LicenseShutdownIntent
import io.specmatic.commons.shutdown.ShutdownTask
import io.specmatic.reporter.ReporterShutdownIntent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SpecmaticLifecycleTest {
    @Nested
    inner class Ordering {
        @Test
        fun `runs registered tasks sequentially in registration order with reporter and license last`() {
            val events = mutableListOf<String>()
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
            assertThat(events).containsExactly("mock", "proxy", "late-mock", "reporter", "license")
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
