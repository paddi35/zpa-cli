package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.plugin.CachedPlugins
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixModeTest {

    private val mapper = jacksonObjectMapper()
    private lateinit var root: File
    private lateinit var sourcesDir: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("zpa-cli-fix-test").toFile()
        sourcesDir = root.resolve("src").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private class Run(val exitCode: Int, val stdout: String, val stderr: String, val stdoutBytes: ByteArray)

    private fun run(vararg args: String): Run = run(UTF_8, *args)

    /** Runs zpa-cli with a System.out that encodes with [stdoutCharset] (like a console with that code page). */
    private fun run(stdoutCharset: java.nio.charset.Charset, vararg args: String): Run {
        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exitCode = try {
            System.setOut(PrintStream(out, true, stdoutCharset))
            System.setErr(PrintStream(err, true, UTF_8))
            execute(arrayOf("--sources", sourcesDir.absolutePath, *args))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return Run(exitCode, out.toString(UTF_8), err.toString(UTF_8), out.toByteArray())
    }

    private fun source(name: String, content: String): File =
        sourcesDir.resolve(name).apply {
            parentFile.mkdirs()
            writeText(content, UTF_8)
        }

    private fun diagnostics(outputFile: File): List<JsonNode> =
        mapper.readTree(outputFile.readText(UTF_8)).get("diagnostics").toList()

    @Test
    fun appliesTheQuickFixesToTheFile() {
        val file = source(
            "test.sql",
            """
            DECLARE
              v NUMBER := NULL;
            BEGIN
              IF a <> b THEN
                NULL;
              END IF;
              IF a = NULL THEN
                NULL;
              END IF;
              IF NULL = b THEN
                NULL;
              END IF;
            END;
            /
            """.trimIndent() + "\n"
        )

        val result = run("--fix")

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals(
            """
            DECLARE
              v NUMBER;
            BEGIN
              IF a != b THEN
                NULL;
              END IF;
              IF a IS NULL THEN
                NULL;
              END IF;
              IF b IS NULL THEN
                NULL;
              END IF;
            END;
            /
            """.trimIndent() + "\n",
            file.readText(UTF_8)
        )
        assertTrue(result.stderr.contains("Applied 4 quick fixes in 1 file (1 round)"), result.stderr)
        assertTrue(result.stderr.contains("  test.sql: 4 quick fixes"), result.stderr)
        assertFalse(result.stdout.contains("quick fix available"), result.stdout)
    }

    @Test
    fun overlappingQuickFixesAreResolvedLikeTheEditor() {
        // "<>" -> "!=" (InequalityUsage, MAJOR) and "<> NULL" -> "IS NOT NULL" (ComparisonWithNull, BLOCKER) start at
        // the same position: the more severe issue wins, and the analysis of the result finds nothing more to fix
        val file = source("test.sql", "BEGIN\n  IF a <> NULL THEN\n    NULL;\n  END IF;\nEND;\n/\n")

        val result = run("--fix")

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("BEGIN\n  IF a IS NOT NULL THEN\n    NULL;\n  END IF;\nEND;\n/\n", file.readText(UTF_8))
        assertTrue(result.stderr.contains("Applied 1 quick fix in 1 file (1 round)"), result.stderr)
    }

    @Test
    fun skippedQuickFixesAreAppliedInTheNextRound() {
        // UselessParenthesis removes the inner "(" and ")"; ComparisonWithNull inserts " IS NULL" before that ")" and
        // conflicts with it. The fix starting first wins; ComparisonWithNull follows in round 2.
        val content = "BEGIN\n  IF ((NULL = a)) THEN\n    NULL;\n  END IF;\nEND;\n/\n"
        val file = source("test.sql", content)

        val result = run("--fix")

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("BEGIN\n  IF (a IS NULL) THEN\n    NULL;\n  END IF;\nEND;\n/\n", file.readText(UTF_8))
        assertTrue(result.stderr.contains("Applied 2 quick fixes in 1 file (2 rounds)"), result.stderr)

        // one round only: the rest is reported
        file.writeText(content, UTF_8)
        val oneRound = run("--fix", "--fix-max-rounds", "1")
        assertEquals(0, oneRound.exitCode, oneRound.stderr)
        assertEquals("BEGIN\n  IF (NULL = a) THEN\n    NULL;\n  END IF;\nEND;\n/\n", file.readText(UTF_8))
        assertTrue(oneRound.stderr.contains("Applied 1 quick fix in 1 file (1 round)"), oneRound.stderr)
        assertTrue(oneRound.stderr.contains("Stopped after 1 round (--fix-max-rounds)"), oneRound.stderr)
        assertTrue(oneRound.stdout.lines().any { it.startsWith("2:") && it.contains("zpa:ComparisonWithNull") }, oneRound.stdout)
    }

    @Test
    fun keepsLineSeparatorsAndByteOrderMark() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val crlf = sourcesDir.resolve("crlf.sql")
        crlf.writeBytes(bom + "BEGIN\r\n  IF a <> b THEN\r\n    NULL;\r\n  END IF;\r\nEND;\r\n/\r\n".toByteArray(UTF_8))
        val cr = sourcesDir.resolve("cr.sql")
        cr.writeBytes("BEGIN\r  IF a <> b THEN\r    NULL;\r  END IF;\rEND;\r/".toByteArray(UTF_8))
        // a BOM and a CRLF on the first line of the fix
        val firstLine = sourcesDir.resolve("first.sql")
        firstLine.writeBytes(bom + "DECLARE v NUMBER := NULL; BEGIN IF a <> 'ä' THEN NULL; END IF; END;\r\n/\r\n".toByteArray(UTF_8))

        val result = run("--fix")

        assertEquals(0, result.exitCode, result.stderr)
        assertContentEquals(
            bom + "BEGIN\r\n  IF a != b THEN\r\n    NULL;\r\n  END IF;\r\nEND;\r\n/\r\n".toByteArray(UTF_8),
            crlf.readBytes()
        )
        assertContentEquals("BEGIN\r  IF a != b THEN\r    NULL;\r  END IF;\rEND;\r/".toByteArray(UTF_8), cr.readBytes())
        assertContentEquals(
            bom + "DECLARE v NUMBER; BEGIN IF a != 'ä' THEN NULL; END IF; END;\r\n/\r\n".toByteArray(UTF_8),
            firstLine.readBytes()
        )
        assertEquals(listOf("crlf.sql", "cr.sql", "first.sql").sorted(), sourcesDir.list()!!.sorted(), "no temporary files are left")
    }

    @Test
    fun filesWithoutQuickFixesAreNotTouched() {
        val clean = source("clean.sql", "BEGIN\n  NULL;\nEND;\n/\n")
        val noFix = source("nofix.sql", "BEGIN\n  IF a < NULL THEN\n    NULL;\n  END IF;\nEND;\n/\n")
        val fixable = source("fixable.sql", "BEGIN\n  IF a <> b THEN\n    NULL;\n  END IF;\nEND;\n/\n")
        val past = FileTime.fromMillis(System.currentTimeMillis() - 60_000)
        for (file in listOf(clean, noFix, fixable)) {
            Files.setLastModifiedTime(file.toPath(), past)
        }

        // only the file given in --files is fixed
        val focused = run("--fix", "--files", "clean.sql", "nofix.sql")
        assertEquals(0, focused.exitCode, focused.stderr)
        assertTrue(focused.stderr.contains("No quick fixes to apply."), focused.stderr)
        assertTrue(fixable.readText(UTF_8).contains("<>"))

        run("--fix")
        assertEquals(past, Files.getLastModifiedTime(clean.toPath()))
        assertEquals(past, Files.getLastModifiedTime(noFix.toPath()))
        assertTrue(fixable.readText(UTF_8).contains("!="))
    }

    @Test
    fun issuesOnNoSonarLinesAreNotFixed() {
        val file = source("test.sql", "BEGIN\n  IF a <> b THEN -- NOSONAR\n    NULL;\n  END IF;\n  IF c <> d THEN\n    NULL;\n  END IF;\nEND;\n/\n")

        assertEquals(0, run("--fix").exitCode)

        assertEquals("BEGIN\n  IF a <> b THEN -- NOSONAR\n    NULL;\n  END IF;\n  IF c != d THEN\n    NULL;\n  END IF;\nEND;\n/\n", file.readText(UTF_8))
    }

    @Test
    fun filesThatAreNotUtf8AreNotFixed() {
        val latin1 = sourcesDir.resolve("latin1.sql")
        val bytes = "BEGIN\n  IF a <> 'ä' THEN\n    NULL;\n  END IF;\nEND;\n/\n".toByteArray(ISO_8859_1)
        latin1.writeBytes(bytes)

        val result = run("--fix")

        assertEquals(0, result.exitCode, result.stderr)
        assertContentEquals(bytes, latin1.readBytes())
        assertTrue(result.stderr.contains("Not fixing latin1.sql: the file is not valid UTF-8"), result.stderr)
        assertTrue(result.stdout.contains("zpa:InequalityUsage"), result.stdout)
    }

    @Test
    fun dryRunPrintsADiffAndKeepsTheFiles() {
        val content = "BEGIN\n  IF a <> b THEN\n    NULL;\n  END IF;\n  x := 1;\n  x := 2;\n  x := 3;\n  x := 4;\n  x := 5;\n" +
            "  IF c = NULL THEN NULL; END IF;\nEND;\n/"
        val file = source("pkg/test.sql", content)
        val outputFile = root.resolve("report.json")

        val result = run("--fix-dry-run", "--output-format", "json", "--output-file", outputFile.absolutePath)

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals(content, file.readText(UTF_8))
        assertEquals(
            """
            --- a/pkg/test.sql
            +++ b/pkg/test.sql
            @@ -1,5 +1,5 @@
             BEGIN
            -  IF a <> b THEN
            +  IF a != b THEN
                 NULL;
               END IF;
               x := 1;
            @@ -7,6 +7,6 @@
               x := 3;
               x := 4;
               x := 5;
            -  IF c = NULL THEN NULL; END IF;
            +  IF c IS NULL THEN NULL; END IF;
             END;
             /
            \ No newline at end of file

            """.trimIndent(),
            result.stdout
        )
        assertTrue(result.stderr.contains("Would apply 2 quick fixes in 1 file (1 round); no file was changed (--fix-dry-run)"), result.stderr)
        // the report shows what would remain after the fixes
        assertTrue(diagnostics(outputFile).none { it.get("file").asText() == "pkg/test.sql" && it.has("quickFixes") })
    }

    @Test
    fun dryRunDiffHasTheBytesOfTheFiles() {
        // like "diff -u" of the files, so that the diff applies to them: the byte order mark is part of the first line,
        // and the diff is UTF-8 whatever the encoding of System.out (e.g. a Windows console code page)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = sourcesDir.resolve("bom.sql")
        val bytes = bom + "BEGIN\r\n  IF a <> 'ä' THEN NULL; END IF;\r\nEND;\r\n".toByteArray(UTF_8)
        file.writeBytes(bytes)

        val result = run(ISO_8859_1, "--fix-dry-run")

        assertEquals(0, result.exitCode, result.stderr)
        assertContentEquals(bytes, file.readBytes())
        val expected = "--- a/bom.sql\n+++ b/bom.sql\n@@ -1,3 +1,3 @@\n ".toByteArray(UTF_8) + bom +
            "BEGIN\r\n-  IF a <> 'ä' THEN NULL; END IF;\r\n+  IF a != 'ä' THEN NULL; END IF;\r\n END;\r\n".toByteArray(UTF_8)
        assertContentEquals(expected, result.stdoutBytes.copyOf(minOf(expected.size, result.stdoutBytes.size)), result.stdout)
    }

    @Test
    fun theReportShowsTheRemainingIssues() {
        source("test.sql", "BEGIN\n  IF a <> b THEN\n    NULL;\n  END IF;\n  IF a < NULL THEN\n    NULL;\n  END IF;\nEND;\n/\n")
        val outputFile = root.resolve("report.json")

        // ComparisonWithNull on "a < NULL" (BLOCKER) has no quick fix and remains
        val result = run("--fix", "--output-format", "json", "--output-file", outputFile.absolutePath, "--fail-on", "blocker")

        assertEquals(1, result.exitCode, result.stderr)
        val diagnostics = diagnostics(outputFile)
        assertEquals(listOf("zpa:ComparisonWithNull"), diagnostics.map { it.get("rule").asText() })
        assertEquals(5, diagnostics[0].path("range").path("startLine").asInt())
        assertFalse(mapper.readTree(outputFile.readText(UTF_8)).path("validation").path("passed").asBoolean())

        // the fixed issues no longer fail the build
        source("other.sql", "BEGIN\n  IF a <> b THEN\n    NULL;\n  END IF;\nEND;\n/\n")
        assertEquals(1, run("--files", "other.sql", "--fail-on", "major").exitCode)
        assertEquals(0, run("--fix", "--files", "other.sql", "--fail-on", "major").exitCode)
    }

    @Test
    fun rejectedCombinations() {
        val content = "BEGIN\n  IF a <> b THEN\n    NULL;\n  END IF;\nEND;\n/\n"
        val file = source("test.sql", content)

        fun assertRejected(message: String, vararg args: String) {
            val result = run(*args)
            assertEquals(2, result.exitCode, result.stderr)
            assertTrue(result.stderr.contains(message), result.stderr)
        }

        assertRejected("--fix cannot be used with --syntax-only", "--fix", "--syntax-only")
        assertRejected("--fix cannot be used with standard input", "--fix", "--files", "-", "--stdin-filename", "test.sql")
        assertRejected("--fix-dry-run cannot be used with standard input", "--fix-dry-run", "--files", "-")
        assertRejected("--fix-dry-run writes the diff to standard output", "--fix-dry-run", "--output-format", "json")
        assertRejected("--fix-max-rounds must be at least 1", "--fix", "--fix-max-rounds", "0")
        assertRejected("--fix-max-rounds can only be used with --fix", "--fix-max-rounds", "2")
        assertEquals(content, file.readText(UTF_8))
    }

    @Test
    fun fixInADaemonRequest() {
        val file = source("test.sql", "BEGIN\n  IF a <> b THEN\n    NULL;\n  END IF;\nEND;\n/\n")
        val outputFile = root.resolve("report.json")
        fun request(id: Int, vararg args: String) =
            mapper.writeValueAsString(mapOf("id" to id, "args" to listOf("--sources", sourcesDir.absolutePath, *args)))

        val input = listOf(
            request(1, "--fix", "--output-format", "json", "--output-file", outputFile.absolutePath),
            request(2, "--output-format", "json")
        ).joinToString("\n", postfix = "\n")
        val output = ByteArrayOutputStream()
        val daemon = Daemon(
            ByteArrayInputStream(input.toByteArray(UTF_8)),
            PrintStream(output, true, UTF_8),
            version = "test",
            plugins = CachedPlugins(root.resolve("no-plugins").toPath())
        )
        assertEquals(0, daemon.run())

        val lines = output.toString(UTF_8).lines().filter { it.isNotEmpty() }.map { mapper.readTree(it) }
        assertEquals(3, lines.size)
        val fixResponse = lines[1]
        assertEquals(0, fixResponse.get("exitCode").asInt(), fixResponse.toString())
        assertTrue(fixResponse.get("stderr").asText().contains("Applied 1 quick fix in 1 file (1 round)"))
        assertEquals("BEGIN\n  IF a != b THEN\n    NULL;\n  END IF;\nEND;\n/\n", file.readText(UTF_8))
        assertTrue(diagnostics(outputFile).isEmpty())

        // the next request sees the fixed file
        val report = mapper.readTree(lines[2].get("stdout").asText())
        assertTrue(report.get("diagnostics").isEmpty, report.toString())
    }
}
