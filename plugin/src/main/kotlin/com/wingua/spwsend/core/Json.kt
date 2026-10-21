package com.wingua.spwsend.core

/**
 * 极简 JSON 读写实现。
 *
 * 模组不引入任何第三方 JSON 库：一是减少 .spmod 打包体积与冲突风险，
 * 二是协议报文结构固定且简单，手写解析完全可控。
 */
object Json {

    fun parse(text: String): Any? = Parser(text).parseDocument()

    fun stringify(value: Any?): String {
        val sb = StringBuilder(256)
        write(value, sb)
        return sb.toString()
    }

    fun obj(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        for ((k, v) in pairs) map[k] = v
        return map
    }

    fun arr(vararg values: Any?): MutableList<Any?> = values.toMutableList()

    private fun write(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(value, sb)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Float -> writeNumber(value.toDouble(), sb)
            is Double -> writeNumber(value, sb)
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k?.toString() ?: "", sb)
                    sb.append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(item, sb)
                }
                sb.append(']')
            }
            is Array<*> -> write(value.toList(), sb)
            else -> quote(value.toString(), sb)
        }
    }

    private fun writeNumber(value: Double, sb: StringBuilder) {
        if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 9.0E15) {
            sb.append(value.toLong().toString())
        } else {
            sb.append(value.toString())
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
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
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            return value
        }

        private fun parseValue(): Any? {
            if (index >= text.length) throw IllegalArgumentException("JSON 意外结束")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> {
                    if (c == '-' || c.isDigit()) parseNumber()
                    else throw IllegalArgumentException("JSON 字符非法: $c")
                }
            }
        }

        private fun parseObject(): MutableMap<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            index++ // {
            skipWhitespace()
            if (peek() == '}') {
                index++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw IllegalArgumentException("JSON 对象键必须是字符串")
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return map
                    }
                    else -> throw IllegalArgumentException("JSON 对象分隔符非法")
                }
            }
        }

        private fun parseArray(): MutableList<Any?> {
            val list = ArrayList<Any?>()
            index++ // [
            skipWhitespace()
            if (peek() == ']') {
                index++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return list
                    }
                    else -> throw IllegalArgumentException("JSON 数组分隔符非法")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (index < text.length) {
                val c = text[index++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (index >= text.length) throw IllegalArgumentException("JSON 转义意外结束")
                        val e = text[index++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (index + 4 > text.length) {
                                    throw IllegalArgumentException("JSON Unicode 转义不完整")
                                }
                                val hex = text.substring(index, index + 4)
                                index += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("JSON 转义字符非法: $e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("JSON 字符串未闭合")
        }

        private fun parseNumber(): Number {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            var isDouble = false
            if (index < text.length && text[index] == '.') {
                isDouble = true
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                isDouble = true
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            return if (isDouble) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, index)) {
                throw IllegalArgumentException("JSON 字面量非法")
            }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length) {
                val c = text[index]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') index++ else return
            }
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("JSON 期望字符 '$c'")
            index++
        }
    }
}

/** 把解析结果安全地视为 Map。 */
fun Any?.asMap(): Map<String, Any?> {
    val map = this as? Map<*, *> ?: return emptyMap()
    val result = LinkedHashMap<String, Any?>(map.size)
    for ((k, v) in map) result[k?.toString() ?: ""] = v
    return result
}

/** 把解析结果安全地视为 List。 */
fun Any?.asList(): List<Any?> = this as? List<*> ?: emptyList()

fun Map<String, Any?>.str(key: String): String = this[key]?.toString() ?: ""

fun Map<String, Any?>.longValue(key: String): Long = when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull() ?: 0L
    else -> 0L
}

fun Map<String, Any?>.intValue(key: String): Int = longValue(key).toInt()

fun Map<String, Any?>.boolValue(key: String): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is Number -> v.toInt() != 0
    is String -> v.equals("true", ignoreCase = true)
    else -> false
}
