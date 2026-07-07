package org.solvermcp.dsl

import org.solvermcp.domain.GenericElement
import org.solvermcp.domain.GenericEntity
import java.math.BigDecimal
import kotlin.math.abs

class ParseException(message: String, val position: Int) : IllegalArgumentException("$message at position $position")
class EvalException(message: String) : IllegalArgumentException(message)

data class AttrRef(val root: String, val attr: String)

sealed interface Expr {
    val pos: Int
    fun eval(ctx: EvalContext): Any?
    fun attrRefs(): Set<AttrRef> = emptySet()
}

data class LiteralExpr(val value: Any?, override val pos: Int) : Expr {
    override fun eval(ctx: EvalContext): Any? = value
}

data class PathExpr(val parts: List<String>, override val pos: Int) : Expr {
    override fun eval(ctx: EvalContext): Any? = ctx.resolve(parts, pos)

    override fun attrRefs(): Set<AttrRef> {
        val attrsIndex = parts.indexOf("attrs")
        if (attrsIndex <= 0 || attrsIndex == parts.lastIndex) return emptySet()
        val root = if (attrsIndex >= 2 && parts[attrsIndex - 1] == "value") "value" else parts[0]
        return setOf(AttrRef(root, parts[attrsIndex + 1]))
    }
}

data class UnaryExpr(val op: String, val inner: Expr, override val pos: Int) : Expr {
    override fun eval(ctx: EvalContext): Any? = when (op) {
        "!" -> !asBoolean(inner.eval(ctx), pos)
        "-" -> -asNumber(inner.eval(ctx), pos)
        else -> throw EvalException("Unknown unary operator '$op'")
    }

    override fun attrRefs(): Set<AttrRef> = inner.attrRefs()
}

data class BinaryExpr(val left: Expr, val op: String, val right: Expr, override val pos: Int) : Expr {
    override fun eval(ctx: EvalContext): Any? = when (op) {
        "&&" -> asBoolean(left.eval(ctx), pos) && asBoolean(right.eval(ctx), pos)
        "||" -> asBoolean(left.eval(ctx), pos) || asBoolean(right.eval(ctx), pos)
        "==" -> equalValues(left.eval(ctx), right.eval(ctx))
        "!=" -> !equalValues(left.eval(ctx), right.eval(ctx))
        "<" -> compareValues(left.eval(ctx), right.eval(ctx), pos) < 0
        "<=" -> compareValues(left.eval(ctx), right.eval(ctx), pos) <= 0
        ">" -> compareValues(left.eval(ctx), right.eval(ctx), pos) > 0
        ">=" -> compareValues(left.eval(ctx), right.eval(ctx), pos) >= 0
        "+" -> addValues(left.eval(ctx), right.eval(ctx), pos)
        "-" -> asNumber(left.eval(ctx), pos) - asNumber(right.eval(ctx), pos)
        "*" -> asNumber(left.eval(ctx), pos) * asNumber(right.eval(ctx), pos)
        "/" -> asNumber(left.eval(ctx), pos) / asNumber(right.eval(ctx), pos)
        "%" -> asNumber(left.eval(ctx), pos) % asNumber(right.eval(ctx), pos)
        else -> throw EvalException("Unknown binary operator '$op'")
    }

    override fun attrRefs(): Set<AttrRef> = left.attrRefs() + right.attrRefs()
}

