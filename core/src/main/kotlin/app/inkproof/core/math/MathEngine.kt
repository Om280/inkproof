package app.inkproof.core.math

import app.inkproof.core.model.ANALYSIS_SCHEMA_VERSION
import kotlin.math.abs
import kotlin.math.sqrt

class ParseException(message: String) : Exception(message)

sealed class Expr {
    data class Num(val v: Double) : Expr()
    data class Var(val name: String) : Expr()
    data class Neg(val e: Expr) : Expr()
    data class Add(val a: Expr, val b: Expr) : Expr()
    data class Sub(val a: Expr, val b: Expr) : Expr()
    data class Mul(val a: Expr, val b: Expr) : Expr()
    data class Div(val a: Expr, val b: Expr) : Expr()
    data class Pow(val a: Expr, val b: Expr) : Expr()
    data class Call(val name: String, val arg: Expr) : Expr()
}

data class Equation(val left: Expr, val right: Expr)

data class Poly(val coeffs: List<Double>) {
    fun coeff(power: Int): Double = coeffs.getOrElse(power) { 0.0 }

    fun degree(): Int {
        for (i in coeffs.lastIndex downTo 0) {
            if (abs(coeffs[i]) > 1e-9) return i
        }
        return -1
    }

    fun trim(): Poly {
        val d = degree()
        if (d < 0) return Poly(listOf(0.0))
        return Poly(coeffs.take(d + 1))
    }

    operator fun plus(other: Poly): Poly {
        val n = maxOf(coeffs.size, other.coeffs.size)
        return Poly(List(n) { coeff(it) + other.coeff(it) }).trim()
    }

    operator fun minus(other: Poly): Poly = this + (other * -1.0)

    operator fun times(k: Double): Poly = Poly(coeffs.map { it * k }).trim()

    operator fun unaryMinus(): Poly = this * -1.0

    operator fun times(other: Poly): Poly {
        if (degree() < 0 || other.degree() < 0) return Poly(listOf(0.0))
        val out = DoubleArray(coeffs.size + other.coeffs.size)
        for (i in coeffs.indices) {
            for (j in other.coeffs.indices) {
                out[i + j] += coeffs[i] * other.coeffs[j]
            }
        }
        return Poly(out.toList()).trim()
    }

    fun pow(n: Int): Poly {
        require(n >= 0)
        var result = Poly(listOf(1.0))
        repeat(n) { result *= this }
        return result.trim()
    }

    fun eval(x: Double): Double {
        var acc = 0.0
        var xp = 1.0
        for (c in coeffs) {
            acc += c * xp
            xp *= x
        }
        return acc
    }

    fun derivative(): Poly {
        if (coeffs.size <= 1) return Poly(listOf(0.0))
        return Poly(coeffs.drop(1).mapIndexed { i, c -> c * (i + 1) }).trim()
    }

    fun integral(): Poly {
        val out = DoubleArray(coeffs.size + 1)
        for (i in coeffs.indices) {
            out[i + 1] = coeffs[i] / (i + 1)
        }
        return Poly(out.toList()).trim()
    }

    fun nearlyEquals(other: Poly, eps: Double = 1e-6): Boolean {
        val n = maxOf(degree(), other.degree(), 0)
        for (i in 0..n) {
            if (abs(coeff(i) - other.coeff(i)) > eps) return false
        }
        return true
    }
}

fun polyConst(v: Double) = Poly(listOf(v))

private val FUNCTIONS = listOf("sqrt", "sin", "cos", "tan", "ln", "log", "abs")

fun normalizeMath(raw: String): String {
    val superscripts = mapOf(
        '⁰' to "0", '¹' to "1", '²' to "2", '³' to "3", '⁴' to "4",
        '⁵' to "5", '⁶' to "6", '⁷' to "7", '⁸' to "8", '⁹' to "9",
    )
    val sb = StringBuilder()
    var i = 0
    val src = raw.trim()
        .replace('−', '-')
        .replace('–', '-')
        .replace('—', '-')
        .replace('×', '*')
        .replace('·', '*')
        .replace('÷', '/')
        .replace("∫", "integral")
    while (i < src.length) {
        val ch = src[i]
        val sup = superscripts[ch]
        if (sup != null) {
            sb.append('^').append(sup)
            i++
            continue
        }
        if (ch == '√') {
            sb.append("sqrt")
            i++
            continue
        }
        if (!ch.isWhitespace()) sb.append(ch.lowercaseChar())
        i++
    }
    return sb.toString()
        .replace("derivativeof", "d/dx")
        .replace("dy/dx", "d/dx")
}

class ExprParser(private val s: String) {
    private var i = 0

    fun parse(): Expr {
        if (s.isEmpty()) throw ParseException("empty")
        val e = parseExpr()
        if (i != s.length) throw ParseException("trailing at $i in $s")
        return e
    }

