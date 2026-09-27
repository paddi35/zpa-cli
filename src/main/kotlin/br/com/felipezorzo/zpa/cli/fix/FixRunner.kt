package br.com.felipezorzo.zpa.cli.fix

import br.com.felipezorzo.zpa.cli.InputFile
import com.felipebz.zpa.api.annotations.Priority
import com.felipebz.zpa.api.checks.QuickFixes
import com.felipebz.zpa.api.checks.TextEdit
import com.felipebz.zpa.checks.ParsingErrorCheck
import com.felipebz.zpa.squid.ZpaIssue
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Locale

/**
 * `--fix` / `--fix-dry-run`: applies the quick fixes of the issues to the analyzed files.
 *
 * Every round analyzes the files (the first round all targets, later rounds only the files changed in the round
 * before), chooses the quick fixes of each file like "fix all" in zpa-for-vscode (the first quick fix of every issue,
 * in document order, the most severe issue first on the same start, skipping quick fixes that conflict with one
 * chosen before, see [QuickFixes.selectNonOverlapping]) and applies them to the file's content in memory. A skipped
 * quick fix or a new issue revealed by a fix is handled by the next round. The rounds stop when nothing changes or
 * after [maxRounds] rounds; the changed files are then analyzed once more, so the returned issues are the ones that
 * remain in the fixed content.
 *
 * The analysis always sees the fixed content, also as project context of the other files. Only at the end the
 * changed files are written (or, with [dryRun], printed as a unified diff to [out]). A summary goes to [err].
 *
 * [scan] analyzes the given targets; the map holds the content to use for the project files being fixed (by
 * [InputFile.pathRelativeToBase]), which replaces their disk content in the project context.
 */
