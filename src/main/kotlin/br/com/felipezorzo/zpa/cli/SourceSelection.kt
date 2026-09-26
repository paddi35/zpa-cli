package br.com.felipezorzo.zpa.cli

import com.felipebz.zpa.api.PlSqlFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString

internal class SourceSelection(
    private val baseDirPath: Path,
    extensions: List<String>,
    private val charset: Charset = StandardCharsets.UTF_8
) {
    private val normalizedExtensions: Set<String> =
        extensions.flatMap { it.split(',') }
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()

    private val supportedExtensionsString: String =
        normalizedExtensions.joinToString(",")

    fun discoverProjectSources(): List<InputFile> {
        val baseDir = baseDirPath.toFile()
        return baseDir
            .walkTopDown()
            .filter { it.isFile && normalizedExtensions.contains(it.extension.lowercase(Locale.ROOT)) }
            .map { InputFile(PlSqlFile.Type.MAIN, baseDirPath, it, charset) }
            .sortedBy { it.pathRelativeToBase }
            .toList()
    }

    fun resolveProjectTargets(
        projectSources: List<InputFile>,
        requestedFiles: List<String>
    ): List<InputFile> {
        if (requestedFiles.isEmpty()) {
            return projectSources.sortedBy { it.pathRelativeToBase }
        }

        val allFilesByPath = projectSources.associateBy { it.path().toAbsolutePath().normalize() }
        val resolvedTargets = LinkedHashMap<String, InputFile>()

        for (rawTarget in requestedFiles) {
            if (rawTarget == STDIN_TARGET) {
                throw CliValidationException(
                    "Standard input ('--files -') must be analyzed as a project overlay (resolveStdinOverlayPath), not as a disk target"
                )
            }

            val rawPath = Path.of(rawTarget)
            val candidate = if (rawPath.isAbsolute) {
                rawPath.normalize()
            } else {
                baseDirPath.resolve(rawPath).normalize()
            }

            if (!candidate.startsWith(baseDirPath)) {
                throw CliValidationException("Target file '$rawTarget' is outside the sources directory '$baseDirPath'")
            }

            val inputFile = allFilesByPath[candidate]
                ?: run {
                    if (!candidate.exists() || !candidate.toFile().isFile) {
                        throw CliValidationException("Target file does not exist: $rawTarget")
                    }
                    val ext = candidate.extension.lowercase(Locale.ROOT)
                    if (!normalizedExtensions.contains(ext)) {
                        throw CliValidationException("Target file '$rawTarget' has unsupported extension '$ext'. Supported extensions: $supportedExtensionsString")
                    }
                    throw CliValidationException("Target file '$rawTarget' is not part of discovered project sources under '$baseDirPath'")
                }

            resolvedTargets[inputFile.pathRelativeToBase] = inputFile
        }

        return resolvedTargets.values.sortedBy { it.pathRelativeToBase }
    }

    /**
     * Validates a project-aware stdin request (`--files - --stdin-filename <path>` without `--syntax-only`) and
     * returns the path, relative to the sources directory, of the file the stdin content stands for. Nothing is read
     * from stdin here, so an invalid request fails before any input is consumed.
     */
    fun resolveStdinOverlayPath(requestedFiles: List<String>, stdinFilename: String): String {
        if (requestedFiles.any { it != STDIN_TARGET }) {
            throw CliValidationException(
                "Standard input ('--files -') cannot be combined with other --files entries; analyze the stdin content in a separate run"
            )
        }
        if (stdinFilename.isBlank()) {
            throw CliValidationException(
                "--stdin-filename is required when analyzing standard input ('--files -') without --syntax-only; it names the project file the input stands for"
            )
        }
        return resolveStdinVirtualPath(stdinFilename)
    }

    /**
     * Builds the project view in which the file at [overlayPath] has [content] instead of its disk content: the disk
     * version (if any) is replaced in the project sources, a file that does not exist yet is added, and the in-memory
     * file is the only analysis target. When the file exists on disk, its discovered spelling is kept as identity, so
     * issues are keyed exactly as in a `--files <path>` run.
     */
    fun applyStdinOverlay(projectSources: List<InputFile>, overlayPath: String, content: String): StdinOverlay {
        val overlayAbsolute = baseDirPath.resolve(overlayPath).toAbsolutePath().normalize()
        val diskFile = projectSources.firstOrNull { it.path().toAbsolutePath().normalize() == overlayAbsolute }
        val target = InputFile.fromStdin(baseDirPath, content, diskFile?.pathRelativeToBase ?: overlayPath)
        val sources = (projectSources.filter { it !== diskFile } + target).sortedBy { it.pathRelativeToBase }
        return StdinOverlay(projectSources = sources, target = target)
    }

    class StdinOverlay(val projectSources: List<InputFile>, val target: InputFile)

    /** Reads the whole standard input with the source charset, the same way [InputFile] reads a file. */
    fun readStdin(): String = System.`in`.bufferedReader(charset).readText()

    fun resolveSyntaxOnlyTargets(
        requestedFiles: List<String>,
        stdinFilename: String = "",
        stdinReader: (() -> String)? = null
    ): List<InputFile> {
        if (requestedFiles.isEmpty()) {
            return discoverProjectSources()
        }

        var stdinRead = false
        var stdinContent: String? = null
        val resolvedTargets = LinkedHashMap<String, InputFile>()

        for (rawTarget in requestedFiles) {
            if (rawTarget == STDIN_TARGET) {
                if (!stdinRead) {
                    stdinContent = stdinReader?.invoke() ?: readStdin()
                    stdinRead = true
                }
                val virtualPath = resolveStdinVirtualPath(stdinFilename)
                val stdinFile = InputFile.fromStdin(baseDirPath, stdinContent ?: "", virtualPath)
                resolvedTargets[stdinFile.pathRelativeToBase] = stdinFile
            } else {
                val rawPath = Path.of(rawTarget)
                val candidate = if (rawPath.isAbsolute) {
                    rawPath.normalize()
                } else {
                    baseDirPath.resolve(rawPath).normalize()
                }

                if (!candidate.startsWith(baseDirPath)) {
                    throw CliValidationException("Target file '$rawTarget' is outside the sources directory '$baseDirPath'")
                }
                if (!candidate.exists() || !candidate.toFile().isFile) {
                    throw CliValidationException("Target file does not exist: $rawTarget")
                }
                val ext = candidate.extension.lowercase(Locale.ROOT)
                if (!normalizedExtensions.contains(ext)) {
                    throw CliValidationException("Target file '$rawTarget' has unsupported extension '$ext'. Supported extensions: $supportedExtensionsString")
                }

                val inputFile = InputFile(PlSqlFile.Type.MAIN, baseDirPath, candidate.toFile(), charset)
                resolvedTargets[inputFile.pathRelativeToBase] = inputFile
            }
        }

        return resolvedTargets.values.sortedBy { it.pathRelativeToBase }
    }

    private fun resolveStdinVirtualPath(stdinFilename: String): String {
        if (stdinFilename.isBlank()) {
            return "stdin.sql"
        }
        val rawVirtual = Path.of(stdinFilename.trim())
        val resolvedVirtual = if (rawVirtual.isAbsolute) {
            val normalized = rawVirtual.normalize()
            if (!normalized.startsWith(baseDirPath)) {
                throw CliValidationException("--stdin-filename must be inside the sources directory '$baseDirPath'")
            }
            baseDirPath.relativize(normalized)
        } else {
            val candidate = baseDirPath.resolve(rawVirtual).normalize()
            if (!candidate.startsWith(baseDirPath)) {
                throw CliValidationException("--stdin-filename cannot escape the sources directory")
            }
            rawVirtual.normalize()
        }
        val ext = resolvedVirtual.extension.lowercase(Locale.ROOT)
        if (!normalizedExtensions.contains(ext)) {
            throw CliValidationException("--stdin-filename '$stdinFilename' has unsupported extension '$ext'. Supported extensions: $supportedExtensionsString")
        }
        return resolvedVirtual.invariantSeparatorsPathString
    }

    companion object {
        /** The `--files` entry that stands for standard input. */
        const val STDIN_TARGET = "-"
    }
}
