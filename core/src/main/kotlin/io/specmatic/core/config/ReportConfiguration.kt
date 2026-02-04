package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.specmatic.core.config.SpecmaticConfigVersion.VERSION_1

const val excludedEndpointsWarning =
    "WARNING: excludedEndpoints is not supported in Specmatic config v2. . Refer to https://specmatic.io/documentation/configuration.html#report-configuration to see how to exclude endpoints."

interface ReportConfiguration {
    fun getSuccessCriteria(): SuccessCriteria
    fun excludedOpenAPIEndpoints(): List<String>

    companion object {
        val default = ReportConfigurationDetails(
           types = ReportTypes()
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReportConfigurationDetails(
    val types: ReportTypes? = null
) : ReportConfiguration {

    fun validatePresenceOfExcludedEndpoints(currentVersion: SpecmaticConfigVersion): ReportConfigurationDetails {
        if(currentVersion.isLessThanOrEqualTo(VERSION_1))
            return this

        if (types?.apiCoverage?.openAPI?.excludedEndpoints.orEmpty().isNotEmpty()) {
            throw UnsupportedOperationException(excludedEndpointsWarning)
        }
        return this
    }

    fun clearPresenceOfExcludedEndpoints(): ReportConfigurationDetails {
        return this.copy(
            types = types?.copy(
                apiCoverage = types.apiCoverage?.copy(
                    openAPI = types.apiCoverage.openAPI?.copy(
                        excludedEndpoints = emptyList()
                    )
                )
            )
        )
    }

    @JsonIgnore
    override fun getSuccessCriteria(): SuccessCriteria {
        return types?.apiCoverage?.openAPI?.successCriteria ?: SuccessCriteria.default
    }

    @JsonIgnore
    override fun excludedOpenAPIEndpoints(): List<String> {
        return types?.apiCoverage?.openAPI?.excludedEndpoints ?: emptyList()
    }
}

data class ReportTypes(
    @param:JsonProperty("APICoverage")
    val apiCoverage: APICoverage? = null
)

data class APICoverage(
    @param:JsonProperty("OpenAPI")
    val openAPI: APICoverageConfiguration? = null
)

data class APICoverageConfiguration(
    val successCriteria: SuccessCriteria? = null,
    val excludedEndpoints: List<String>? = null
)

data class SuccessCriteria(
    val minThresholdPercentage: Int? = null,
    val maxMissedEndpointsInSpec: Int? = null,
    val enforce: Boolean? = null
) {
    companion object {
        val default = SuccessCriteria(0, 0, false)
    }

    @JsonIgnore
    fun getMinThresholdPercentageOrDefault(): Int {
        return minThresholdPercentage ?: 0
    }

    @JsonIgnore
    fun getMaxMissedEndpointsInSpecOrDefault(): Int {
        return maxMissedEndpointsInSpec ?: 0
    }

    @JsonIgnore
    fun getEnforceOrDefault(): Boolean {
        return enforce ?: false
    }
}
