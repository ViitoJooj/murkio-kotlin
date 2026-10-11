package com.murkio.identity.android.internal

internal class JsonException(message: String) : Exception(message)

/**
 * A small JSON reader and writer. Objects are `Map<String, Any?>`, arrays are
 * `List<Any?>`, numbers are always `Double`, and `null` is null.
 *
 * The SDK does not use `org.json` on purpose: the copy inside Android and the
 * one a unit test runs against are different implementations, and the canvas
 * validation must behave on a phone exactly as it does in the tests.
 */
internal object Json {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipSpace()
        val value = parser.value(0)
        parser.skipSpace()
        if (!parser.atEnd()) throw JsonException("unexpected data after the value")
        return value
    }

    /** Parses [text], or returns null when it is not JSON. */
    fun parseOrNull(text: String?): Any? {
        if (text.isNullOrEmpty()) return null
        return try {
            parse(text)
        } catch (_: JsonException) {
            null
        }
    }

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is String -> writeString(value, out)
            is Int, is Long -> out.append(value)
            is Number -> {
                val number = value.toDouble()
                when {
                    number.isNaN() || number.isInfinite() -> out.append("null")
                    number == Math.rint(number) && Math.abs(number) < 1e15 -> out.append(number.toLong())
                    else -> out.append(number)
                }
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(key.toString(), out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(item, out)
                }
                out.append(']')
            }
            else -> writeString(value.toString(), out)
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (char in value) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' || char.code == 0x2028 || char.code == 0x2029 ->
                    out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        private var at = 0

        fun atEnd(): Boolean = at >= text.length

        fun skipSpace() {
            while (at < text.length) {
                val char = text[at]
                if (char == ' ' || char == '\n' || char == '\r' || char == '\t') at++ else break
            }
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("nested too deep")
            if (atEnd()) throw JsonException("unexpected end")
            return when (val char = text[at]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (char == '-' || char in '0'..'9') number() else throw JsonException("unexpected character at $at")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) throw JsonException("unexpected token at $at")
            at += word.length
            return value
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            at++
            skipSpace()
            if (peek() == '}') {
                at++
                return result
            }
            while (true) {
                skipSpace()
                if (peek() != '"') throw JsonException("expected a key at $at")
                val key = string()
                skipSpace()
                if (peek() != ':') throw JsonException("expected ':' at $at")
                at++
                skipSpace()
                result[key] = value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> at++
                    '}' -> {
                        at++
                        return result
                    }
                    else -> throw JsonException("expected ',' or '}' at $at")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            val result = ArrayList<Any?>()
            at++
            skipSpace()
            if (peek() == ']') {
                at++
                return result
            }
            while (true) {
                skipSpace()
                result.add(value(depth + 1))
                skipSpace()
                when (peek()) {
                    ',' -> at++
                    ']' -> {
                        at++
                        return result
                    }
                    else -> throw JsonException("expected ',' or ']' at $at")
                }
            }
        }

        private fun peek(): Char = if (atEnd()) throw JsonException("unexpected end") else text[at]

        private fun string(): String {
            val out = StringBuilder()
            at++
            while (true) {
                if (atEnd()) throw JsonException("unterminated string")
                val char = text[at++]
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> {
                        if (atEnd()) throw JsonException("unterminated escape")
                        when (val escape = text[at++]) {
                            '"', '\\', '/' -> out.append(escape)
                            'b' -> out.append('\b')
                            'f' -> out.append(0x0C.toChar())
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (at + 4 > text.length) throw JsonException("short unicode escape")
                                val code = text.substring(at, at + 4).toIntOrNull(16)
                                    ?: throw JsonException("bad unicode escape at $at")
                                out.append(code.toChar())
                                at += 4
                            }
                            else -> throw JsonException("bad escape at $at")
                        }
                    }
                    char < ' ' -> throw JsonException("control character in a string at $at")
                    else -> out.append(char)
                }
            }
        }

        private fun number(): Double {
            val start = at
            if (peek() == '-') at++
            if (atEnd()) throw JsonException("bad number at $start")
            if (text[at] == '0') {
                at++
            } else if (text[at] in '1'..'9') {
                digits()
            } else {
                throw JsonException("bad number at $start")
            }
            if (!atEnd() && text[at] == '.') {
                at++
                if (atEnd() || text[at] !in '0'..'9') throw JsonException("bad number at $start")
                digits()
            }
            if (!atEnd() && (text[at] == 'e' || text[at] == 'E')) {
                at++
                if (!atEnd() && (text[at] == '+' || text[at] == '-')) at++
                if (atEnd() || text[at] !in '0'..'9') throw JsonException("bad number at $start")
                digits()
            }
            return text.substring(start, at).toDouble()
        }

        private fun digits() {
            while (!atEnd() && text[at] in '0'..'9') at++
        }
    }
}

/** Reads `map[key]` as an object, or null. */
@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.list(): List<Any?>? = this as? List<Any?>

internal fun Any?.strings(): List<String> = (this.list() ?: emptyList()).filterIsInstance<String>()
