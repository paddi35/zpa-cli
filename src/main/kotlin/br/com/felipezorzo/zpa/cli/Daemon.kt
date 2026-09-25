package br.com.felipezorzo.zpa.cli

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8

const val DAEMON_FLAG = "--daemon"

/**
 * Keeps one JVM alive for many analyses. Reads one JSON request per line from [input] and writes one JSON line per
 * request to [output]:
 *
 * - on start: `{"type":"ready","protocol":1,"version":"<zpa-cli version>"}`
 * - request: `{"id": <string|number>, "args": ["--sources", "...", ...], "stdin": "<optional text for --files ->"}`
 * - response: `{"id": ..., "exitCode": <int>, "stdout": "...", "stderr": "..."}`
 * - `{"type":"shutdown"}` or end of input stops the daemon with exit code 0.
 *
 * Requests run sequentially through [executor] (the normal CLI entry point). While a request runs, `System.out`,
 * `System.err` and `System.in` are replaced by per-request buffers, so nothing the analysis prints (including JUL log
 * output, whose ConsoleHandler is recreated by every run) reaches the protocol stream.
 */
class Daemon(
    input: InputStream,
    private val output: PrintStream,
    private val version: String = cliVersion(),
    private val executor: (Array<String>) -> Int = ::execute,
) {
    private val reader = BufferedReader(InputStreamReader(input, UTF_8))
    private val mapper = jacksonObjectMapper()

    fun run(): Int {
        writeLine(mapOf("type" to "ready", "protocol" to PROTOCOL_VERSION, "version" to version))
        while (true) {
            val line = reader.readLine() ?: return 0
            if (line.isBlank()) {
                continue
            }
            val request = try {
                parse(line)
            } catch (e: InvalidRequestException) {
                writeLine(response(e.id, 2, "", e.message + System.lineSeparator()))
                continue
            }
            if (request == null) {
                return 0
            }
            writeLine(handle(request))
        }
    }

    private class Request(val id: JsonNode?, val args: Array<String>, val stdin: String)

    private class InvalidRequestException(val id: JsonNode?, override val message: String) : Exception(message)

    /** Returns null for a shutdown request. */
    private fun parse(line: String): Request? {
        val node = try {
            mapper.readTree(line)
        } catch (e: JsonProcessingException) {
            throw InvalidRequestException(null, "Invalid request, not valid JSON: ${e.originalMessage}")
        }
        if (node == null || !node.isObject) {
            throw InvalidRequestException(null, "Invalid request, expected a JSON object")
        }

        val idNode = node.get("id")
        val id = idNode?.takeIf { it.isTextual || it.isNumber }
        if (idNode != null && !idNode.isNull && id == null) {
            throw InvalidRequestException(null, "Invalid request, 'id' must be a string or a number")
        }

        val type = node.get("type")
        if (type != null) {
            if (type.isTextual && type.asText() == "shutdown") {
                return null
            }
            throw InvalidRequestException(id, "Invalid request, unknown type: $type")
        }

        val args = node.get("args")
        if (args == null || !args.isArray || !args.all { it.isTextual }) {
            throw InvalidRequestException(id, "Invalid request, 'args' must be an array of strings")
        }
        val stdin = node.get("stdin")
        if (stdin != null && !stdin.isTextual && !stdin.isNull) {
            throw InvalidRequestException(id, "Invalid request, 'stdin' must be a string")
        }
        return Request(id, args.map { it.asText() }.toTypedArray(), stdin?.textValue() ?: "")
    }

    private fun handle(request: Request): Map<String, Any?> {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val originalIn = System.`in`
        val out = PrintStream(stdout, true, UTF_8)
        val err = PrintStream(stderr, true, UTF_8)
        val exitCode = try {
            System.setOut(out)
            System.setErr(err)
            // Never let an analysis read the protocol stream; '--files -' reads the request's "stdin" instead.
            System.setIn(ByteArrayInputStream(request.stdin.toByteArray(UTF_8)))
            executor(request.args)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            err.println("Execution failed: ${e.message}")
            e.printStackTrace(err)
            3
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            System.setIn(originalIn)
            out.close()
            err.close()
        }
        return response(request.id, exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8))
    }

    private fun response(id: JsonNode?, exitCode: Int, stdout: String, stderr: String): Map<String, Any?> =
        mapOf("id" to id, "exitCode" to exitCode, "stdout" to stdout, "stderr" to stderr)

    private fun writeLine(message: Map<String, Any?>) {
        output.print(mapper.writeValueAsString(message))
        output.print('\n')
        output.flush()
    }

    companion object {
        const val PROTOCOL_VERSION = 1

        fun cliVersion(): String = Daemon::class.java.`package`?.implementationVersion ?: "unknown"

        /** Entry point for `zpa-cli --daemon`: speaks the protocol on the real stdin/stdout. */
        fun runOnStandardStreams(args: Array<String>): Int {
            if (args.size != 1) {
                System.err.println("$DAEMON_FLAG does not accept other arguments; pass them in each request")
                return 2
            }
            val protocolOut = PrintStream(FileOutputStream(FileDescriptor.out), true, UTF_8)
            // Output written outside a request (e.g. by a stray thread) goes to stderr, never into the protocol.
            System.setOut(System.err)
            return Daemon(System.`in`, protocolOut).run()
        }
    }
}
