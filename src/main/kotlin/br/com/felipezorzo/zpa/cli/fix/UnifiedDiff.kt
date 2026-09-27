package br.com.felipezorzo.zpa.cli.fix

/**
 * A line-based unified diff (as `diff -u` / `git diff`, applicable with `git apply`) of two texts, computed with the
 * Myers algorithm. Lines keep their original line separator; a missing separator at the end of a text is marked with
 * `\ No newline at end of file`.
 */
internal object UnifiedDiff {

    private const val CONTEXT = 3
    private const val NO_NEWLINE = "\\ No newline at end of file\n"

    private enum class Kind { EQUAL, DELETE, INSERT }

    /** One step of the edit script; [oldIndex] / [newIndex] are the 0-based line indexes in the old and new text. */
    private class Op(val kind: Kind, val oldIndex: Int, val newIndex: Int)

    /** Returns the diff of [old] and [new] with the file headers `--- a/<path>` / `+++ b/<path>`, or "" if equal. */
    fun diff(path: String, old: String, new: String): String {
        if (old == new) {
            return ""
        }
        val a = splitLines(old)
        val b = splitLines(new)
        val ops = editScript(a, b)
        val out = StringBuilder()
        out.append("--- a/").append(path).append('\n')
        out.append("+++ b/").append(path).append('\n')
        for (hunk in hunks(ops)) {
            appendHunk(out, ops.subList(hunk.first, hunk.last + 1), a, b)
        }
        return out.toString()
    }

    /** Splits [text] into lines, each with its line separator (the last one may have none). */
    private fun splitLines(text: String): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                    i++
                }
                lines.add(text.substring(start, i + 1))
                start = i + 1
            }
            i++
        }
        if (start < text.length) {
            lines.add(text.substring(start))
        }
        return lines
    }

    /** Myers' shortest edit script. Memory is O(D²) for D changed lines, independent of the file length. */
    private fun editScript(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        val max = n + m
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        // trace[d] = v[-d-1 .. d+1] before step d
        val trace = ArrayList<IntArray>()
        var steps = 0
        search@ for (d in 0..max) {
            trace.add(v.copyOfRange(offset - d - 1, offset + d + 2))
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    v[offset + k + 1]
                } else {
                    v[offset + k - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    steps = d
                    break@search
                }
            }
        }

        val ops = ArrayList<Op>()
        var x = n
        var y = m
        for (d in steps downTo 0) {
            val t = trace[d]
            fun at(k: Int) = t[k + d + 1]
            val k = x - y
            val previousK = if (k == -d || (k != d && at(k - 1) < at(k + 1))) k + 1 else k - 1
            val previousX = at(previousK)
            val previousY = previousX - previousK
            while (x > previousX && y > previousY) {
                ops.add(Op(Kind.EQUAL, x - 1, y - 1))
                x--
                y--
            }
            if (d > 0) {
                if (x == previousX) {
                    ops.add(Op(Kind.INSERT, x, y - 1))
                } else {
                    ops.add(Op(Kind.DELETE, x - 1, y))
                }
                x = previousX
                y = previousY
            }
        }
        ops.reverse()
        return ops
    }

    /** Ranges of [ops] (inclusive indexes) that form one hunk: changes with up to [CONTEXT] equal lines around them. */
    private fun hunks(ops: List<Op>): List<IntRange> {
        val changes = ops.indices.filter { ops[it].kind != Kind.EQUAL }
        val hunks = mutableListOf<IntRange>()
        var start = -1
        var end = -1
        for (index in changes) {
            if (start >= 0 && index - end - 1 <= 2 * CONTEXT) {
                end = index
            } else {
                if (start >= 0) {
                    hunks.add(maxOf(0, start - CONTEXT)..minOf(ops.size - 1, end + CONTEXT))
                }
                start = index
                end = index
            }
        }
        if (start >= 0) {
            hunks.add(maxOf(0, start - CONTEXT)..minOf(ops.size - 1, end + CONTEXT))
        }
        return hunks
    }

    private fun appendHunk(out: StringBuilder, ops: List<Op>, a: List<String>, b: List<String>) {
        val oldCount = ops.count { it.kind != Kind.INSERT }
        val newCount = ops.count { it.kind != Kind.DELETE }
        // an empty range is given as the line before it ("-0,0" for the start of the file)
        val oldStart = if (oldCount > 0) ops.first { it.kind != Kind.INSERT }.oldIndex + 1 else ops.first().oldIndex
        val newStart = if (newCount > 0) ops.first { it.kind != Kind.DELETE }.newIndex + 1 else ops.first().newIndex
        out.append("@@ -").append(range(oldStart, oldCount)).append(" +").append(range(newStart, newCount)).append(" @@\n")
        for (op in ops) {
            when (op.kind) {
                Kind.EQUAL -> appendLine(out, ' ', a[op.oldIndex])
                Kind.DELETE -> appendLine(out, '-', a[op.oldIndex])
                Kind.INSERT -> appendLine(out, '+', b[op.newIndex])
            }
        }
    }

    private fun range(start: Int, count: Int) = if (count == 1) "$start" else "$start,$count"

    private fun appendLine(out: StringBuilder, prefix: Char, line: String) {
        out.append(prefix)
        when {
            line.endsWith("\n") -> out.append(line)
            // a line ending with a lone CR would be joined with the next output line
            line.endsWith("\r") -> out.append(line, 0, line.length - 1).append('\n')
            else -> out.append(line).append('\n').append(NO_NEWLINE)
        }
    }
}