    private fun parseExpr(): Expr {
        var left = parseTerm()
        while (i < s.length && (s[i] == '+' || s[i] == '-')) {
            val op = s[i++]
            val right = parseTerm()
            left = if (op == '+') Expr.Add(left, right) else Expr.Sub(left, right)
        }
        return left
    }

    private fun parseTerm(): Expr {
        var left = parsePow()
        while (i < s.length) {
            when {
                s[i] == '*' -> {
                    i++
                    left = Expr.Mul(left, parsePow())
                }
                s[i] == '/' -> {
                    i++
                    left = Expr.Div(left, parsePow())
                }
                canImplicit() -> left = Expr.Mul(left, parsePow())
                else -> break
            }
        }
        return left
    }

    private fun canImplicit(): Boolean {
        if (i >= s.length) return false
        val c = s[i]
        return c == '(' || c.isLetter() || c.isDigit()
    }

    private fun parsePow(): Expr {
        val base = parseUnary()
        if (i < s.length && s[i] == '^') {
            i++
            val exp = parseUnary()
            return Expr.Pow(base, exp)
        }
        return base
    }

    private fun parseUnary(): Expr {
        if (i < s.length && s[i] == '+') {
            i++
            return parseUnary()
        }
        if (i < s.length && s[i] == '-') {
            i++
            return Expr.Neg(parseUnary())
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        if (i >= s.length) throw ParseException("unexpected end")
        if (s[i] == '(') {
            i++
            val e = parseExpr()
            if (i >= s.length || s[i] != ')') throw ParseException("missing )")
            i++
            return e
        }
        for (fn in FUNCTIONS) {
            if (s.startsWith(fn, i)) {
                val after = i + fn.length
                val boundary = after >= s.length || !s[after].isLetter()
                if (boundary) {
                    i = after
                    val arg = if (i < s.length && s[i] == '(') {
                        i++
                        val e = parseExpr()
                        if (i >= s.length || s[i] != ')') throw ParseException("missing )")
                        i++
                        e
                    } else {
                        parseUnary()
                    }
                    return Expr.Call(fn, arg)
                }
            }
        }
        if (s.startsWith("pi", i) && (i + 2 >= s.length || !s[i + 2].isLetter())) {
            i += 2
            return Expr.Num(Math.PI)
        }
        if (s[i].isLetter()) {
            val name = s[i].toString()
            i++
            return Expr.Var(name)
        }
        if (s[i].isDigit() || s[i] == '.') return parseNumber()
        throw ParseException("bad char '${s[i]}' at $i")
    }

    private fun parseNumber(): Expr {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        val token = s.substring(start, i)
        val v = token.toDoubleOrNull() ?: throw ParseException("bad number $token")
        return Expr.Num(v)
    }
}

fun parseExpr(raw: String): Expr = ExprParser(normalizeMath(raw)).parse()

fun tryParseExpr(raw: String): Expr? = try {
    parseExpr(raw)
} catch (_: Exception) {
    null
}

fun splitEquation(s: String): Pair<String, String>? {
    var depth = 0
    for (idx in s.indices) {
        when (s[idx]) {
            '(' -> depth++
            ')' -> depth--
            '=' -> if (depth == 0) return s.substring(0, idx) to s.substring(idx + 1)
        }
    }
    return null
}

fun parseEquation(raw: String): Equation? {
    val n = normalizeMath(raw)
    val parts = splitEquation(n) ?: return null
    if (parts.first.isEmpty() || parts.second.isEmpty()) return null
    return try {
        Equation(ExprParser(parts.first).parse(), ExprParser(parts.second).parse())
    } catch (_: Exception) {
        null
    }
}

fun toPoly(expr: Expr, variable: String = "x"): Poly? = when (expr) {
    is Expr.Num -> polyConst(expr.v)
    is Expr.Var -> if (expr.name == variable) Poly(listOf(0.0, 1.0)) else null
    is Expr.Neg -> toPoly(expr.e, variable)?.let { -it }
    is Expr.Add -> {
        val a = toPoly(expr.a, variable) ?: return null
        val b = toPoly(expr.b, variable) ?: return null
        a + b
    }
    is Expr.Sub -> {
        val a = toPoly(expr.a, variable) ?: return null
        val b = toPoly(expr.b, variable) ?: return null
        a - b
    }
    is Expr.Mul -> {
        val a = toPoly(expr.a, variable) ?: return null
        val b = toPoly(expr.b, variable) ?: return null
        a * b
    }
    is Expr.Div -> {
        val a = toPoly(expr.a, variable) ?: return null
        val b = toPoly(expr.b, variable) ?: return null
        if (b.degree() != 0) return null
        val d = b.coeff(0)
        if (abs(d) < 1e-12) return null
        a * (1.0 / d)
    }
    is Expr.Pow -> {
        val base = toPoly(expr.a, variable) ?: return null
        val exp = expr.b
        val n = (exp as? Expr.Num)?.v ?: return null
        if (n < 0 || n > 12 || n != kotlin.math.floor(n)) return null
        base.pow(n.toInt())
    }
    is Expr.Call -> null
}

data class Rational(val num: Poly, val den: Poly)

fun toRational(expr: Expr, variable: String = "x"): Rational? = when (expr) {
    is Expr.Div -> {
        val n = toPoly(expr.a, variable) ?: return null
        val d = toPoly(expr.b, variable) ?: return null
        if (d.degree() < 0) return null
        Rational(n, d)
    }
    else -> toPoly(expr, variable)?.let { Rational(it, polyConst(1.0)) }
}

fun roots(poly: Poly): List<Double>? {
    val p = poly.trim()
    val d = p.degree()
    if (d < 0) return null
    if (d == 0) return if (abs(p.coeff(0)) < 1e-8) null else emptyList()
    if (d == 1) return listOf(-p.coeff(0) / p.coeff(1))
    if (d == 2) {
        val a = p.coeff(2)
        val b = p.coeff(1)
        val c = p.coeff(0)
        val disc = b * b - 4 * a * c
        if (disc < -1e-7) return emptyList()
        if (disc <= 1e-7) return listOf(-b / (2 * a))
        val s = sqrt(disc)
        return listOf((-b - s) / (2 * a), (-b + s) / (2 * a))
    }
    return null
}

fun divideByLinear(poly: Poly, root: Double): Poly? {
    val deg = poly.degree()
    if (deg < 1) return null
    val highToLow = (deg downTo 0).map { poly.coeff(it) }
    val out = ArrayList<Double>(highToLow.size)
    var acc = 0.0
    for (coef in highToLow) {
        acc = coef + acc * root
        out.add(acc)
    }
    val remainder = out.removeAt(out.lastIndex)
    if (abs(remainder) > 1e-6) return null
    val coeffs = DoubleArray(out.size)
    for (i in out.indices) {
        val power = out.size - 1 - i
        coeffs[power] = out[i]
    }
    return Poly(coeffs.toList()).trim()
}

sealed class Problem {
    data class SolveEq(val equation: Equation, val variable: String = "x") : Problem()
    data class Derivative(val variable: String, val expr: Expr) : Problem()
    data class Integral(val variable: String, val expr: Expr, val lower: Expr?, val upper: Expr?) : Problem()
    data class Limit(val variable: String, val approaching: Double, val expr: Expr) : Problem()
    data class Unsupported(val reason: String) : Problem()
}

fun classifyProblem(raw: String): Problem {
    val n = normalizeMath(raw)
    if (n.isBlank()) return Problem.Unsupported("empty question")
    if (n.startsWith("d/dx")) {
        val body = n.removePrefix("d/dx")
        val expr = tryParseExpr(body) ?: return Problem.Unsupported("could not read the derivative")
        return Problem.Derivative("x", expr)
    }
    if (n.startsWith("lim") || n.startsWith("limit")) {
        return parseLimit(n) ?: Problem.Unsupported("could not read the limit")
    }
    if (n.startsWith("integral")) {
        return parseIntegral(n) ?: Problem.Unsupported("could not read the integral")
    }
    if ('=' in n) {
        val eq = parseEquation(n) ?: return Problem.Unsupported("could not read the equation")
        val poly = toPoly(Expr.Sub(eq.left, eq.right))
        if (poly == null || poly.degree() > 2) return Problem.Unsupported("equation is not a supported polynomial")
        return Problem.SolveEq(eq)
    }
    return Problem.Unsupported("not a supported question form")
}

private fun parseLimit(n: String): Problem? {
    var rest = if (n.startsWith("limit")) n.removePrefix("limit") else n.removePrefix("lim")
    rest = rest.removePrefix("_").removePrefix("{")
    if (rest.startsWith("}")) return null
    if (!rest.startsWith("x->") && !rest.startsWith("x→")) {
        val arrow = rest.indexOf("->")
        if (arrow < 0) return null
        rest = rest.substring(arrow + 2)
    } else {
        rest = rest.removePrefix("x->").removePrefix("x→")
    }
    if (rest.startsWith("}")) rest = rest.removePrefix("}")
    val (approaching, body) = readNumberToken(rest) ?: return null
    val expr = tryParseExpr(body) ?: return null
    return Problem.Limit("x", approaching, expr)
}

private fun parseIntegral(n: String): Problem? {
    var rest = n.removePrefix("integral")
    var lower: Expr? = null
    var upper: Expr? = null
    if (rest.startsWith("_")) {
        rest = rest.removePrefix("_")
        if (rest.startsWith("{")) {
            val end = rest.indexOf('}')
            if (end < 0) return null
            lower = tryParseExpr(rest.substring(1, end)) ?: return null
            rest = rest.substring(end + 1)
        } else {
            val hat = rest.indexOf('^')
            if (hat < 0) return null
            lower = tryParseExpr(rest.substring(0, hat)) ?: return null
            rest = rest.substring(hat + 1)
        }
        if (rest.startsWith("^")) rest = rest.removePrefix("^")
        if (rest.startsWith("{")) {
            val end = rest.indexOf('}')
            if (end < 0) return null
            upper = tryParseExpr(rest.substring(1, end)) ?: return null
            rest = rest.substring(end + 1)
        } else {
            val (value, rem) = readNumberToken(rest) ?: return null
            upper = Expr.Num(value)
            rest = rem
        }
    }
    if (rest.length < 3 || rest[rest.lastIndex - 1] != 'd' || !rest.last().isLetter()) return null
    val variable = rest.last().toString()
    val body = rest.substring(0, rest.length - 2)
    val expr = tryParseExpr(body) ?: return null
    return Problem.Integral(variable, expr, lower, upper)
}

private fun readNumberToken(s: String): Pair<Double, String>? {
    if (s.isEmpty()) return null
    var i = 0
    if (s[0] == '-') i++
    val startDigits = i
    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
    if (i == startDigits) return null
    val n = s.substring(0, i).toDoubleOrNull() ?: return null
    return n to s.substring(i)
}

fun evalNumeric(expr: Expr): Double? = when (expr) {
    is Expr.Num -> expr.v
    is Expr.Var -> null
    is Expr.Neg -> evalNumeric(expr.e)?.let { -it }
    is Expr.Add -> {
        val a = evalNumeric(expr.a) ?: return null
        val b = evalNumeric(expr.b) ?: return null
        a + b
    }
    is Expr.Sub -> {
        val a = evalNumeric(expr.a) ?: return null
        val b = evalNumeric(expr.b) ?: return null
        a - b
    }
    is Expr.Mul -> {
        val a = evalNumeric(expr.a) ?: return null
        val b = evalNumeric(expr.b) ?: return null
        a * b
    }
    is Expr.Div -> {
        val a = evalNumeric(expr.a) ?: return null
        val b = evalNumeric(expr.b) ?: return null
        if (abs(b) < 1e-12) null else a / b
    }
    is Expr.Pow -> {
        val a = evalNumeric(expr.a) ?: return null
        val b = evalNumeric(expr.b) ?: return null
        Math.pow(a, b)
    }
    is Expr.Call -> {
        val arg = evalNumeric(expr.arg) ?: return null
        when (expr.name) {
            "sqrt" -> if (arg < 0) null else sqrt(arg)
            "abs" -> abs(arg)
            "sin" -> kotlin.math.sin(arg)
            "cos" -> kotlin.math.cos(arg)
            "tan" -> kotlin.math.tan(arg)
            "ln", "log" -> if (arg <= 0) null else kotlin.math.ln(arg)
            else -> null
        }
    }
}

fun valuesFromAnswer(text: String): List<Double>? {
    val n = normalizeMath(text)
    if (n.isBlank()) return null
    if ("±" in text || "±" in n) {
        val plus = evalAnswer(n.replace("±", "+"))
        val minus = evalAnswer(n.replace("±", "-"))
        if (plus != null && minus != null) return listOf(minus, plus)
    }
    val parts = n.split(Regex("[,;]|\\bor\\b|&")).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size > 1) {
        return parts.map { evalAnswer(it) ?: return null }
    }
    return evalAnswer(n)?.let { listOf(it) }
}

