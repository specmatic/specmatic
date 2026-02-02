package io.specmatic.core.config.v3.components

import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.settings.TestSettings

data class CommonServiceConfig<RunOptions : Any, Settings: Any>(
    val description: String? = null,
    val definitions: List<Definition>,
    val runOptions: RefOrValue<RunOptions>? = null,
    val data: Data? = null,
    val settings: RefOrValue<Settings>? = null
)

data class TestServiceConfig(val service: RefOrValue<CommonServiceConfig<TestRunOptions, TestSettings>>)
data class MockServiceConfig(val services: List<Value>, val data: Data? = null, val settings: RefOrValue<MockSettings>?) {
    data class Value(val service: RefOrValue<CommonServiceConfig<MockRunOptions, MockSettings>>)
}
