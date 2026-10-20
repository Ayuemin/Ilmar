package com.ayuemin.ilmar.tools

import kotlin.math.pow

/** Bounded arithmetic grammar. No scripting engine, reflection, variables or code execution. */
internal object Calculator {
    fun calculate(expression: String): Double {
        require(expression.length in 1..2048) { "Выражение: от 1 до 2048 символов" }
        val parser = Parser(expression)
        val result = parser.expression()
        parser.space()
        require(parser.position == expression.length && result.isFinite()) { "Некорректное выражение или результат" }
        return result
    }
    private class Parser(val source: String) {
        var position = 0
        private var depth = 0
        private var operations = 0
        fun space() { while (position < source.length && source[position].isWhitespace()) position++ }
        private fun eat(c: Char): Boolean { space(); return if (source.getOrNull(position) == c) { position++; true } else false }
        private fun checked(value: Double): Double { require(++operations <= 512 && value.isFinite()) { "Лимит вычислений или нечисловой результат" }; return value }
        fun expression(): Double {
            require(++depth <= 32) { "Слишком глубокое выражение" }
            try {
                var value = product()
                while (true) value = when { eat('+') -> checked(value + product()); eat('-') -> checked(value - product()); else -> return value }
            } finally { depth-- }
        }
        private fun product(): Double {
            var value = unary()
            while (true) value = when {
                eat('*') -> checked(value * unary())
                eat('/') -> { val divisor = unary(); require(divisor != 0.0) { "Деление на ноль" }; checked(value / divisor) }
                eat('%') -> { val divisor = unary(); require(divisor != 0.0) { "Деление на ноль" }; checked(value % divisor) }
                else -> return value
            }
        }
        private fun unary(): Double {
            require(++depth <= 32) { "Слишком глубокое выражение" }
            try { return when { eat('+') -> unary(); eat('-') -> -unary(); else -> power() } } finally { depth-- }
        }
        private fun power(): Double {
            val base = atom()
            return if (eat('^')) { val exponent = unary(); require(kotlin.math.abs(exponent) <= 10000) { "Слишком большая степень" }; checked(base.pow(exponent)) } else base
        }
        private fun atom(): Double {
            if (eat('(')) { val value = expression(); require(eat(')')) { "Не закрыта скобка" }; return value }
            space(); val start = position
            while (source.getOrNull(position)?.let { it.isDigit() || it == '.' } == true) position++
            if (source.getOrNull(position) in listOf('e', 'E')) {
                position++; if (source.getOrNull(position) in listOf('+', '-')) position++
                while (source.getOrNull(position)?.isDigit() == true) position++
            }
            return source.substring(start, position).toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: throw IllegalArgumentException("Ожидается число")
        }
    }
}
