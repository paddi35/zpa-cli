package br.com.felipezorzo.zpa.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SarifExporterTest {

    private val mapper = jacksonObjectMapper()

    // line 2: InequalityUsage ("<>", MAJOR) and ComparisonWithNull ("<> NULL", BLOCKER)
    // line 5: ComparisonWithNull ("NULL = b", BLOCKER)
    private val source = """
        BEGIN
          IF a <> NULL THEN
            NULL;
          END IF;
          IF NULL = b THEN
            NULL;
          END IF;
        END;
        /
        """.trimIndent()

    private fun withSources(block: (sourcesDir: File, root: File) -> Unit) {
        val root = Files.createTempDirectory("zpa-cli-sarif-test").toFile()
        try {
            val sourcesDir = root.resolve("src").apply { mkdirs() }
            sourcesDir.resolve("test.sql").writeText(source)
            block(sourcesDir, root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun analyze(sourcesDir: File, root: File, format: String): JsonNode {
        val outputFile = root.resolve("output-$format.json")
        val exitCode = execute(
            arrayOf(
                "--sources", sourcesDir.absolutePath,
                "--output-format", format,
                "--output-file", outputFile.absolutePath
            )
        )
        assertEquals(0, exitCode)
        return mapper.readTree(outputFile.readText(StandardCharsets.UTF_8))
    }

    /**
     * The "json" format is already covered by [QuickFixExportTest] and uses 0-based columns; this test derives the
     * expected SARIF values from it instead of hardcoding line/column numbers, so it only asserts what's specific to
     * SARIF: the 2.1.0 envelope, the rules array, the severity-to-level mapping, and the +1 column shift.
     */
    @Test
    fun exportsAValidSarifLogDerivedFromTheJsonFormat() = withSources { sourcesDir, root ->
        val json = analyze(sourcesDir, root, "json")
        val sarif = analyze(sourcesDir, root, "sarif")

        assertEquals("2.1.0", sarif.get("version").asText())
        assertTrue(sarif.get("\$schema").asText().contains("sarif-schema-2.1.0"))

        val run = sarif.get("runs").single()
        val driver = run.path("tool").path("driver")
        assertEquals("ZPA", driver.get("name").asText())
        assertEquals("https://github.com/felipebz/zpa", driver.get("informationUri").asText())

        val rules = driver.get("rules").associateBy { it.get("id").asText() }
        assertNotNull(rules["zpa:InequalityUsage"])
        assertNotNull(rules["zpa:ComparisonWithNull"])
        assertTrue(rules.getValue("zpa:InequalityUsage").path("shortDescription").get("text").asText().isNotEmpty())

        val diagnostics = json.get("diagnostics").toList()
        val results = run.get("results").toList()
        assertEquals(diagnostics.size, results.size)
        assertTrue(diagnostics.isNotEmpty())

        for (diagnostic in diagnostics) {
            val rule = diagnostic.get("rule").asText()
            val jsonRange = diagnostic.get("range")
            val startLine = jsonRange.get("startLine").asInt()

            val result = results.single {
                it.get("ruleId").asText() == rule &&
                    it.path("locations").single().path("physicalLocation").path("region")
                        .path("startLine").asInt() == startLine
            }
            val location = result.path("locations").single().path("physicalLocation")
            val region = location.path("region")

            fun asIntOrNull(node: JsonNode) = node.takeUnless { it.isNull || it.isMissingNode }?.asInt()
            fun oneBasedOrNull(node: JsonNode) = asIntOrNull(node)?.plus(1)

            assertEquals("test.sql", location.path("artifactLocation").get("uri").asText())
            assertEquals(oneBasedOrNull(jsonRange.get("startColumn")), asIntOrNull(region.path("startColumn")))
            assertEquals(oneBasedOrNull(jsonRange.get("endColumn")), asIntOrNull(region.path("endColumn")))
            assertEquals(asIntOrNull(jsonRange.get("endLine")), asIntOrNull(region.path("endLine")))
            assertEquals(diagnostic.get("message").asText(), result.path("message").get("text").asText())

            val expectedLevel = when (diagnostic.get("severity").asText()) {
                "BLOCKER", "CRITICAL" -> "error"
                "MAJOR" -> "warning"
                else -> "note"
            }
            assertEquals(expectedLevel, result.get("level").asText())
        }
    }

    @Test
    fun printsToStdoutWhenNoOutputFileIsGiven() = withSources { sourcesDir, _ ->
        val originalOut = System.out
        val capturedOut = ByteArrayOutputStream()
        val output = try {
            System.setOut(PrintStream(capturedOut, true, StandardCharsets.UTF_8.name()))
            val exitCode = execute(arrayOf("--sources", sourcesDir.absolutePath, "--output-format", "sarif"))
            assertEquals(0, exitCode)
            capturedOut.toString(StandardCharsets.UTF_8.name())
        } finally {
            System.setOut(originalOut)
        }

        val sarif = mapper.readTree(output)
        assertEquals("2.1.0", sarif.get("version").asText())
    }
}
