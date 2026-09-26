package br.com.felipezorzo.zpa.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Project-aware analysis of stdin content (`--files - --stdin-filename <path>` without `--syntax-only`): the input
 * replaces or adds one project file, the project index is built with it and only that file is analyzed.
 *
 * The project context is observed through PackageBodyParameterNocopyCheck: it reports a body parameter whose NOCOPY
 * differs from the package specification, which lives in another file. The body declaration is matched to the index
 * by its source range in the body's own file, so a shifted stdin body is only resolved when the index was built from
 * the stdin content rather than from the disk content of the same file.
 */
class StdinProjectOverlayTest {

    private val mapper = jacksonObjectMapper()
    private lateinit var root: File
    private lateinit var sourcesDir: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("zpa-cli-stdin-overlay").toFile()
        sourcesDir = root.resolve("src").apply { mkdirs() }
        sourcesDir.resolve("pkg.pks").writeText(SPEC)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private class Run(val exitCode: Int, val stdout: String, val stderr: String, val unreadStdin: Int)

    private fun run(stdin: String, vararg args: String): Run = runBytes(stdin.toByteArray(UTF_8), *args)

    private fun runBytes(stdin: ByteArray, vararg args: String): Run {
        val originalIn = System.`in`
        val originalOut = System.out
        val originalErr = System.err
        val input = ByteArrayInputStream(stdin)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        try {
            System.setIn(input)
            System.setOut(PrintStream(out, true, UTF_8))
            System.setErr(PrintStream(err, true, UTF_8))
            val exitCode = execute(arrayOf("--sources", sourcesDir.absolutePath, *args))
            return Run(exitCode, out.toString(UTF_8), err.toString(UTF_8), input.available())
        } finally {
            System.setIn(originalIn)
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    /** Runs the analysis with json output and returns the diagnostics. */
    private fun diagnostics(stdin: String?, vararg args: String): List<JsonNode> {
        val outputFile = root.resolve("out.json")
        outputFile.delete()
        val allArgs = arrayOf(*args, "--output-format", JSON, "--output-file", outputFile.absolutePath)
        val result = if (stdin == null) run("", *allArgs) else run(stdin, "--files", "-", *allArgs)
        assertEquals(0, result.exitCode, result.stderr)
        return mapper.readTree(outputFile).get("diagnostics").toList()
    }

    private fun List<JsonNode>.nocopy() = filter { it.get("rule").asText() == NOCOPY_RULE }

    @Test
    fun stdinContentReplacesAnIssueOnDisk() {
        sourcesDir.resolve("pkg.pkb").writeText(BODY_MISMATCH)

        // Sanity check: the file on disk has the issue.
        assertEquals(1, diagnostics(null, "--files", "pkg.pkb").nocopy().size)

        val fromStdin = diagnostics(BODY_MATCH, "--stdin-filename", "pkg.pkb")
        assertEquals(emptyList(), fromStdin.nocopy())
    }

    @Test
    fun stdinContentIntroducesAnIssueNotOnDisk() {
        sourcesDir.resolve("pkg.pkb").writeText(BODY_MATCH)
        assertEquals(emptyList(), diagnostics(null, "--files", "pkg.pkb").nocopy())

        // Shifted by three lines: the body declaration only resolves against an index built from the stdin content.
        val fromStdin = diagnostics("\n\n\n" + BODY_MISMATCH, "--stdin-filename", "pkg.pkb").nocopy()

        assertEquals(1, fromStdin.size)
        assertEquals("pkg.pkb", fromStdin[0].get("file").asText())
        assertEquals(5, fromStdin[0].get("range").get("startLine").asInt())
    }

    @Test
    fun otherProjectFilesProvideTheContext() {
        val withSpec = diagnostics(BODY_MISMATCH, "--stdin-filename", "pkg.pkb").nocopy()
        assertEquals(1, withSpec.size)

        // The same buffer without the specification in the project, and in syntax-only mode, has no such issue.
        sourcesDir.resolve("pkg.pks").delete()
        assertEquals(emptyList(), diagnostics(BODY_MISMATCH, "--stdin-filename", "pkg.pkb").nocopy())
        sourcesDir.resolve("pkg.pks").writeText(SPEC)
        assertEquals(emptyList(), diagnostics(BODY_MISMATCH, "--stdin-filename", "pkg.pkb", "--syntax-only").nocopy())
    }

    @Test
    fun overlayOfTheSpecificationChangesTheContextOfTheStdinFileOnly() {
        // Only the stdin file is a target: the body on disk is indexed but not analyzed.
        sourcesDir.resolve("pkg.pkb").writeText(BODY_MISMATCH)
        sourcesDir.resolve("other.sql").writeText("BEGIN\n  IF 1 = NULL THEN\n    NULL;\n  END IF;\nEND;\n/\n")

        val fromStdin = diagnostics(SPEC, "--stdin-filename", "pkg.pks")
        assertEquals(emptySet(), fromStdin.map { it.get("file").asText() }.toSet() - "pkg.pks")
    }

    @Test
    fun newFileThatDoesNotExistOnDisk() {
        val relative = diagnostics(BODY_MISMATCH, "--stdin-filename", "sub/new_body.pkb").nocopy()
        assertEquals(1, relative.size)
        assertEquals("sub/new_body.pkb", relative[0].get("file").asText())

        val absolute = diagnostics(
            BODY_MISMATCH, "--stdin-filename", sourcesDir.resolve("sub").resolve("new_body.pkb").absolutePath
        ).nocopy()
        assertEquals(1, absolute.size)
        assertEquals("sub/new_body.pkb", absolute[0].get("file").asText())
        assertTrue(!sourcesDir.resolve("sub").exists(), "nothing is written to the sources directory")
    }

    @Test
    fun stdinOverlayUsesTheNormalReportingPipeline() {
        sourcesDir.resolve("pkg.pkb").writeText(BODY_MATCH)
        val buffer = "BEGIN\n  IF 1 = NULL THEN\n    NULL;\n  END IF;\n  IF 2 = NULL THEN -- NOSONAR\n    NULL;\n  END IF;\nEND;\n/\n"
        val outputFile = root.resolve("generic.json")

        val result = run(
            buffer, "--files", "-", "--stdin-filename", "pkg.pkb",
            "--output-format", GENERIC_ISSUE_FORMAT, "--output-file", outputFile.absolutePath
        )

        assertEquals(0, result.exitCode, result.stderr)
        val issues = mapper.readTree(outputFile).get("issues").toList()
        val comparisons = issues.filter { it.get("ruleId").asText() == "zpa:ComparisonWithNull" }
        assertEquals(listOf(2), comparisons.map { it.get("primaryLocation").get("textRange").get("startLine").asInt() })
        assertEquals(setOf("pkg.pkb"), issues.map { it.get("primaryLocation").get("filePath").asText() }.toSet())
        assertTrue(comparisons.single().has("quickFixes"), comparisons.toString())

        val console = run(buffer, "--files", "-", "--stdin-filename", "pkg.pkb", "--fail-on", "any")
        assertEquals(1, console.exitCode, console.stderr)
        assertTrue(console.stdout.contains("File: pkg.pkb"), console.stdout)
    }

    @Test
    fun stdinIsDecodedAsUtf8LikeFiles() {
        sourcesDir.resolve("pkg.pkb").writeText(BODY_MATCH)
        val buffer = "BEGIN\n  -- Größe\n  IF 1 = NULL THEN\n    NULL;\n  END IF;\nEND;\n/\n"
        val outputFile = root.resolve("out.json")

        // A disk file and the same bytes on stdin (with and without BOM) give the same diagnostics.
        fun diagnosticsFor(bytes: ByteArray, fromStdin: Boolean): List<String> {
            outputFile.delete()
            val result = if (fromStdin) {
                runBytes(bytes, "--files", "-", "--stdin-filename", "pkg.pkb", "--output-format", JSON, "--output-file", outputFile.absolutePath)
            } else {
                sourcesDir.resolve("pkg.pkb").writeBytes(bytes)
                runBytes(ByteArray(0), "--files", "pkg.pkb", "--output-format", JSON, "--output-file", outputFile.absolutePath)
            }
            assertEquals(0, result.exitCode, result.stderr)
            return mapper.readTree(outputFile).get("diagnostics").map { "${it.get("rule").asText()}@${it.get("range")}" }
        }

        val plain = buffer.toByteArray(UTF_8)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + plain
        assertEquals(diagnosticsFor(plain, fromStdin = false), diagnosticsFor(plain, fromStdin = true))
        assertEquals(diagnosticsFor(withBom, fromStdin = false), diagnosticsFor(withBom, fromStdin = true))
    }

    @Test
    fun invalidRequestsAreRejectedBeforeReadingStdin() {
        sourcesDir.resolve("pkg.pkb").writeText(BODY_MATCH)
        val outside = root.resolve("outside.pkb").apply { writeText(BODY_MATCH) }

        fun assertRejected(message: String, vararg args: String) {
            val result = run(BODY_MISMATCH, *args)
            assertEquals(2, result.exitCode, result.stdout + result.stderr)
            assertTrue(result.stderr.contains(message), result.stderr)
            assertEquals(BODY_MISMATCH.toByteArray(UTF_8).size, result.unreadStdin, "stdin must not be consumed")
        }

        assertRejected("--stdin-filename is required", "--files", "-")
        assertRejected("cannot escape the sources directory", "--files", "-", "--stdin-filename", "../outside.pkb")
        assertRejected("must be inside the sources directory", "--files", "-", "--stdin-filename", outside.absolutePath)
        assertRejected("unsupported extension 'txt'", "--files", "-", "--stdin-filename", "pkg.txt")
        assertRejected("cannot be combined with other --files entries", "--files", "-", "pkg.pkb", "--stdin-filename", "pkg.pkb")
        assertRejected("cannot be combined with other --files entries", "--files", "pkg.pkb", "-", "--stdin-filename", "pkg.pkb")
    }

    @Test
    fun overlayMatchesTheDiskFileAndKeepsItsIdentity() {
        val selection = SourceSelection(sourcesDir.toPath().toAbsolutePath().normalize(), listOf("pks", "pkb", "sql"))
        sourcesDir.resolve("pkg.pkb").writeText(BODY_MATCH)
        val projectSources = selection.discoverProjectSources()

        val overlayPath = selection.resolveStdinOverlayPath(listOf("-"), "./pkg.pkb")
        assertEquals("pkg.pkb", overlayPath)

        val overlay = selection.applyStdinOverlay(projectSources, overlayPath, "buffer")

        assertEquals(listOf("pkg.pkb", "pkg.pks"), overlay.projectSources.map { it.pathRelativeToBase })
        assertTrue(overlay.projectSources.contains(overlay.target))
        assertEquals("buffer", overlay.projectSources.single { it.pathRelativeToBase == "pkg.pkb" }.contents())
        assertEquals("pkg.pkb", overlay.target.pathRelativeToBase)

        val added = selection.applyStdinOverlay(projectSources, "new.sql", "added")
        assertEquals(listOf("new.sql", "pkg.pkb", "pkg.pks"), added.projectSources.map { it.pathRelativeToBase })
        assertEquals(BODY_MATCH, added.projectSources.single { it.pathRelativeToBase == "pkg.pkb" }.contents())
    }

    companion object {
        const val NOCOPY_RULE = "zpa:PackageBodyParameterNocopy"

        val SPEC = """
            CREATE OR REPLACE PACKAGE pkg AS
              PROCEDURE work(payload IN OUT NOCOPY CLOB);
            END pkg;
            """.trimIndent()

        val BODY_MISMATCH = """
            CREATE OR REPLACE PACKAGE BODY pkg AS
              PROCEDURE work(payload IN OUT CLOB) IS
              BEGIN
                NULL;
              END work;
            END pkg;
            """.trimIndent()

        val BODY_MATCH = """
            CREATE OR REPLACE PACKAGE BODY pkg AS
              PROCEDURE work(payload IN OUT NOCOPY CLOB) IS
              BEGIN
                NULL;
              END work;
            END pkg;
            """.trimIndent()
    }
}
