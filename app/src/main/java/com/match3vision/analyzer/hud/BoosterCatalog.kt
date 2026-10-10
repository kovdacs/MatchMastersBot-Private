package com.match3vision.analyzer.hud

/**
 * Sourced catalog of boosters, powered-up skins, and perks.
 * `v1_safe` in the file is research. It is not permission to tap.
 */
class BoosterCatalog(val entries: List<BoosterEntry>) {
    private val byId = entries.associateBy { it.id }

    init {
        require(byId.size == entries.size) { "duplicate booster id" }
    }

    fun get(id: String): BoosterEntry? = byId[id]

    fun count(kind: String): Int = entries.count { it.kind == kind }

    companion object {
        const val ASSET_PATH = "booster_db.json"

        fun parse(text: String): BoosterCatalog {
            val root = Json.parse(text) as? Json.Arr ?: error("booster db must be a JSON array")
            val entries = root.items.map { item ->
                val obj = item as? Json.Obj ?: error("booster entry must be an object")
                BoosterEntry(
                    id = obj.string("id"),
                    name = obj.string("name"),
                    kind = obj.string("kind"),
                    category = obj.string("category"),
                    targetRequirement = obj.string("target_requirement"),
                    v1Safe = obj.bool("v1_safe"),
                )
            }
            return BoosterCatalog(entries)
        }
    }
}

data class BoosterEntry(
    val id: String,
    val name: String,
    val kind: String,
    val category: String,
    val targetRequirement: String,
    val v1Safe: Boolean,
)

/**
 * Minimal JSON reader for [BoosterCatalog]. The unit-test classpath has no org.json.
 */
object Json {
    fun parse(text: String): Value {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.finish()
        return value
    }

    sealed class Value
    data class Obj(val fields: Map<String, Value>) : Value() {
        fun string(key: String): String = (fields[key] as? Str)?.value
            ?: error("missing string $key")

        fun bool(key: String): Boolean = (fields[key] as? Bool)?.value
            ?: error("missing bool $key")
    }

    data class Arr(val items: List<Value>) : Value()
    data class Str(val value: String) : Value()
    data class Num(val raw: String) : Value()
    data class Bool(val value: Boolean) : Value()
    data object Null : Value()

    fun write(value: Value): String = buildString { emit(value) }

    private fun StringBuilder.emit(value: Value) {
        when (value) {
            is Obj -> {
                append('{')
                value.fields.entries.forEachIndexed { index, (key, child) ->
                    if (index > 0) append(',')
                    emit(Str(key))
                    append(':')
                    emit(child)
                }
                append('}')
            }
            is Arr -> {
                append('[')
                value.items.forEachIndexed { index, child ->
                    if (index > 0) append(',')
                    emit(child)
                }
                append(']')
            }
            is Str -> {
                append('"')
                value.value.forEach { c ->
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (c.code < 0x20) {
                            append("\\u")
                            append(c.code.toString(16).padStart(4, '0'))
                        } else {
                            append(c)
                        }
                    }
                }
                append('"')
            }
            is Num -> append(value.raw)
            is Bool -> append(if (value.value) "true" else "false")
            Null -> append("null")
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Value {
            skip()
            if (i >= s.length) error("unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Str(string())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                '-', in '0'..'9' -> number()
                else -> error("unexpected '${c}' at $i")
            }
        }

        fun finish() {
            skip()
            if (i != s.length) error("trailing JSON at $i")
        }

        private fun obj(): Obj {
            expect('{')
            val fields = LinkedHashMap<String, Value>()
            skip()
            if (peek('}')) {
                i++
                return Obj(fields)
            }
            while (true) {
                skip()
                val key = string()
                skip()
                expect(':')
                fields[key] = parseValue()
                skip()
                when {
                    peek(',') -> i++
                    peek('}') -> {
                        i++
                        return Obj(fields)
                    }
                    else -> error("expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(): Arr {
            expect('[')
            val items = ArrayList<Value>()
            skip()
            if (peek(']')) {
                i++
                return Arr(items)
            }
            while (true) {
                items += parseValue()
                skip()
                when {
                    peek(',') -> i++
                    peek(']') -> {
                        i++
                        return Arr(items)
                    }
                    else -> error("expected ',' or ']' at $i")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= s.length) error("bad escape")
                        out.append(
                            when (val e = s[i++]) {
                                '"', '\\', '/' -> e
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    val hex = s.substring(i, (i + 4).coerceAtMost(s.length))
                                    if (hex.length < 4) error("bad unicode escape")
                                    i += 4
                                    hex.toInt(16).toChar()
                                }
                                else -> error("bad escape \\$e")
                            },
                        )
                    }
                    else -> out.append(c)
                }
            }
            error("unterminated string")
        }

        private fun number(): Num {
            val start = i
            if (peek('-')) i++
            while (i < s.length && s[i].isDigit()) i++
            if (peek('.')) {
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (peek('+') || peek('-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i == start) error("bad number at $i")
            return Num(s.substring(start, i))
        }

        private fun literal(word: String, value: Value): Value {
            if (!s.startsWith(word, i)) error("expected $word at $i")
            i += word.length
            return value
        }

        private fun expect(c: Char) {
            skip()
            if (i >= s.length || s[i] != c) error("expected '$c' at $i")
            i++
        }

        private fun peek(c: Char): Boolean = i < s.length && s[i] == c

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