internal class FixRunner(
    private val maxRounds: Int,
    private val dryRun: Boolean,
    private val out: PrintStream,
    private val err: PrintStream,
    private val scan: (targets: List<InputFile>, fixedSources: Map<String, InputFile>) -> List<ZpaIssue>,
) {
    class Result(
        /** The issues remaining after the fixes (unsorted). */
        val issues: List<ZpaIssue>,
        /** Files that could not be written. */
        val writeFailures: List<String>,
    )

    private class FixedFile(val disk: InputFile, val source: SourceFile) {
        val path: String = disk.pathRelativeToBase
        var text: String = source.text
        var input: InputFile = disk
        var fixable = true
        var applied = 0
        var issues: List<ZpaIssue> = emptyList()
        /** The state before the fixes of the last round, until the new content has been analyzed. */
        var previous: Previous? = null

        fun update(newText: String, baseDirPath: Path) {
            text = newText
            input = pinned(disk, baseDirPath, newText)
        }
    }

    private class Previous(val text: String, val input: InputFile, val issues: List<ZpaIssue>, val applied: Int)

    fun run(targets: List<InputFile>, baseDirPath: Path): Result {
        val files = LinkedHashMap<String, FixedFile>()
        val others = mutableListOf<InputFile>()
        for (target in targets) {
            val source = SourceFile.read(target.path())
            if (source == null) {
                err.println("Not fixing ${target.pathRelativeToBase}: the file is not valid UTF-8")
                others.add(target)
            } else {
                // the analysis must see exactly the content the fixes are applied to
                files[target.pathRelativeToBase] = FixedFile(target, source).also {
                    it.input = pinned(target, baseDirPath, source.text)
                }
            }
        }

        var otherIssues: List<ZpaIssue> = emptyList()
        var toAnalyze = files.values.toList()
        var rounds = 0
        var first = true
        var stoppedByLimit = false
        while (true) {
            val inputs = toAnalyze.map { it.input } + (if (first) others else emptyList())
            val issuesByFile = scan(inputs, fixedSources(files)).groupBy { pathOf(it) }
            if (first) {
                otherIssues = others.flatMap { issuesByFile[it.pathRelativeToBase].orEmpty() }
                first = false
            }
            for (file in toAnalyze) {
                file.issues = issuesByFile[file.path].orEmpty()
                val previous = file.previous ?: continue
                file.previous = null
                if (hasParsingError(file.issues) && !hasParsingError(previous.issues)) {
                    err.println("Not applying the quick fixes of round $rounds to ${file.path}: the fixed file cannot be parsed")
                    file.text = previous.text
                    file.input = previous.input
                    file.issues = previous.issues
                    file.applied = previous.applied
                    file.fixable = false
                }
            }
            if (rounds == maxRounds) {
                stoppedByLimit = true
                break
            }
            val changed = toAnalyze.filter { it.fixable && applyFixes(it, baseDirPath) }
            if (changed.isEmpty()) {
                break
            }
            rounds++
            toAnalyze = changed
        }

        val changedFiles = files.values.filter { it.text != it.source.text }
        val writeFailures = mutableListOf<String>()
        val notWritten = mutableListOf<FixedFile>()
        for (file in changedFiles) {
            if (dryRun) {
                // the whole file content, so that the diff applies to it (a byte order mark is part of the first line),
                // as UTF-8 bytes whatever the console encoding, so that a redirected diff matches the files
                out.write(UnifiedDiff.diff(file.path, file.source.fileContent(file.source.text), file.source.fileContent(file.text)).toByteArray(StandardCharsets.UTF_8))
                out.flush()
                continue
            }
            if (!file.source.isUnchangedOnDisk()) {
                err.println("Not fixing ${file.path}: the file was changed while it was analyzed")
                notWritten.add(file)
                continue
            }
            try {
                file.source.write(file.text)
            } catch (e: IOException) {
                err.println("Could not write ${file.path}: $e")
                writeFailures.add(file.path)
                notWritten.add(file)
            }
        }
        if (notWritten.isNotEmpty()) {
            // the reported issues must match the files on disk
            for (file in notWritten) {
                file.text = file.source.text
                file.input = file.disk
                file.applied = 0
            }
            val issuesByFile = scan(notWritten.map { it.disk }, fixedSources(files)).groupBy { pathOf(it) }
            for (file in notWritten) {
                file.issues = issuesByFile[file.path].orEmpty()
            }
        }

        val issues = otherIssues + files.values.flatMap { it.issues }
        printSummary(files.values.filter { it.applied > 0 }, rounds, stoppedByLimit && rounds > 0 && issues.any { it.quickFixes.isNotEmpty() })
        return Result(issues, writeFailures)
    }

    /** Chooses and applies the quick fixes of [file]'s current issues; returns true if its content changed. */
    private fun applyFixes(file: FixedFile, baseDirPath: Path): Boolean {
        val candidates = file.issues.filter { it.quickFixes.isNotEmpty() }
        if (candidates.isEmpty()) {
            return false
        }
        val selected = QuickFixes.selectNonOverlapping(candidates, { it.quickFixes[0] }, { severityOf(it) })
        val edits = selected.flatMap { it.quickFixes[0].edits() }.map { edit ->
            val text = SourceFile.normalizeLineBreaks(edit.text(), file.source.lineSeparator)
            if (text == edit.text()) edit else TextEdit(edit.startLine(), edit.startLineOffset(), edit.endLine(), edit.endLineOffset(), text)
        }
        val newText = try {
            QuickFixes.apply(file.text, edits)
        } catch (e: IllegalArgumentException) {
            err.println("Not applying the quick fixes to ${file.path}: ${e.message}")
            file.fixable = false
            return false
        }
        if (newText == file.text) {
            return false
        }
        file.previous = Previous(file.text, file.input, file.issues, file.applied)
        file.applied += selected.size
        file.update(newText, baseDirPath)
        return true
    }

    private fun printSummary(fixedFiles: List<FixedFile>, rounds: Int, stoppedWithFixesLeft: Boolean) {
        val total = fixedFiles.sumOf { it.applied }
        if (total == 0) {
            err.println("No quick fixes to apply.")
            return
        }
        val verb = if (dryRun) "Would apply" else "Applied"
        val roundText = if (rounds == 1) "1 round" else "$rounds rounds"
        err.println("$verb ${plural(total, "quick fix", "quick fixes")} in ${plural(fixedFiles.size, "file", "files")} ($roundText)" +
            if (dryRun) "; no file was changed (--fix-dry-run)" else "")
        for (file in fixedFiles.sortedBy { it.path }) {
            err.println("  ${file.path}: ${plural(file.applied, "quick fix", "quick fixes")}")
        }
        if (stoppedWithFixesLeft) {
            err.println("Stopped after ${plural(maxRounds, "round", "rounds")} (--fix-max-rounds); some remaining issues still offer a quick fix.")
        }
    }

    private fun plural(count: Int, singular: String, plural: String) = "$count ${if (count == 1) singular else plural}"

    private fun fixedSources(files: Map<String, FixedFile>): Map<String, InputFile> =
        files.mapValues { it.value.input }

    private fun pathOf(issue: ZpaIssue): String =
        (issue.file as? InputFile)?.pathRelativeToBase ?: issue.file.fileName()

    private fun hasParsingError(issues: List<ZpaIssue>) = issues.any { it.check is ParsingErrorCheck }

    private fun severityOf(issue: ZpaIssue): Priority {
        val severity = issue.check.activeRule.severity.uppercase(Locale.ROOT)
        return Priority.entries.firstOrNull { it.name == severity } ?: Priority.INFO
    }

    companion object {
        /** [disk] with a fixed content (the path, and so the identity in the reports, stays the same). */
        private fun pinned(disk: InputFile, baseDirPath: Path, text: String) = InputFile(
            type = disk.type(),
            baseDirPath = baseDirPath,
            file = disk.path().toFile(),
            charset = StandardCharsets.UTF_8,
            inMemoryContent = text,
            virtualPath = null
        )
    }
}