class EvalContext(
    var entity: GenericEntity? = null,
    var value: GenericElement? = null,
    var a: GenericEntity? = null,
    var b: GenericEntity? = null,
    var group: Any? = null
) {
    fun withEntity(entity: GenericEntity): EvalContext {
        this.entity = entity
        this.value = entity.value
        this.a = null
        this.b = null
        this.group = null
        return this
    }

    fun withPair(a: GenericEntity, b: GenericEntity): EvalContext {
        this.entity = a
        this.value = a.value
        this.a = a
        this.b = b
        this.group = null
        return this
    }

    fun withValue(value: GenericElement, entity: GenericEntity? = null): EvalContext {
        this.entity = entity
        this.value = value
        this.a = null
        this.b = null
        this.group = null
        return this
    }

    fun withGroup(group: Any?): EvalContext {
        this.entity = null
        this.value = null
        this.a = null
        this.b = null
        this.group = group
        return this
    }

    fun resolve(parts: List<String>, pos: Int): Any? {
        if (parts.isEmpty()) throw EvalException("Empty path at position $pos")
        var current: Any? = when (parts[0]) {
            "entity" -> entity ?: throw EvalException("Identifier 'entity' is unavailable at position $pos")
            "value" -> value ?: throw EvalException("Identifier 'value' is unavailable at position $pos")
            "a" -> a ?: throw EvalException("Identifier 'a' is unavailable at position $pos")
            "b" -> b ?: throw EvalException("Identifier 'b' is unavailable at position $pos")
            "group" -> group
            else -> throw EvalException("Unknown identifier '${parts[0]}' at position $pos")
        }
        for (part in parts.drop(1)) {
            current = property(current, part, pos)
        }
        return current
    }

    private fun property(target: Any?, name: String, pos: Int): Any? {
        if (target == null) return null
        return when (target) {
            is GenericElement -> when (name) {
                "id" -> target.id
                "attrs" -> target.attrs
                else -> throw EvalException("Element has no property '$name' at position $pos")
            }
            is GenericEntity -> when (name) {
                "id" -> target.id
                "element" -> target.element
                "attrs" -> target.element.attrs
                "value" -> target.value
                else -> throw EvalException("Entity has no property '$name' at position $pos")
            }
            is Map<*, *> -> target[name]
            else -> throw EvalException("Cannot read property '$name' from ${target::class.simpleName} at position $pos")
        }
    }
}

object Expressions {
    fun parse(source: String): Expr = Parser(Lexer(source).lex()).parse()
}

private enum class TokenType {
    IDENT, NUMBER, STRING, BOOLEAN, NULL, OP, DOT, LPAREN, RPAREN, EOF
}

private data class Token(val type: TokenType, val text: String, val pos: Int, val value: Any? = null)

private class Lexer(private val source: String) {
    private var pos = 0
    private val tokens = mutableListOf<Token>()

    fun lex(): List<Token> {
        while (pos < source.length) {
            val ch = source[pos]
            when {
                ch.isWhitespace() -> pos++
                ch.isLetter() || ch == '_' -> lexIdent()
                ch.isDigit() -> lexNumber()
                ch == '"' -> lexString()
                ch == '.' -> add(TokenType.DOT, ".")
                ch == '(' -> add(TokenType.LPAREN, "(")
                ch == ')' -> add(TokenType.RPAREN, ")")
                else -> lexOperator()
            }
        }
        tokens += Token(TokenType.EOF, "", pos)
        return tokens
    }

    private fun lexIdent() {
        val start = pos
        pos++
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        val text = source.substring(start, pos)
        when (text) {
            "true" -> tokens += Token(TokenType.BOOLEAN, text, start, true)
            "false" -> tokens += Token(TokenType.BOOLEAN, text, start, false)
            "null" -> tokens += Token(TokenType.NULL, text, start, null)
            else -> tokens += Token(TokenType.IDENT, text, start)
        }
    }

    private fun lexNumber() {
        val start = pos
        pos++
        while (pos < source.length && source[pos].isDigit()) pos++
        if (pos < source.length && source[pos] == '.') {
            pos++
            if (pos >= source.length || !source[pos].isDigit()) throw ParseException("Expected digit after decimal point", pos)
            while (pos < source.length && source[pos].isDigit()) pos++
        }
        val text = source.substring(start, pos)
        tokens += Token(TokenType.NUMBER, text, start, text.toDouble())
    }

    private fun lexString() {
        val start = pos
        pos++
        val builder = StringBuilder()
        while (pos < source.length) {
            val ch = source[pos++]
            when (ch) {
                '"' -> {
                    tokens += Token(TokenType.STRING, source.substring(start, pos), start, builder.toString())
                    return
                }
                '\\' -> {
                    if (pos >= source.length) throw ParseException("Unterminated escape sequence", pos)
                    val escaped = source[pos++]
                    builder.append(
                        when (escaped) {
                            '"', '\\', '/' -> escaped
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> throw ParseException("Unsupported escape '\\$escaped'", pos - 1)
                        }
                    )
                }
                else -> builder.append(ch)
            }
        }
        throw ParseException("Unterminated string literal", start)
    }