fun evalAnswer(text: String): Double? {
    var n = normalizeMath(text)
    n = n.removePrefix("x=").removePrefix("y=")
    val eq = splitEquation(n)
    val body = if (eq != null) {
        val leftVar = eq.first == "x" || eq.first == "y"
        if (leftVar) eq.second else return null
    } else {
        n
    }
    val cleaned = body.removeSuffix("+c")
    return tryParseExpr(cleaned)?.let { evalNumeric(it) }
}

fun setsMatch(expected: List<Double>, actual: List<Double>, tol: Double): Boolean {
    if (expected.size != actual.size) return false
    val used = BooleanArray(actual.size)
    for (e in expected) {
        var found = -1
        for (i in actual.indices) {
            if (!used[i] && abs(actual[i] - e) <= tol) {
                found = i
                break
            }
        }
        if (found < 0) return false
        used[found] = true
    }
    return true
}

fun isSubset(part: List<Double>, full: List<Double>, tol: Double): Boolean {
    return part.all { p -> full.any { abs(it - p) <= tol } }
}

private fun formatNumber(v: Double): String {
    val rounded = kotlin.math.round(v * 1e6) / 1e6
    return if (abs(rounded - rounded.toLong()) < 1e-6) rounded.toLong().toString() else rounded.toString()
}

