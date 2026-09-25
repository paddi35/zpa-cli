package br.com.felipezorzo.zpa.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoSonarTest {

    private val mapper = jacksonObjectMapper()

    private fun rulesReportedOnLine(comment: String): List<String> {
        val root = Files.createTempDirectory("zpa-cli-nosonar-test").toFile()
        try {
            val sourcesDir = root.resolve("src").apply { mkdirs() }
            sourcesDir.resolve("test.sql").writeText(
                """
                BEGIN
                  IF 1 = NULL THEN $comment
                    NULL;
                  END IF;
                END;
                /
                """.trimIndent()
            )

            val outputFile = root.resolve("output.json")
            val exitCode = execute(
                arrayOf(
                    "--sources", sourcesDir.absolutePath,
                    "--output-format", "json",
                    "--output-file", outputFile.absolutePath
                )
            )
            assertEquals(0, exitCode)

            return mapper.readTree(outputFile).get("diagnostics").elements().asSequence()
                .filter { it.path("range").path("startLine").asInt() == 2 }
                .map { it.get("rule").asText() }
                .toList()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun issueIsReportedWithoutNoSonarComment() {
        assertTrue(rulesReportedOnLine("").isNotEmpty())
    }

    @Test
    fun issueIsSuppressedByNoSonarComment() {
        assertEquals(emptyList(), rulesReportedOnLine("--NOSONAR"))
    }

    @Test
    fun issueIsSuppressedByNoSonarCommentWithSpace() {
        assertEquals(emptyList(), rulesReportedOnLine("-- NOSONAR"))
    }
}
