package br.com.felipezorzo.zpa.cli.sarif

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder

/** Minimal SARIF 2.1.0 log: https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/sarif-v2.1.0-os.html */
@JsonPropertyOrder("\$schema", "version", "runs")
data class SarifLog(
    @get:JsonProperty("\$schema")
    val schema: String = "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json",
    val version: String = "2.1.0",
    val runs: List<SarifRun>
)

data class SarifRun(
    val tool: SarifTool,
    val results: List<SarifResult>
)

data class SarifTool(
    val driver: SarifDriver
)

data class SarifDriver(
    val name: String = "ZPA",
    val informationUri: String = "https://github.com/felipebz/zpa",
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val version: String? = null,
    val rules: List<SarifRule>
)

data class SarifRule(
    val id: String,
    val shortDescription: SarifText,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val properties: SarifRuleProperties? = null
)

data class SarifRuleProperties(
    val tags: List<String>
)

data class SarifText(
    val text: String
)

data class SarifResult(
    val ruleId: String,
    val level: String,
    val message: SarifText,
    val locations: List<SarifLocation>
)

data class SarifLocation(
    val physicalLocation: SarifPhysicalLocation
)

data class SarifPhysicalLocation(
    val artifactLocation: SarifArtifactLocation,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val region: SarifRegion? = null
)

data class SarifArtifactLocation(
    val uri: String
)

data class SarifRegion(
    val startLine: Int,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val startColumn: Int? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val endLine: Int? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val endColumn: Int? = null
)
