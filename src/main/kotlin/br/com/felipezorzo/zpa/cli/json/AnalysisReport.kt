package br.com.felipezorzo.zpa.cli.json

import com.fasterxml.jackson.annotation.JsonInclude

enum class DiagnosticKind {
    SYNTAX,
    RULE
}

data class ValidationSummary(
    val passed: Boolean,
    val threshold: String
)

data class AnalysisReport(
    val schemaVersion: Int = 1,
    val validation: ValidationSummary,
    val diagnostics: List<Diagnostic>
)

data class Diagnostic(
    val kind: String,
    val file: String,
    val range: DiagnosticRange?,
    val rule: String,
    val severity: String,
    val message: String,
    /** Automatic corrections for the diagnostic. Null, and therefore omitted from the report, if there are none. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val quickFixes: List<DiagnosticQuickFix>? = null
)

data class DiagnosticRange(
    val startLine: Int? = null,
    val startColumn: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null
)

/** An automatic correction: all [edits] refer to the original file and are applied together. */
data class DiagnosticQuickFix(
    val message: String,
    val edits: List<DiagnosticTextEdit>
)

/**
 * Replaces a range of the file with [text], using the coordinates of [DiagnosticRange]: lines are 1-based,
 * columns are 0-based and [endColumn] is exclusive. An empty [text] deletes the range, an empty range inserts [text].
 */
data class DiagnosticTextEdit(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val text: String
)
