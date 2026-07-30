package io.specmatic.core

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.mock.ScenarioStub

data class NamedStubSource(val operationPointer: String, val requestVariantPointer: String, val responseVariantPointer: String)
data class NamedStub(
    val name: String,
    val shortName: String,
    val stub: ScenarioStub,
    val source: NamedStubSource? = null,
) {
    constructor(name: String, stub: ScenarioStub) : this(name, name, stub)
    constructor(name: String, stub: ScenarioStub, source: NamedStubSource?) : this(name, name, stub, source)

    val protocol: SpecmaticProtocol
        get() = stub.protocol
}
