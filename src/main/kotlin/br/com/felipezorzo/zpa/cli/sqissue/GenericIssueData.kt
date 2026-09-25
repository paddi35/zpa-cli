package br.com.felipezorzo.zpa.cli.sqissue

import com.fasterxml.jackson.annotation.JsonInclude

data class GenericIssueData(
        val issues: List<Issue>
)

data class Issue(
        val engineId: String = "zpa",
        val ruleId: String,
        val severity: String,
        val type: String,
        val primaryLocation: PrimaryLocation,
        private val duration: String,
        val secondaryLocations: List<SecondaryLocation>,
        /**
         * Automatic corrections for the issue (fork extension of the Generic Issue Data format, ignored by
         * SonarQube). Null, and therefore omitted from the report, if the issue has none.
         */
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val quickFixes: List<QuickFix>? = null) {
    val effortMinutes: Int = Duration.toMinute(duration)
}

data class PrimaryLocation(
        val message: String,
        val filePath: String,
        val textRange: TextRange
)

data class SecondaryLocation(
        val message: String,
        val filePath: String,
        val textRange: TextRange
)

data class TextRange(
        val startLine: Int,
        val endLine: Int?,
        val startColumn: Int?,
        val endColumn: Int?
)

/** An automatic correction: all [edits] refer to the original file and are applied together. */
data class QuickFix(
        val message: String,
        val edits: List<TextEdit>
)

/**
 * Replaces a range of the file of the issue with [text], using the coordinates of [TextRange]: lines are 1-based,
 * columns are 0-based and [endColumn] is exclusive. An empty [text] deletes the range, an empty range inserts [text].
 */
data class TextEdit(
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int,
        val text: String
)