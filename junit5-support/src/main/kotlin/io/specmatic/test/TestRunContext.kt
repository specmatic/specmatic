package io.specmatic.test

data class TestRunContext(val skipExcludedCountTracker: SkipExcludedCountTracker = SkipExcludedCountTracker())
object TestRunContextHolder {
    private val context = ThreadLocal<TestRunContext?>()

    fun set(runContext: TestRunContext?) {
        context.set(runContext)
    }

    fun get(): TestRunContext? {
        return context.get()
    }

    fun clear() {
        context.remove()
    }
}
