package io.specmatic.core.log

class UsesBoundaryImpl : UsesBoundaryWithHelpers {
    private val boundary = ThreadLocal.withInitial { false }

    override fun boundary() {
        boundary.set(true)
    }

    override fun removeBoundary(): Boolean {
        val oldBoundary = boundary.get()
        boundary.set(false)
        return oldBoundary
    }
}
