package app.ledgerly.core.parser

/**
 * Minimal, dependency-free JSON reader/writer used for template libraries and LLM output.
 * Values map to: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 */
object MiniJson {

    class JsonException(message: String) : RuntimeException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw JsonException("Trailing content at ${p.i}")
        return v
    }

    fun stringify(value: Any?, indent: String? = "  "): String = StringBuilder().also { write(it, value, indent, 0) }.toString()

    private fun write(sb: StringBuilder, v: Any?, indent: String?, depth: Int) {
        fun nl(d: Int) { if (indent != null) { sb.append('\n'); repeat(d) { sb.append(indent) } } }
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isFinite()) sb.append(v.toString()) else sb.append("null")
            is Float -> if (v.isFinite()) sb.append(v.toString()) else sb.append("null")
            is Number -> sb.append(v.toString())
            is Enum<*> -> quote(sb, v.name)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    nl(depth + 1)
                    quote(sb, k.toString())
                    sb.append(if (indent != null) ": " else ":")
                    write(sb, value, indent, depth + 1)
                }
                if (v.isNotEmpty()) nl(depth)
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    nl(depth + 1)
                    write(sb, item, indent, depth + 1)
                }
                if (v.any()) nl(depth)
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(depth: Int): Any? {
            if (depth > 64) throw JsonException("Too deeply nested")
            ws()
            if (i >= s.length) throw JsonException("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonException("Unexpected '$c' at $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("Expected $word at $i")
            i += word.length
            return v
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw JsonException("Bad number '$t'")
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonException("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw JsonException("Bad unicode escape"))
                                i += 4
                            }
                            else -> throw JsonException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw JsonException("Expected key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw JsonException("Expected ':' at $i")
                i++
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw JsonException("Unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw JsonException("Expected ',' or '}' at ${i - 1}")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l += value(depth + 1)
                ws()
                if (i >= s.length) throw JsonException("Unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw JsonException("Expected ',' or ']' at ${i - 1}")
                }
            }
        }
    }
}
