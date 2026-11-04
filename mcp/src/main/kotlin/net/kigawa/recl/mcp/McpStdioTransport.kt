package net.kigawa.recl.mcp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Newline-delimited JSON-RPC transport used by `claude mcp add --transport stdio`.
 *
 * The launcher owns the credential; it is never read from a JSON-RPC message. Each line is bounded
 * before it is decoded so one oversized message cannot exhaust the process heap.
 */
class McpStdioTransport(
    private val server: McpContextServer,
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out,
    private val maxMessageBytes: Int = 1 shl 20,
) {
    fun run() {
        val reader = BoundedLineReader(input, maxMessageBytes)
        try {
            while (true) {
                when (val line = reader.next() ?: return) {
                    is Line.Oversize -> emit(server.oversizeResponse(maxMessageBytes))
                    is Line.Text -> {
                        if (line.value.isBlank()) continue
                        val response = try {
                            server.handle(line.value)
                        } catch (_: Exception) {
                            // A handler bug must not kill the session; never leak exception details.
                            server.internalErrorResponse()
                        }
                        if (response != null) emit(response)
                    }
                }
            }
        } catch (_: IOException) {
            // The client closed the pipe. Exiting is the only correct behavior.
        }
    }

    private fun emit(line: String) {
        output.write(line.toByteArray(Charsets.UTF_8))
        output.write('\n'.code)
        output.flush()
    }
}

private sealed interface Line {
    data class Text(val value: String) : Line
    data class Oversize(val bytes: Long) : Line
}

/** Reads UTF-8 lines from bytes while capping the retained prefix. */
private class BoundedLineReader(input: InputStream, private val limit: Int) {
    private val source = BufferedInputStream(input)
    private val buffer = ByteArrayOutputStream()

    fun next(): Line? {
        buffer.reset()
        var overflow = false
        var total = 0L
        while (true) {
            val b = source.read()
            if (b == -1) {
                if (total == 0L) return null
                break
            }
            total++
            if (b == '\n'.code) break
            if (!overflow) {
                if (buffer.size() >= limit) {
                    overflow = true
                    buffer.reset()
                } else {
                    buffer.write(b)
                }
            }
        }
        if (overflow) return Line.Oversize(total)
        var bytes = buffer.toByteArray()
        if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
            bytes = bytes.copyOf(bytes.size - 1)
        }
        return Line.Text(String(bytes, Charsets.UTF_8))
    }
}
