package br.com.felipezorzo.zpa.cli.exporters

import br.com.felipezorzo.zpa.cli.InputFile
import br.com.felipezorzo.zpa.cli.sarif.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.felipebz.zpa.squid.ZpaIssue
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Exports issues as a SARIF 2.1.0 log (https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/sarif-v2.1.0-os.html),
 * consumable by tools such as GitHub code scanning (`upload-sarif`).
 */
class SarifExporter(
    private val outputFile: String = "",
    private val toolVersion: String? = SarifExporter::class.java.`package`?.implementationVersion,
    private val out: PrintStream = System.out
) : IssueExporter {

    override fun export(issues: List<ZpaIssue>) {
        val rules = issues
            .map { it.check.activeRule }
            .distinctBy { it.ruleKey.toString() }
            .sortedBy { it.ruleKey.toString() }
            .map { activeRule ->
                SarifRule(
                    id = activeRule.ruleKey.toString(),
                    shortDescription = SarifText(activeRule.name),
                    properties = activeRule.tags.takeIf { it.isNotEmpty() }?.let { SarifRuleProperties(it.toList()) }
                )
            }

        val results = issues.map { issue ->
            val relativePath = (issue.file as InputFile).pathRelativeToBase
            val loc = issue.primaryLocation
            val startLine = loc.startLine()
            val startColumn = loc.startLineOffset()
            val endLine = loc.endLine()
            val endColumn = loc.endLineOffset()

            // SARIF regions are 1-based on both axes; ZPA's columns are 0-based, so they need a +1 shift here
            // (unlike the "json"/"sq-generic-issue-import" formats, which keep ZPA's own 0-based columns as-is).
            val region = if (startLine <= 0) {
                null
            } else {
                SarifRegion(
                    startLine = startLine,
                    startColumn = if (startColumn >= 0) startColumn + 1 else null,
                    endLine = if (endLine > 0) endLine else null,
                    endColumn = if (endColumn >= 0) endColumn + 1 else null
                )
            }

            SarifResult(
                ruleId = issue.check.activeRule.ruleKey.toString(),
                level = sarifLevel(issue.check.activeRule.severity),
                message = SarifText(loc.message()),
                locations = listOf(
                    SarifLocation(
                        SarifPhysicalLocation(
                            artifactLocation = SarifArtifactLocation(relativePath),
                            region = region
                        )
                    )
                )
            )
        }

        val log = SarifLog(
            runs = listOf(
                SarifRun(
                    tool = SarifTool(SarifDriver(version = toolVersion, rules = rules)),
                    results = results
                )
            )
        )

        val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
        val jsonString = mapper.writeValueAsString(log)

        if (outputFile.isNotEmpty()) {
            val file = File(outputFile)
            file.parentFile?.mkdirs()
            file.writeText(jsonString, StandardCharsets.UTF_8)
        } else {
            out.println(jsonString)
        }
    }

    private fun sarifLevel(severity: String): String = when (severity) {
        "BLOCKER", "CRITICAL" -> "error"
        "MAJOR" -> "warning"
        else -> "note"
    }
}