fun formatRoots(roots: List<Double>): String = when (roots.size) {
    0 -> "no real solution"
    1 -> "x = ${formatNumber(roots[0])}"
    else -> roots.joinToString(", ") { "x = ${formatNumber(it)}" }
}

fun prettyQuadratic(a: Double, b: Double, c: Double): String {
    val disc = b * b - 4 * a * c
    if (disc < 0) return "no real solution"
    val roots = roots(Poly(listOf(c, b, a))) ?: return formatRoots(emptyList())
    if (abs(disc) < 1e-8) return formatRoots(roots)
    val sqrtDisc = sqrt(disc)
    val half = -b / (2 * a)
    val pm = sqrtDisc / (2 * a)
    if (abs(a - 1.0) < 1e-9 && abs(half - half.toInt()) < 1e-9) {
        val simplified = simplifyRadical(disc)
        if (simplified != null) {
            val (coef, rad) = simplified
            val denom = 2 * a
            if (abs(coef / denom - 1.0) < 1e-9 || abs(coef - denom) < 1e-6) {
                val k = coef / denom
                val kText = if (abs(k - 1.0) < 1e-9) "" else "${formatNumber(k)}"
                return "x = ${formatNumber(half)} ± ${kText}sqrt($rad)".replace("±  ", "± ")
            }
        }
    }
    return "x = ${formatNumber(half)} ± ${formatNumber(pm)}"
}