    private fun lexOperator() {
        val start = pos
        val two = if (pos + 1 < source.length) source.substring(pos, pos + 2) else ""
        if (two in setOf("<=", ">=", "==", "!=", "&&", "||")) {
            pos += 2
            tokens += Token(TokenType.OP, two, start)
            return
        }
        val one = source[pos].toString()
        if (one in setOf("<", ">", "+", "-", "*", "/", "%", "!")) {
            pos++
            tokens += Token(TokenType.OP, one, start)
            return
        }
        throw ParseException("Unexpected character '${source[pos]}'", pos)
    }

    private fun add(type: TokenType, text: String) {
        tokens += Token(type, text, pos)
        pos++
    }
}

private class Parser(private val tokens: List<Token>) {
    private var index = 0
    private val current: Token get() = tokens[index]

    fun parse(): Expr {
        val expr = parseOr()
        if (current.type != TokenType.EOF) throw ParseException("Unexpected token '${current.text}'", current.pos)
        return expr
    }

    private fun parseOr(): Expr = binary(::parseAnd, "||")
    private fun parseAnd(): Expr = binary(::parseEquality, "&&")
    private fun parseEquality(): Expr = binary(::parseComparison, "==", "!=")
    private fun parseComparison(): Expr = binary(::parseTerm, "<", "<=", ">", ">=")
    private fun parseTerm(): Expr = binary(::parseFactor, "+", "-")
    private fun parseFactor(): Expr = binary(::parseUnary, "*", "/", "%")

    private fun binary(next: () -> Expr, vararg ops: String): Expr {
        var left = next()
        while (current.type == TokenType.OP && current.text in ops) {
            val op = current
            index++
            val right = next()
            left = BinaryExpr(left, op.text, right, op.pos)
        }
        return left
    }

    private fun parseUnary(): Expr {
        if (current.type == TokenType.OP && current.text in setOf("!", "-")) {
            val token = current
            index++
            return UnaryExpr(token.text, parseUnary(), token.pos)
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        val token = current
        return when (token.type) {
            TokenType.NUMBER, TokenType.STRING, TokenType.BOOLEAN, TokenType.NULL -> {
                index++
                LiteralExpr(token.value, token.pos)
            }
            TokenType.IDENT -> parsePath()
            TokenType.LPAREN -> {
                index++
                val inner = parseOr()
                expect(TokenType.RPAREN, ")")
                inner
            }
            else -> throw ParseException("Expected expression", token.pos)
        }
    }

    private fun parsePath(): Expr {
        val start = current.pos
        val parts = mutableListOf(expect(TokenType.IDENT, "identifier").text)
        while (current.type == TokenType.DOT) {
            index++
            parts += expect(TokenType.IDENT, "identifier after '.'").text
        }
        return PathExpr(parts, start)
    }

    private fun expect(type: TokenType, label: String): Token {
        val token = current
        if (token.type != type) throw ParseException("Expected $label", token.pos)
        index++
        return token
    }
}

fun asBoolean(value: Any?, pos: Int = 0): Boolean =
    value as? Boolean ?: throw EvalException("Expected boolean at position $pos but got ${typeName(value)}")

fun asNumber(value: Any?, pos: Int = 0): Double = when (value) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull() ?: throw EvalException("Expected number at position $pos but got string '$value'")
    else -> throw EvalException("Expected number at position $pos but got ${typeName(value)}")
}

fun asScoreLong(value: Any?, pos: Int = 0): Long {
    val number = asNumber(value, pos)
    if (!number.isFinite()) throw EvalException("Expected finite number at position $pos")
    return abs(number).toLong()
}

private fun addValues(left: Any?, right: Any?, pos: Int): Any? {
    if (left is String || right is String) return "${left ?: "null"}${right ?: "null"}"
    return asNumber(left, pos) + asNumber(right, pos)
}

private fun equalValues(left: Any?, right: Any?): Boolean {
    if (left is Number && right is Number) {
        return BigDecimal.valueOf(left.toDouble()).compareTo(BigDecimal.valueOf(right.toDouble())) == 0
    }
    return left == right
}

private fun compareValues(left: Any?, right: Any?, pos: Int): Int {
    if (left is Number && right is Number) return left.toDouble().compareTo(right.toDouble())
    if (left is String && right is String) return left.compareTo(right)
    if (left is Boolean && right is Boolean) return left.compareTo(right)
    throw EvalException("Cannot compare ${typeName(left)} and ${typeName(right)} at position $pos")
}

private fun typeName(value: Any?): String = value?.let { it::class.simpleName ?: it::class.java.name } ?: "null"
