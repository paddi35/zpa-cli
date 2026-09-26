package br.com.felipezorzo.zpa.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuickFixExportTest {

    private val mapper = jacksonObjectMapper()

    // line 2: InequalityUsage ("<>" -> "!=") and ComparisonWithNull ("<> NULL" -> "IS NOT NULL"), one edit each
    // line 5: ComparisonWithNull ("NULL = b" -> "b IS NULL"), two edits
    // line 8: ComparisonWithNull without a quick fix ("<" has no IS [NOT] NULL equivalent)
    private val source = """
        BEGIN
          IF a <> NULL THEN
            NULL;
          END IF;
          IF NULL = b THEN
            NULL;
          END IF;
          IF a < NULL THEN
            NULL;
          END IF;
        END;
        /
        """.trimIndent()

    private fun <T> withSources(block: (sourcesDir: File, root: File) -> T): T {
        val root = Files.createTempDirectory("zpa-cli-quickfix-test").toFile()
        try {
            val sourcesDir = root.resolve("src").apply { mkdirs() }
            sourcesDir.resolve("test.sql").writeText(source)
            return block(sourcesDir, root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun analyze(format: String): String = withSources { sourcesDir, root ->
        val outputFile = root.resolve("output.json")
        val exitCode = execute(
            arrayOf(
                "--sources", sourcesDir.absolutePath,
                "--output-format", format,
                "--output-file", outputFile.absolutePath
            )
        )
        assertEquals(0, exitCode)
        outputFile.readText(StandardCharsets.UTF_8)
    }

    private fun edit(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, text: String) =
        mapOf(
            "startLine" to startLine, "startColumn" to startColumn,
            "endLine" to endLine, "endColumn" to endColumn, "text" to text
        )

    private fun edits(quickFix: JsonNode): List<Map<String, Any>> =
        quickFix.get("edits").map { mapper.convertValue<Map<String, Any>>(it) }

    private fun assertQuickFixes(issueOnLine: (rule: String, line: Int) -> JsonNode?) {
        val inequality = assertNotNull(issueOnLine("zpa:InequalityUsage", 2))
        val inequalityFixes = inequality.get("quickFixes")
        assertEquals(1, inequalityFixes.size())
        assertTrue(inequalityFixes[0].get("message").asText().contains("!="))
        assertEquals(listOf(edit(2, 7, 2, 9, "!=")), edits(inequalityFixes[0]))

        val notNull = assertNotNull(issueOnLine("zpa:ComparisonWithNull", 2))
        val notNullFixes = notNull.get("quickFixes")
        assertEquals(1, notNullFixes.size())
        assertTrue(notNullFixes[0].get("message").asText().contains("IS NOT NULL"))
        assertEquals(listOf(edit(2, 7, 2, 14, "IS NOT NULL")), edits(notNullFixes[0]))

        val nullFirst = assertNotNull(issueOnLine("zpa:ComparisonWithNull", 5))
        val nullFirstFixes = nullFirst.get("quickFixes")
        assertEquals(1, nullFirstFixes.size())
        assertEquals(
            listOf(edit(5, 5, 5, 12, ""), edit(5, 13, 5, 13, " IS NULL")),
            edits(nullFirstFixes[0])
        )

        val withoutFix = assertNotNull(issueOnLine("zpa:ComparisonWithNull", 8))
        assertFalse(withoutFix.has("quickFixes"), "Issues without a quick fix must not have the field: $withoutFix")
    }

    @Test
    fun genericIssueFormatExportsQuickFixes() {
        val report = mapper.readTree(analyze("sq-generic-issue-import"))
        val issues = report.get("issues").toList()
        assertQuickFixes { rule, line ->
            issues.firstOrNull {
                it.get("ruleId").asText() == rule && it.path("primaryLocation").path("textRange").path("startLine").asInt() == line
            }
        }
    }

    @Test
    fun jsonFormatExportsQuickFixes() {
        val report = mapper.readTree(analyze("json"))
        assertEquals(1, report.get("schemaVersion").asInt())
        val diagnostics = report.get("diagnostics").toList()
        assertQuickFixes { rule, line ->
            diagnostics.firstOrNull { it.get("rule").asText() == rule && it.path("range").path("startLine").asInt() == line }
        }
    }

    @Test
    fun reportsWithoutQuickFixesHaveNoQuickFixesField() {
        val root = Files.createTempDirectory("zpa-cli-no-quickfix-test").toFile()
        try {
            val sourcesDir = root.resolve("src").apply { mkdirs() }
            sourcesDir.resolve("test.sql").writeText("BEGIN\n  IF a < NULL THEN\n    NULL;\n  END IF;\nEND;\n/\n")
            for (format in listOf("sq-generic-issue-import", "json")) {
                val outputFile = root.resolve("output-$format.json")
                val exitCode = execute(
                    arrayOf(
                        "--sources", sourcesDir.absolutePath,
                        "--output-format", format,
                        "--output-file", outputFile.absolutePath
                    )
                )
                assertEquals(0, exitCode)
                val output = outputFile.readText(StandardCharsets.UTF_8)
                assertTrue(output.contains("zpa:ComparisonWithNull"), output)
                assertFalse(output.contains("quickFixes"), output)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun consoleMarksIssuesWithQuickFixes() {
        val originalOut = System.out
        val capturedOut = ByteArrayOutputStream()
        val lines = try {
            System.setOut(PrintStream(capturedOut, true, StandardCharsets.UTF_8))
            withSources { sourcesDir, _ ->
                assertEquals(0, execute(arrayOf("--sources", sourcesDir.absolutePath, "--output-format", "console")))
            }
            capturedOut.toString(StandardCharsets.UTF_8).lines()
        } finally {
            System.setOut(originalOut)
        }

        fun line(prefix: String, rule: String) = assertNotNull(lines.firstOrNull { it.startsWith(prefix) && it.contains(rule) })
        assertTrue(line("2", "zpa:InequalityUsage").endsWith("[quick fix available]"))
        assertTrue(line("5:", "zpa:ComparisonWithNull").endsWith("[quick fix available]"))
        assertFalse(line("8:", "zpa:ComparisonWithNull").contains("[quick fix available]"))
    }
}