private fun simplifyRadical(disc: Double): Pair<Double, Int>? {
    val rounded = kotlin.math.round(disc).toInt()
    if (abs(disc - rounded) > 1e-6 || rounded < 0) return null
    var n = rounded
    var coef = 1
    var p = 2
    while (p * p <= n) {
        while (n % (p * p) == 0) {
            n /= p * p
            coef *= p
        }
        p++
    }
    return coef.toDouble() to n
}

fun polyToString(poly: Poly): String {
    val p = poly.trim()
    if (p.degree() < 0) return "0"
    val parts = ArrayList<String>()
    for (power in p.degree() downTo 0) {
        val c = p.coeff(power)
        if (abs(c) < 1e-9) continue
        val mag = abs(c)
        val coef = when {
            power > 0 && abs(mag - 1.0) < 1e-9 -> ""
            abs(mag - mag.toLong()) < 1e-6 -> mag.toLong().toString()
            else -> formatNumber(mag)
        }
        val term = when (power) {
            0 -> formatNumber(mag)
            1 -> "${coef}x"
            else -> "${coef}x^$power"
        }
        if (parts.isEmpty()) {
            parts.add(if (c < 0) "-$term" else term)
        } else {
            parts.add(if (c < 0) "- $term" else "+ $term")
        }
    }
    return parts.joinToString(" ").ifBlank { "0" }
}

data class Solved(
    val steps: List<String>,
    val finalAnswer: String,
    val problemType: String,
)

fun solveProblem(raw: String): Solved? = when (val problem = classifyProblem(raw)) {
    is Problem.SolveEq -> solveEquation(problem)
    is Problem.Derivative -> solveDerivative(problem)
    is Problem.Integral -> solveIntegral(problem)
    is Problem.Limit -> solveLimit(problem)
    is Problem.Unsupported -> null
}

private fun solveEquation(problem: Problem.SolveEq): Solved? {
    val diff = toPoly(Expr.Sub(problem.equation.left, problem.equation.right)) ?: return null
    val rs = roots(diff) ?: return null
    val steps = ArrayList<String>()
    steps.add("Start from the equation and bring every term to one side.")
    when (diff.degree()) {
        1 -> {
            val a = diff.coeff(1)
            val b = diff.coeff(0)
            steps.add("This is linear: ${formatNumber(a)}x + ${formatNumber(b)} = 0.")
            steps.add("Subtract the constant and divide by the coefficient of x.")
            steps.add(formatRoots(rs))
        }
        2 -> {
            val a = diff.coeff(2)
            val b = diff.coeff(1)
            val c = diff.coeff(0)
            val disc = b * b - 4 * a * c
            steps.add("Quadratic with a = ${formatNumber(a)}, b = ${formatNumber(b)}, c = ${formatNumber(c)}.")
            steps.add("Discriminant b² − 4ac = ${formatNumber(disc)}.")
            steps.add(prettyQuadratic(a, b, c))
        }
        else -> return null
    }
    return Solved(steps, formatRoots(rs), if (diff.degree() == 1) "linear" else "quadratic")
}

private fun solveDerivative(problem: Problem.Derivative): Solved? {
    val poly = toPoly(problem.expr, problem.variable) ?: return null
    val d = poly.derivative()
    val steps = listOf(
        "Differentiate term by term.",
        "d/dx (${polyToString(poly)}) = ${polyToString(d)}.",
    )
    return Solved(steps, polyToString(d), "derivative")
}

private fun solveIntegral(problem: Problem.Integral): Solved? {
    val poly = toPoly(problem.expr, problem.variable) ?: return null
    val anti = poly.integral()
    if (problem.lower != null && problem.upper != null) {
        val lo = evalNumeric(problem.lower) ?: return null
        val hi = evalNumeric(problem.upper) ?: return null
        val value = anti.eval(hi) - anti.eval(lo)
        return Solved(
            listOf(
                "Antiderivative is ${polyToString(anti)}.",
                "Evaluate from ${formatNumber(lo)} to ${formatNumber(hi)}.",
                formatNumber(value),
            ),
            formatNumber(value),
            "definite-integral",
        )
    }
    val answer = "${polyToString(anti)} + C"
    return Solved(
        listOf("Increase each power by 1 and divide by the new power.", answer),
        answer,
        "integral",
    )
}

