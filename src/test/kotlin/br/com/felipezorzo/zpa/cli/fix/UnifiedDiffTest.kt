package br.com.felipezorzo.zpa.cli.fix

import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedDiffTest {

    @Test
    fun equalTextsHaveNoDiff() {
        assertEquals("", UnifiedDiff.diff("a.sql", "x\ny\n", "x\ny\n"))
    }

    @Test
    fun removedAndInsertedLines() {
        val old = "DECLARE\nBEGIN\n  NULL;\nEND;\n"
        val new = "BEGIN\n  NULL;\n  x := 1;\nEND;\n"
        assertEquals(
            "--- a/a.sql\n+++ b/a.sql\n" +
                "@@ -1,4 +1,4 @@\n-DECLARE\n BEGIN\n   NULL;\n+  x := 1;\n END;\n",
            UnifiedDiff.diff("a.sql", old, new)
        )
    }

    @Test
    fun keepsCrLfAndMarksAMissingNewline() {
        assertEquals(
            "--- a/a.sql\n+++ b/a.sql\n@@ -1,2 +1,2 @@\n a\r\n-b\n\\ No newline at end of file\n+c\n\\ No newline at end of file\n",
            UnifiedDiff.diff("a.sql", "a\r\nb", "a\r\nc")
        )
        assertEquals(
            "--- a/a.sql\n+++ b/a.sql\n@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+a\n",
            UnifiedDiff.diff("a.sql", "a", "a\n")
        )
    }

    @Test
    fun emptyFiles() {
        assertEquals("--- a/a.sql\n+++ b/a.sql\n@@ -0,0 +1 @@\n+a\n", UnifiedDiff.diff("a.sql", "", "a\n"))
        assertEquals("--- a/a.sql\n+++ b/a.sql\n@@ -1 +0,0 @@\n-a\n", UnifiedDiff.diff("a.sql", "a\n", ""))
    }

    @Test
    fun distantChangesGetSeparateHunks() {
        val old = (1..20).joinToString("") { "$it\n" }
        val new = (1..20).joinToString("") {
            when (it) {
                2 -> "two\n"
                19 -> "nineteen\n"
                else -> "$it\n"
            }
        }
        assertEquals(
            "--- a/a.sql\n+++ b/a.sql\n" +
                "@@ -1,5 +1,5 @@\n 1\n-2\n+two\n 3\n 4\n 5\n" +
                "@@ -16,5 +16,5 @@\n 16\n 17\n 18\n-19\n+nineteen\n 20\n",
            UnifiedDiff.diff("a.sql", old, new)
        )
    }
}
