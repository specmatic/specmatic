package io.specmatic.core

import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.mock.ScenarioStub

data class NamedStub(val name: String, val shortName: String, val stub: ScenarioStub) {
    constructor(name: String, stub: ScenarioStub) : this(name, name, stub)
    val protocol: SpecmaticProtocol
        get() = stub.protocol
}