private fun solveLimit(problem: Problem.Limit): Solved? {
    val value = limitValue(problem) ?: return null
    return Solved(
        listOf(
            "Simplify the expression, then substitute x = ${formatNumber(problem.approaching)}.",
            formatNumber(value),
        ),
        formatNumber(value),
        "limit",
    )
}

fun limitValue(problem: Problem.Limit): Double? {
    val rational = toRational(problem.expr, problem.variable) ?: return null
    var num = rational.num
    var den = rational.den
    val a = problem.approaching
    if (abs(den.eval(a)) > 1e-8) {
        return num.eval(a) / den.eval(a)
    }
    if (abs(num.eval(a)) > 1e-6) return null
    var guard = 0
    while (abs(den.eval(a)) <= 1e-8 && abs(num.eval(a)) <= 1e-6 && guard < 6) {
        val n2 = divideByLinear(num, a) ?: return null
        val d2 = divideByLinear(den, a) ?: return null
        num = n2
        den = d2
        guard++
    }
    if (abs(den.eval(a)) <= 1e-8) return null
    return num.eval(a) / den.eval(a)
}

data class StepAssessment(
    val stepIndex: Int,
    val text: String,
    val verdict: String,
    val note: String,
)

data class FirstError(
    val stepIndex: Int,
    val explanation: String,
)

data class LocalCheck(
    val status: String,
    val confidence: Double,
    val problemType: String,
    val recognizedQuestion: String,
    val steps: List<StepAssessment>,
    val firstError: FirstError?,
    val explanation: String,
    val hints: List<String>,
    val finalAnswer: String?,
    val fullSolution: List<String>,
)

fun checkWork(question: String, solutionSteps: List<String>): LocalCheck? {
    val problem = classifyProblem(question)
    if (problem is Problem.Unsupported) return null
    val solved = solveProblem(question)
    return when (problem) {
        is Problem.SolveEq -> checkEquation(question, problem, solutionSteps, solved)
        is Problem.Derivative -> checkExpressionProblem(question, problem, solutionSteps, solved, "derivative")
        is Problem.Integral -> checkExpressionProblem(question, problem, solutionSteps, solved, "integral")
        is Problem.Limit -> checkExpressionProblem(question, problem, solutionSteps, solved, "limit")
        is Problem.Unsupported -> null
    }
}

private fun checkEquation(
    question: String,
    problem: Problem.SolveEq,
    steps: List<String>,
    solved: Solved?,
): LocalCheck {
    val diff = toPoly(Expr.Sub(problem.equation.left, problem.equation.right))
    val expected = diff?.let { roots(it) } ?: emptyList()
    val hints = equationHints(diff)
    if (steps.isEmpty()) {
        return LocalCheck(
            status = "incomplete",
            confidence = 0.66,
            problemType = if (diff?.degree() == 2) "quadratic" else "linear",
            recognizedQuestion = question,
            steps = emptyList(),
            firstError = null,
            explanation = "There is no solution work to check yet.",
            hints = hints,
            finalAnswer = solved?.finalAnswer,
            fullSolution = solved?.steps ?: emptyList(),
        )
    }
    val assessments = ArrayList<StepAssessment>()
    var firstBad: Int? = null
    var sawAnswer = false
    var unclear = 0
    for ((index, line) in steps.withIndex()) {
        if (firstBad != null) {
            assessments.add(StepAssessment(index, line, "depends_on_error", "This step follows an earlier mistake, so it is not marked correct."))
            continue
        }
        val interpreted = interpretEquationStep(line, expected)
        when (interpreted) {
            StepKind.EQUIVALENT -> {
                sawAnswer = true
                assessments.add(StepAssessment(index, line, "correct", "Same solution set as the original equation."))
            }
            StepKind.PARTIAL -> {
                assessments.add(StepAssessment(index, line, "correct", "This value is a root, but not the full solution set."))
            }
            StepKind.INCORRECT -> {
                firstBad = index
                assessments.add(StepAssessment(index, line, "incorrect", "This step changes the solution set."))
            }
            StepKind.COMMENTARY -> assessments.add(StepAssessment(index, line, "commentary", "Ignored as a label."))
            StepKind.UNCLEAR -> {
                unclear++
                assessments.add(StepAssessment(index, line, "unclear", "Could not read this step as an equation."))
            }
        }
    }
    val finalValues = steps.asReversed().firstNotNullOfOrNull { valuesFromAnswer(it) }
    val finalMatches = finalValues != null && setsMatch(expected, finalValues, 1e-3)
    val status = when {
        firstBad != null -> "incorrect"
        unclear == steps.size -> "unclear"
        finalMatches || (sawAnswer && expected.size <= 1) -> "correct"
        finalValues != null && isSubset(finalValues, expected, 1e-3) && finalValues.size < expected.size -> "incomplete"
        sawAnswer && finalValues == null -> "incomplete"
        else -> if (unclear > 0) "unclear" else "incomplete"
    }
    val explanation = when (status) {
        "incorrect" -> {
            val line = steps[firstBad ?: 0]
            "The first incorrect step is \"${line.trim()}\". It does not keep the same solutions as the original equation. Later steps are not marked correct, because they depend on that error."
        }
        "correct" -> "The work stays equivalent to the original equation and reaches the right solution."
        "incomplete" -> "No incorrect step was found, but the solution is not finished."
        else -> "The working could not be read clearly enough to mark it. Edit the interpretation and check again."
    }
    return LocalCheck(
        status = status,
        confidence = if (status == "unclear") 0.35 else 0.93,
        problemType = if (diff?.degree() == 2) "quadratic" else "linear",
        recognizedQuestion = question,
        steps = assessments,
        firstError = firstBad?.let { FirstError(it, explanation) },
        explanation = explanation,
        hints = hints,
        finalAnswer = solved?.finalAnswer,
        fullSolution = solved?.steps ?: emptyList(),
    )
}

