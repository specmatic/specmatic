package io.specmatic.core.config

import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.openapi.IRunOptionSpecification
import io.specmatic.core.config.v3.components.runOptions.openapi.OpenApiMockRunOptions
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.utilities.Flags

const val SPECMATIC_STUB_DICTIONARY = "SPECMATIC_STUB_DICTIONARY"

data class StubConfiguration(
    private val generative: Boolean? = null,
    private val delayInMilliseconds: Long? = null,
    private val dictionary: String? = null,
    private val includeMandatoryAndRequestedKeysInResponse: Boolean? = null,
    private val startTimeoutInMilliseconds: Long? = null,
    private val hotReload: Switch? = null,
    private val strictMode: Boolean? = null,
    private val baseUrl: String? = null,
    private val customImplicitStubBase: String? = null,
    private val filter: String? = null,
    private val gracefulRestartTimeoutInMilliseconds: Long? = null,
    private val https: HttpsConfiguration? = null,
    private val lenientMode: Boolean? = null,
    private val overlayFilePath: String? = null,
    private val urlPathPrefix: String? = null,
) {
    constructor(
        mockSettings: MockSettings? = null,
        dictionary: String? = null,
        runOptions: IRunOptions? = null,
        cert: HttpsConfiguration? = null,
        runOptionSpecification: IRunOptionSpecification? = null,
        specificationDefinition: SpecificationDefinition? = null,
    ) : this(
        https = cert,
        dictionary = dictionary,
        hotReload = mockSettings?.hotReload,
        generative = mockSettings?.generative,
        strictMode = mockSettings?.strictMode,
        lenientMode = mockSettings?.lenientMode,
        delayInMilliseconds = mockSettings?.delayInMilliseconds,
        startTimeoutInMilliseconds = mockSettings?.startTimeoutInMilliseconds,
        gracefulRestartTimeoutInMilliseconds = mockSettings?.gracefulRestartTimeoutInMilliseconds,
        baseUrl = (runOptions as? OpenApiMockRunOptions)?.baseUrl,
        filter = runOptionSpecification?.getFilter(),
        urlPathPrefix = specificationDefinition?.getUrlPathPrefix(),
        overlayFilePath = runOptionSpecification?.getOverlayFilePath(),
    )

    fun getGenerative(): Boolean? {
        return generative
    }

    fun getDelayInMilliseconds(): Long? {
        return delayInMilliseconds ?: Flags.getLongValue(Flags.SPECMATIC_STUB_DELAY)
    }

    fun getDictionary(): String? {
        return dictionary ?: Flags.getStringValue(SPECMATIC_STUB_DICTIONARY)
    }

    fun getIncludeMandatoryAndRequestedKeysInResponse(): Boolean? {
        return includeMandatoryAndRequestedKeysInResponse
    }

    fun getStartTimeoutInMilliseconds(): Long? {
        return startTimeoutInMilliseconds
    }

    fun getHotReload(): Switch? {
        return hotReload
    }

    fun getStrictMode(): Boolean {
        return strictMode ?: Flags.getBooleanValue(Flags.STUB_STRICT_MODE, false)
    }

    fun getFilter(): String? {
        return filter
    }

    fun getHttpsConfiguration(): HttpsConfiguration? {
        return https
    }

    fun getGracefulRestartTimeoutInMilliseconds(): Long? {
        return gracefulRestartTimeoutInMilliseconds
    }

    fun getBaseUrl(): String? {
        return baseUrl
    }

    fun getCustomImplicitStubBase(): String? {
        return customImplicitStubBase
    }
}
