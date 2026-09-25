package br.com.felipezorzo.zpa.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonTest {

    private val mapper = jacksonObjectMapper()
    private lateinit var root: File
    private lateinit var sourcesDir: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("zpa-cli-daemon-test").toFile()
        sourcesDir = root.resolve("src").apply { mkdirs() }
        sourcesDir.resolve("test.sql").writeText(
            """
            BEGIN
              IF 1 = NULL THEN
                NULL;
              END IF;
            END;
            /
            """.trimIndent()
        )
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private class Result(val exitCode: Int, val lines: List<JsonNode>)

    private fun runDaemon(vararg requests: String): Result {
        val input = ByteArrayInputStream(requests.joinToString("\n", postfix = "\n").toByteArray(UTF_8))
        val output = ByteArrayOutputStream()
        val exitCode = Daemon(input, PrintStream(output, true, UTF_8), version = "test").run()
        val lines = output.toString(UTF_8).lines().filter { it.isNotEmpty() }.map { mapper.readTree(it) }
        return Result(exitCode, lines)
    }

    private fun request(id: Any, vararg args: String): String =
        mapper.writeValueAsString(mapOf("id" to id, "args" to args.toList()))

    private fun analysis(id: Any, format: String, outputFile: File? = null, vararg extra: String): String {
        val args = mutableListOf("--sources", sourcesDir.absolutePath, "--output-format", format)
        if (outputFile != null) {
            args += listOf("--output-file", outputFile.absolutePath)
        }
        args += extra
        return request(id, *args.toTypedArray())
    }

    @Test
    fun handshakeThenTwoConsecutiveRequests() {
        val genericOutput = root.resolve("generic.json")
        val jsonOutput = root.resolve("output.json")

        val result = runDaemon(
            analysis("first", GENERIC_ISSUE_FORMAT, genericOutput),
            analysis(2, JSON, jsonOutput, "--fail-on", "any"),
        )

        assertEquals(0, result.exitCode)
        assertEquals(3, result.lines.size)

        val ready = result.lines[0]
        assertEquals("ready", ready.get("type").asText())
        assertEquals(1, ready.get("protocol").asInt())
        assertEquals("test", ready.get("version").asText())

        val first = result.lines[1]
        assertEquals("first", first.get("id").asText())
        assertEquals(0, first.get("exitCode").asInt())
        val genericIssues = mapper.readTree(genericOutput).get("issues")
        assertTrue(genericIssues.size() > 0)
        assertTrue(genericIssues.all { it.path("primaryLocation").path("filePath").asText() == "test.sql" })

        val second = result.lines[2]
        assertTrue(second.get("id").isNumber)
        assertEquals(2, second.get("id").asInt())
        assertEquals(1, second.get("exitCode").asInt())
        val json = mapper.readTree(jsonOutput)
        assertFalse(json.path("validation").path("passed").asBoolean())
        assertTrue(json.get("diagnostics").size() > 0)
    }

    @Test
    fun analysisOutputAndLogsLandInTheResponseOnly() {
        val originalOut = System.out
        val originalErr = System.err
        val processOut = ByteArrayOutputStream()
        val processErr = ByteArrayOutputStream()
        val result = try {
            System.setOut(PrintStream(processOut, true, UTF_8))
            System.setErr(PrintStream(processErr, true, UTF_8))
            runDaemon(analysis(1, CONSOLE))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }

        assertEquals(2, result.lines.size)
        val response = result.lines[1]
        assertEquals(0, response.get("exitCode").asInt())
        assertTrue(response.get("stdout").asText().contains("File: test.sql"), response.toString())
        assertTrue(response.get("stderr").asText().contains("Time elapsed"), response.toString())
        assertFalse(response.get("stdout").asText().contains("Time elapsed"), response.toString())

        for (stream in listOf(processOut, processErr)) {
            val text = stream.toString(UTF_8)
            assertFalse(text.contains("File: test.sql"), text)
            assertFalse(text.contains("Time elapsed"), text)
        }
    }

    @Test
    fun malformedRequestsAreAnsweredWithExitCode2AndTheDaemonContinues() {
        val result = runDaemon(
            "this is not json",
            "[1, 2]",
            """{"id": 7, "args": "--help"}""",
            """{"id": {"x": 1}, "args": []}""",
            """{"id": 8, "type": "restart"}""",
            request(9, "--no-such-option"),
            request(10, "--help"),
        )

        assertEquals(0, result.exitCode)
        assertEquals(8, result.lines.size)
        val responses = result.lines.drop(1)

        for (response in responses.take(6)) {
            assertEquals(2, response.get("exitCode").asInt(), response.toString())
            assertTrue(response.get("stderr").asText().isNotBlank(), response.toString())
        }
        assertTrue(responses[0].get("id").isNull)
        assertTrue(responses[1].get("id").isNull)
        assertEquals(7, responses[2].get("id").asInt())
        assertTrue(responses[3].get("id").isNull)
        assertEquals(8, responses[4].get("id").asInt())
        assertEquals(9, responses[5].get("id").asInt())
        assertTrue(responses[5].get("stderr").asText().contains("--no-such-option"))

        assertEquals(10, responses[6].get("id").asInt())
        assertEquals(0, responses[6].get("exitCode").asInt())
        assertTrue(responses[6].get("stdout").asText().contains("--sources"))
    }

    @Test
    fun shutdownStopsTheDaemon() {
        val result = runDaemon("""{"type": "shutdown"}""", request(1, "--help"))

        assertEquals(0, result.exitCode)
        assertEquals(1, result.lines.size)
        assertEquals("ready", result.lines[0].get("type").asText())
    }

    @Test
    fun endOfInputStopsTheDaemon() {
        val result = runDaemon()

        assertEquals(0, result.exitCode)
        assertEquals(1, result.lines.size)
    }

    @Test
    fun stdinIsTakenFromTheRequest() {
        val request = mapper.writeValueAsString(
            mapOf(
                "id" to 1,
                "args" to listOf(
                    "--sources", sourcesDir.absolutePath, "--files", "-", "--stdin-filename", "buffer.sql",
                    "--syntax-only", "--output-format", CONSOLE
                ),
                "stdin" to "BEGIN\n  NULL\nEND;\n"
            )
        )

        val result = runDaemon(request, request(2, "--sources", sourcesDir.absolutePath, "--files", "-", "--syntax-only"))

        val withStdin = result.lines[1]
        assertEquals(1, withStdin.get("exitCode").asInt(), withStdin.toString())
        assertTrue(withStdin.get("stdout").asText().contains("File: buffer.sql"), withStdin.toString())
        assertTrue(withStdin.get("stdout").asText().contains("ParsingError"), withStdin.toString())

        // Without "stdin" the analysis reads an empty input instead of the protocol stream.
        val withoutStdin = result.lines[2]
        assertEquals(0, withoutStdin.get("exitCode").asInt(), withoutStdin.toString())
    }

    @Test
    fun repeatedRequestsDoNotLeakTemporaryDirectoriesOrThreads() {
        val tempRoot = Path(System.getProperty("java.io.tmpdir")).toFile()
        val pluginTempDir = Regex("zpa-cli\\d+")
        fun pluginTempDirs() = tempRoot.listFiles().orEmpty().filter { pluginTempDir.matches(it.name) }.map { it.name }.toSet()
        fun threads() = Thread.getAllStackTraces().keys.filter { it.isAlive && !it.name.startsWith("ForkJoinPool") }

        runDaemon(analysis(0, CONSOLE))
        val dirsBefore = pluginTempDirs()
        val threadsBefore = threads().size

        val requests = (1..20).map { analysis(it, JSON, root.resolve("out-$it.json")) }
        val result = runDaemon(*requests.toTypedArray())

        assertEquals(21, result.lines.size)
        assertTrue(result.lines.drop(1).all { it.get("exitCode").asInt() == 0 })
        assertEquals(emptySet(), pluginTempDirs() - dirsBefore, "plugin temp dirs must be deleted after each run")
        assertTrue(threads().none { it.name.startsWith("Report about progress") })
        assertTrue(threads().size <= threadsBefore, "threads before: $threadsBefore, after: ${threads().map { it.name }}")
    }
}