private enum class StepKind { EQUIVALENT, PARTIAL, INCORRECT, COMMENTARY, UNCLEAR }

private fun interpretEquationStep(line: String, expected: List<Double>): StepKind {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return StepKind.COMMENTARY
    val normalized = normalizeMath(trimmed)
    if (!looksLikeMath(normalized)) return StepKind.COMMENTARY
    if ("±" in trimmed || "±" in normalized) {
        val values = valuesFromAnswer(trimmed) ?: return StepKind.UNCLEAR
        return when {
            setsMatch(expected, values, 2e-2) -> StepKind.EQUIVALENT
            isSubset(values, expected, 2e-2) -> StepKind.PARTIAL
            else -> StepKind.INCORRECT
        }
    }
    val eq = parseEquation(normalized)
    if (eq != null) {
        val poly = toPoly(Expr.Sub(eq.left, eq.right))
        if (poly != null && poly.degree() in 0..2) {
            val rs = roots(poly)
            if (rs == null) return StepKind.UNCLEAR
            return when {
                setsMatch(expected, rs, 1e-4) -> StepKind.EQUIVALENT
                rs.isNotEmpty() && isSubset(rs, expected, 1e-3) -> StepKind.PARTIAL
                else -> StepKind.INCORRECT
            }
        }
        val values = valuesFromAnswer(trimmed)
        if (values != null) {
            return when {
                setsMatch(expected, values, 2e-2) -> StepKind.EQUIVALENT
                isSubset(values, expected, 2e-2) -> StepKind.PARTIAL
                else -> StepKind.INCORRECT
            }
        }
        return StepKind.UNCLEAR
    }
    val values = valuesFromAnswer(trimmed)
    if (values != null) {
        return when {
            setsMatch(expected, values, 2e-2) -> StepKind.EQUIVALENT
            isSubset(values, expected, 2e-2) -> StepKind.PARTIAL
            else -> StepKind.INCORRECT
        }
    }
    return if (looksLikeMath(normalized)) StepKind.UNCLEAR else StepKind.COMMENTARY
}

private fun looksLikeMath(s: String): Boolean =
    s.any { it.isDigit() || it == '=' || it == 'x' || it == '+' || it == '-' || it == '^' }

private fun equationHints(diff: Poly?): List<String> {
    if (diff == null) return listOf("Rewrite the question as an equation in x.")
    return if (diff.degree() == 2) {
        listOf(
            "Move every term to one side so the equation equals zero.",
            "Identify a, b, and c, then compute the discriminant b² − 4ac.",
            "Use the quadratic formula. Keep both the plus and minus roots.",
        )
    } else {
        listOf(
            "Isolate the term that contains x.",
            "Add or subtract the constant on both sides. Check that arithmetic before dividing.",
            "Divide both sides by the coefficient of x.",
        )
    }
}

