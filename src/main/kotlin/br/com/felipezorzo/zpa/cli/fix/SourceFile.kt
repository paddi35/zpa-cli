package br.com.felipezorzo.zpa.cli.fix

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The content of a source file as read from disk for `--fix`: the text the analysis sees ([text], without a byte order
 * mark) and what is needed to write a changed text back in the same form (UTF-8, the BOM if there was one).
 */
internal class SourceFile private constructor(
    val path: Path,
    private val bytes: ByteArray,
    val hasByteOrderMark: Boolean,
    val text: String
) {
    /** The line separator of the file (the first one found), used for line breaks inserted by quick fixes. */
    val lineSeparator: String = LINE_BREAK.find(text)?.value ?: "\n"

    /** The file content for [newText]: with the byte order mark if the file had one. */
    fun fileContent(newText: String): String = (if (hasByteOrderMark) BYTE_ORDER_MARK else "") + newText

    /** The bytes to write for [newText]: UTF-8, with the byte order mark if the file had one. */
    fun encode(newText: String): ByteArray =
        fileContent(newText).toByteArray(UTF_8)

    /** Returns true if the file on disk still has the content that was read. */
    fun isUnchangedOnDisk(): Boolean = try {
        Files.readAllBytes(path).contentEquals(bytes)
    } catch (e: IOException) {
        false
    }

    /**
     * Replaces the file with [newText] atomically: the content is written to a temporary file in the same directory,
     * which is then moved over the file (following a symbolic link to the real file).
     */
    fun write(newText: String) {
        val target = path.toRealPath()
        val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".zpa-fix.tmp")
        try {
            Files.write(temp, encode(newText))
            try {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target))
            } catch (e: UnsupportedOperationException) {
                // not a POSIX file system (e.g. Windows): the temporary file gets the permissions of the directory
            }
            moveWithRetry(temp, target)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun moveWithRetry(source: Path, target: Path) {
        var attempt = 1
        while (true) {
            try {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (e: AccessDeniedException) {
                // on Windows a virus scanner or indexer may hold the file for a moment
                if (attempt >= MOVE_ATTEMPTS) {
                    throw e
                }
                Thread.sleep(MOVE_RETRY_DELAY_MS * attempt)
                attempt++
            }
        }
    }

    companion object {
        private val BYTE_ORDER_MARK = Char(0xFEFF).toString()
        private const val MOVE_ATTEMPTS = 5
        private const val MOVE_RETRY_DELAY_MS = 50L
        private val LINE_BREAK = Regex("\r\n|\r|\n")

        /**
         * Reads [path] as UTF-8. Returns null if the file is not valid UTF-8: it would not be written back unchanged,
         * so it must not be fixed.
         */
        fun read(path: Path): SourceFile? {
            val bytes = Files.readAllBytes(path)
            val raw = try {
                UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                return null
            }
            val hasBom = raw.startsWith(BYTE_ORDER_MARK)
            return SourceFile(path, bytes, hasBom, raw.removePrefix(BYTE_ORDER_MARK))
        }

        /** Replaces the line breaks in [text] (`\n`, `\r\n` or `\r`) with [lineSeparator]. */
        fun normalizeLineBreaks(text: String, lineSeparator: String): String =
            if (text.contains('\n') || text.contains('\r')) text.replace(LINE_BREAK, lineSeparator) else text
    }
}
