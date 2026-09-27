package br.com.felipezorzo.zpa.cli

import br.com.felipezorzo.zpa.cli.plugin.CachedPlugins
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `--context-overlay <path> <file>`: the content of `<file>` replaces (or adds) the project file `<path>` in the project
 * context, without making it an analysis target.
 *
 * Observed through PackageBodyParameterNocopyCheck, which compares a package body with its specification in another
 * file: the specification on disk has no NOCOPY, the unsaved specification (the overlay) has it.
 */
class ContextOverlayTest {

    private val mapper = jacksonObjectMapper()
    private lateinit var root: File
    private lateinit var sourcesDir: File
    private lateinit var buffersDir: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("zpa-cli-context-overlay").toFile()
        sourcesDir = root.resolve("src").apply { mkdirs() }
        buffersDir = root.resolve("buffers").apply { mkdirs() }
        sourcesDir.resolve("pkg.pks").writeText(SPEC_PLAIN)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private class Run(val exitCode: Int, val stdout: String, val stderr: String, val unreadStdin: Int)

    private fun run(stdin: String, vararg args: String): Run {
        val originalIn = System.`in`
        val originalOut = System.out
        val originalErr = System.err
        val input = ByteArrayInputStream(stdin.toByteArray(UTF_8))
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
    private fun diagnostics(stdin: String, vararg args: String): List<JsonNode> {
        val outputFile = root.resolve("out.json")
        outputFile.delete()
        val result = run(stdin, *args, "--output-format", JSON, "--output-file", outputFile.absolutePath)
        assertEquals(0, result.exitCode, result.stderr)
        return mapper.readTree(outputFile).get("diagnostics").toList()
    }

    private fun List<JsonNode>.nocopy() = filter { it.get("rule").asText() == NOCOPY_RULE }

    private fun buffer(name: String, content: String): String =
        buffersDir.resolve(name).apply { writeText(content) }.absolutePath

    private fun buffer(name: String, content: ByteArray): String =
        buffersDir.resolve(name).apply { writeBytes(content) }.absolutePath

    @Test
    fun unsavedSpecificationGivesContextToTheUnsavedBodyOnStdin() {
        val stdinBody = arrayOf("--files", "-", "--stdin-filename", "pkg.pkb")

        // Without the overlay the body matches the specification on disk.
        assertEquals(emptyList(), diagnostics(BODY_PLAIN, *stdinBody).nocopy())

        val withOverlay = diagnostics(BODY_PLAIN, *stdinBody, "--context-overlay", "pkg.pks", buffer("spec.sql", SPEC_NOCOPY))
        val issue = withOverlay.nocopy().single()
        assertEquals("pkg.pkb", issue.get("file").asText())
        assertEquals(setOf("pkg.pkb"), withOverlay.map { it.get("file").asText() }.toSet(), "only the stdin file is reported")
    }

    @Test
    fun overlaysGiveContextToTargetsOnDisk() {
        sourcesDir.resolve("pkg.pkb").writeText(BODY_PLAIN)
        assertEquals(emptyList(), diagnostics("", "--files", "pkg.pkb").nocopy())

        // The overlay has an issue of its own, which is not reported: overlays are context only.
        val spec = SPEC_NOCOPY + "\n/\n" + "BEGIN\n  IF 1 = NULL THEN\n    NULL;\n  END IF;\nEND;\n/\n"
        val withOverlay = diagnostics(
            "", "--files", "pkg.pkb", "--context-overlay", sourcesDir.resolve("pkg.pks").absolutePath, buffer("spec.sql", spec)
        )
        assertEquals(1, withOverlay.nocopy().size)
        assertEquals(setOf("pkg.pkb"), withOverlay.map { it.get("file").asText() }.toSet())

        // The file on disk is unchanged.
        assertEquals(SPEC_PLAIN, sourcesDir.resolve("pkg.pks").readText())
    }

    @Test
    fun overlayOfAFileThatDoesNotExistOnDisk() {
        sourcesDir.resolve("pkg.pks").delete()
        val stdinBody = arrayOf("--files", "-", "--stdin-filename", "pkg.pkb")
        assertEquals(emptyList(), diagnostics(BODY_PLAIN, *stdinBody).nocopy())

        val withOverlay = diagnostics(
            BODY_PLAIN, *stdinBody, "--context-overlay", "sub/new_spec.pks", buffer("spec.sql", SPEC_NOCOPY)
        )
        assertEquals(1, withOverlay.nocopy().size)
        assertEquals(setOf("pkg.pkb"), withOverlay.map { it.get("file").asText() }.toSet())
        assertFalse(sourcesDir.resolve("sub").exists(), "nothing is written to the sources directory")
    }

    @Test
    fun severalOverlaysInOneRun() {
        // Two unsaved specifications: the one for pkg replaces the disk file, the other one is new.
        sourcesDir.resolve("other.pkb").writeText(BODY_PLAIN.replace("pkg", "other"))
        val result = diagnostics(
            BODY_PLAIN, "--files", "-", "--stdin-filename", "pkg.pkb",
            "--context-overlay", "pkg.pks", buffer("spec.sql", SPEC_NOCOPY),
            "--context-overlay", "other.pks", buffer("other.sql", SPEC_NOCOPY.replace("pkg", "other")),
        )
        assertEquals(listOf("pkg.pkb"), result.nocopy().map { it.get("file").asText() })
    }

    @Test
    fun overlayContentIsReadAsUtf8WithoutByteOrderMark() {
        sourcesDir.resolve("pkg.pkb").writeText(BODY_PLAIN)
        // With the byte order mark kept, the specification would not parse and the body would have no counterpart.
        val content = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "-- Größe\n$SPEC_NOCOPY".toByteArray(UTF_8)

        val result = diagnostics("", "--files", "pkg.pkb", "--context-overlay", "pkg.pks", buffer("spec.sql", content))

        assertEquals(1, result.nocopy().size)
    }

    @Test
    fun invalidOverlaysAreRejectedBeforeReadingStdin() {
        sourcesDir.resolve("pkg.pkb").writeText(BODY_PLAIN)
        val spec = buffer("spec.sql", SPEC_NOCOPY)
        val outside = root.resolve("outside.pks").apply { writeText(SPEC_NOCOPY) }
        val stdinBody = arrayOf("--files", "-", "--stdin-filename", "pkg.pkb")

        fun assertRejected(message: String, vararg args: String) {
            val result = run(BODY_PLAIN, *args)
            assertEquals(2, result.exitCode, result.stdout + result.stderr)
            assertTrue(result.stderr.contains(message), result.stderr)
            assertEquals(BODY_PLAIN.toByteArray(UTF_8).size, result.unreadStdin, "stdin must not be consumed")
        }

        assertRejected("cannot be used with --syntax-only", *stdinBody, "--syntax-only", "--context-overlay", "pkg.pks", spec)
        assertRejected("requires explicit analysis targets", "--context-overlay", "pkg.pks", spec)
        assertRejected("--context-overlay path '../outside.pks' cannot escape the sources directory", *stdinBody, "--context-overlay", "../outside.pks", spec)
        assertRejected("must be inside the sources directory", *stdinBody, "--context-overlay", outside.absolutePath, spec)
        assertRejected("has unsupported extension 'txt'", *stdinBody, "--context-overlay", "pkg.txt", spec)
        assertRejected("content file does not exist", *stdinBody, "--context-overlay", "pkg.pks", buffersDir.resolve("missing.sql").absolutePath)
        assertRejected("content file does not exist", *stdinBody, "--context-overlay", "pkg.pks", buffersDir.absolutePath)
        assertRejected("is given more than once", *stdinBody, "--context-overlay", "pkg.pks", spec, "--context-overlay", "./pkg.pks", spec)
        assertRejected("is the file read from standard input", *stdinBody, "--context-overlay", "pkg.pkb", spec)
        assertRejected("is also an analysis target", "--files", "pkg.pkb", "--context-overlay", "./pkg.pkb", spec)
        assertRejected("expects two non-empty values", *stdinBody, "--context-overlay", "", spec)
        assertRejected("expects two non-empty values", *stdinBody, "--context-overlay", "", spec, "--context-overlay", "", spec)
        // A missing <file> argument is a command-line syntax error.
        assertRejected("Expected 2 values after --context-overlay", *stdinBody, "--context-overlay", "pkg.pks")
    }

    @Test
    fun overlaysInDaemonRequests() {
        val spec = buffer("spec.sql", SPEC_NOCOPY)
        fun request(id: Int, vararg extra: String) = mapper.writeValueAsString(
            mapOf(
                "id" to id,
                "args" to listOf(
                    "--sources", sourcesDir.absolutePath, "--files", "-", "--stdin-filename", "pkg.pkb",
                    "--output-format", CONSOLE, *extra
                ),
                "stdin" to BODY_PLAIN
            )
        )
        val input = listOf(
            request(1, "--context-overlay", "pkg.pks", spec),
            request(2),
            request(3, "--context-overlay", "pkg.pks", spec, "--syntax-only"),
        ).joinToString("\n", postfix = "\n")
        val output = ByteArrayOutputStream()

        Daemon(
            ByteArrayInputStream(input.toByteArray(UTF_8)), PrintStream(output, true, UTF_8), version = "test",
            plugins = CachedPlugins(root.resolve("no-plugins").toPath())
        ).run()

        val responses = output.toString(UTF_8).lines().filter { it.isNotEmpty() }.drop(1).map { mapper.readTree(it) }
        assertEquals(3, responses.size)
        assertEquals(0, responses[0].get("exitCode").asInt(), responses[0].toString())
        assertTrue(responses[0].get("stdout").asText().contains(NOCOPY_RULE), responses[0].toString())
        assertFalse(responses[0].get("stdout").asText().contains("File: pkg.pks"), responses[0].toString())
        // The overlay belongs to its request only.
        assertEquals(0, responses[1].get("exitCode").asInt(), responses[1].toString())
        assertFalse(responses[1].get("stdout").asText().contains(NOCOPY_RULE), responses[1].toString())
        assertEquals(2, responses[2].get("exitCode").asInt(), responses[2].toString())
    }

    @Test
    fun overlayReplacesTheDiskFileAndKeepsItsIdentity() {
        val selection = SourceSelection(sourcesDir.toPath().toAbsolutePath().normalize(), listOf("pks", "pkb"))
        sourcesDir.resolve("pkg.pkb").writeText(BODY_PLAIN)
        val projectSources = selection.discoverProjectSources()
        val overlays = selection.resolveContextOverlays(
            listOf("./pkg.pks", buffer("spec.sql", "spec"), "new.pks", buffer("new.sql", "new")),
            requestedFiles = listOf("pkg.pkb"),
            stdinOverlayPath = null
        )
        assertEquals(listOf("pkg.pks", "new.pks"), overlays.map { it.path })

        val sources = selection.applyContextOverlays(projectSources, overlays)

        assertEquals(listOf("new.pks", "pkg.pkb", "pkg.pks"), sources.map { it.pathRelativeToBase })
        assertEquals(listOf("new", BODY_PLAIN, "spec"), sources.map { it.contents() })
        assertTrue(sources.single { it.pathRelativeToBase == "pkg.pkb" } === projectSources.single { it.pathRelativeToBase == "pkg.pkb" })
    }

    companion object {
        const val NOCOPY_RULE = "zpa:PackageBodyParameterNocopy"

        val SPEC_PLAIN = """
            CREATE OR REPLACE PACKAGE pkg AS
              PROCEDURE work(payload IN OUT CLOB);
            END pkg;
            """.trimIndent()

        val SPEC_NOCOPY = """
            CREATE OR REPLACE PACKAGE pkg AS
              PROCEDURE work(payload IN OUT NOCOPY CLOB);
            END pkg;
            """.trimIndent()

        val BODY_PLAIN = """
            CREATE OR REPLACE PACKAGE BODY pkg AS
              PROCEDURE work(payload IN OUT CLOB) IS
              BEGIN
                NULL;
              END work;
            END pkg;
            """.trimIndent()
    }
}