private fun checkExpressionProblem(
    question: String,
    problem: Problem,
    steps: List<String>,
    solved: Solved?,
    type: String,
): LocalCheck {
    val expectedPoly = expectedPoly(problem)
    val expectedNumber = expectedNumber(problem)
    val hints = when (type) {
        "derivative" -> listOf(
            "Differentiate one term at a time.",
            "The derivative of x^n is n·x^(n−1).",
            "Add the term derivatives. Do not include + C.",
        )
        "integral" -> listOf(
            "The integral of x^n is x^(n+1)/(n+1), for n ≠ −1.",
            "Remember the constant + C for an indefinite integral.",
            "Check by differentiating your answer.",
        )
        else -> listOf(
            "Substitute the value x approaches if the expression is defined there.",
            "If you get 0/0, cancel a common factor first.",
            "After simplifying, substitute again.",
        )
    }
    if (steps.isEmpty()) {
        return LocalCheck(
            "incomplete", 0.6, type, question, emptyList(), null,
            "There is no solution work to check yet.", hints, solved?.finalAnswer, solved?.steps ?: emptyList(),
        )
    }
    val assessments = ArrayList<StepAssessment>()
    var firstBad: Int? = null
    var matched = false
    for ((index, line) in steps.withIndex()) {
        if (firstBad != null) {
            assessments.add(StepAssessment(index, line, "depends_on_error", "Depends on an earlier mistake."))
            continue
        }
        val kind = interpretExpressionStep(line, problem, expectedPoly, expectedNumber)
        when (kind) {
            StepKind.EQUIVALENT -> {
                matched = true
                assessments.add(StepAssessment(index, line, "correct", "Matches the expected result."))
            }
            StepKind.PARTIAL -> assessments.add(StepAssessment(index, line, "correct", "Matches the original expression or a valid intermediate."))
            StepKind.INCORRECT -> {
                firstBad = index
                assessments.add(StepAssessment(index, line, "incorrect", "Does not match the expected mathematics."))
            }
            StepKind.COMMENTARY -> assessments.add(StepAssessment(index, line, "commentary", "Label."))
            StepKind.UNCLEAR -> assessments.add(StepAssessment(index, line, "unclear", "Could not parse this step."))
        }
    }
    val status = when {
        firstBad != null -> "incorrect"
        matched -> "correct"
        assessments.all { it.verdict == "unclear" || it.verdict == "commentary" } -> "unclear"
        else -> "incomplete"
    }
    val explanation = when (status) {
        "incorrect" -> "The first incorrect step is \"${steps[firstBad ?: 0].trim()}\". Later lines are not treated as independently correct."
        "correct" -> "The final result matches an on-device ${type} check."
        "incomplete" -> "The expected result was not reached."
        else -> "The working is too unclear to check. Edit the interpretation and try again."
    }
    return LocalCheck(
        status = status,
        confidence = if (status == "unclear") 0.34 else 0.9,
        problemType = type,
        recognizedQuestion = question,
        steps = assessments,
        firstError = firstBad?.let { FirstError(it, explanation) },
        explanation = explanation,
        hints = hints,
        finalAnswer = solved?.finalAnswer,
        fullSolution = solved?.steps ?: emptyList(),
    )
}

private fun expectedPoly(problem: Problem): Poly? = when (problem) {
    is Problem.Derivative -> toPoly(problem.expr)?.derivative()
    is Problem.Integral -> if (problem.lower == null) toPoly(problem.expr)?.integral() else null
    else -> null
}

private fun expectedNumber(problem: Problem): Double? = when (problem) {
    is Problem.Limit -> limitValue(problem)
    is Problem.Integral -> {
        if (problem.lower == null || problem.upper == null) null
        else {
            val anti = toPoly(problem.expr)?.integral() ?: return null
            val lo = evalNumeric(problem.lower) ?: return null
            val hi = evalNumeric(problem.upper) ?: return null
            anti.eval(hi) - anti.eval(lo)
        }
    }
    else -> null
}

private fun interpretExpressionStep(
    line: String,
    problem: Problem,
    expectedPoly: Poly?,
    expectedNumber: Double?,
): StepKind {
    val n = normalizeMath(line)
    if (!looksLikeMath(n) && n.length < 24) return StepKind.COMMENTARY
    val body = splitEquation(n)?.second ?: n.removeSuffix("+c")
    val cleaned = body.removeSuffix("+c")
    val expr = tryParseExpr(cleaned)
    if (expr != null && expectedPoly != null) {
        val poly = toPoly(expr)
        if (poly != null && poly.nearlyEquals(expectedPoly)) return StepKind.EQUIVALENT
        val original = when (problem) {
            is Problem.Derivative -> toPoly(problem.expr)
            is Problem.Integral -> toPoly(problem.expr)
            else -> null
        }
        if (poly != null && original != null && poly.nearlyEquals(original)) return StepKind.PARTIAL
        if (poly != null) return StepKind.INCORRECT
    }
    if (expectedNumber != null) {
        val value = evalAnswer(line) ?: expr?.let { evalNumeric(it) }
        if (value != null) {
            return if (abs(value - expectedNumber) <= 2e-2) StepKind.EQUIVALENT else StepKind.INCORRECT
        }
    }
    if (expectedPoly != null) {
        val valueExpr = tryParseExpr(cleaned)
        val poly = valueExpr?.let { toPoly(it) }
        if (poly != null && !poly.nearlyEquals(expectedPoly)) return StepKind.INCORRECT
    }
    return StepKind.UNCLEAR
}

fun localCheckSchemaNote(): Int = ANALYSIS_SCHEMA_VERSION
