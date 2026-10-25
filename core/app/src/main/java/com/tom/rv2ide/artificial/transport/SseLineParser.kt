package com.tom.rv2ide.artificial.transport

import java.io.BufferedReader

data class SseFrame(
    val event: String?,
    val data: String
)

object SseLineParser {

    /**
     * Parses Server-Sent Events (SSE) from a [BufferedReader].
     * Handles standard `event:` and `data:` lines, multi-line `data:` blocks,
     * `: comment` keepalive lines, and `[DONE]` termination markers.
     *
     * @param reader The input character stream.
     * @param isCancelled Lambda checked before processing each line/frame.
     * @param onFrame Invoked for each non-empty data payload (excluding `[DONE]`).
     *                Return `false` to stop reading early.
     */
    fun parseSseStream(
        reader: BufferedReader,
        isCancelled: () -> Boolean = { false },
        onFrame: (SseFrame) -> Boolean
    ) {
        var currentEvent: String? = null
        val dataBuffer = StringBuilder()

        fun flushFrame(): Boolean {
            if (dataBuffer.isEmpty()) {
                currentEvent = null
                return true
            }
            val payload = dataBuffer.toString().trim()
            dataBuffer.setLength(0)
            val evt = currentEvent
            currentEvent = null

            if (payload.isEmpty()) return true
            if (payload == "[DONE]") return false
            return onFrame(SseFrame(event = evt, data = payload))
        }

        var line: String?
        while (!isCancelled()) {
            line = reader.readLine() ?: break
            if (isCancelled()) break

            val trimmed = line.trimEnd('\r')
            if (trimmed.isEmpty()) {
                if (!flushFrame()) return
                continue
            }

            if (trimmed.startsWith(":")) {
                // SSE comment / keep-alive ping
                continue
            }

            when {
                trimmed.startsWith("event:") -> {
                    currentEvent = trimmed.removePrefix("event:").trim()
                }
                trimmed.startsWith("data:") -> {
                    val dataPart = trimmed.removePrefix("data:").let {
                        if (it.startsWith(" ")) it.substring(1) else it
                    }
                    if (dataPart.trim() == "[DONE]") {
                        flushFrame()
                        return
                    }
                    if (dataBuffer.isNotEmpty()) {
                        dataBuffer.append('\n')
                    }
                    dataBuffer.append(dataPart)
                }
                trimmed.startsWith("{") && dataBuffer.isEmpty() -> {
                    // Fallback if an endpoint returned raw JSON lines instead of `data:` prefix
                    if (!onFrame(SseFrame(event = null, data = trimmed))) return
                }
            }
        }

        if (!isCancelled() && dataBuffer.isNotEmpty()) {
            flushFrame()
        }
    }

    /**
     * Parses newline-delimited JSON (NDJSON), used by Ollama Native `/api/chat`.
     */
    fun parseNdjsonStream(
        reader: BufferedReader,
        isCancelled: () -> Boolean = { false },
        onJsonLine: (String) -> Boolean
    ) {
        var line: String?
        while (!isCancelled()) {
            line = reader.readLine() ?: break
            if (isCancelled()) break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (!onJsonLine(trimmed)) return
        }
    }
}
