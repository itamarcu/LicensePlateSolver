package com.shemetz.licenseplatesolver

import android.util.Log

typealias Num = Double

fun Int.toNum(): Num {
    return this.toDouble()
}

object ProblemSolver {
    private const val TAG = "ProblemSolver"
    /**
     * Radix base of the computation - normal humans use base ten (9+1)
     */
    var radixBase = 10
    /**
     * Allow "1 2 3" -> "1+23"
     */
    var allowDigitConcatenation = false
    /**
     * Allow "2 1" -> "2^(-1)"
     */
    var allowNegative = false
    /**
     * Will stop calculating paths with numbers too large or too small
     */
    var doNotCheckWeirdSolutions = true
    /**
     * Target number. If you change this, change the hardcoded constants near the "weird solutions" result removal
     */
    var targetNumber: Num = 100.0
    private var cacheHits = 0
    private var cacheMisses = 0

    enum class Stats {
        ALL, NonFinite, Suspicious, OutOfRange, PreExisting, NotDropped
    }

    private var calculatedResults = mutableMapOf<Stats, Int>()

    enum class BinaryOperator(
            val operationOrder: Int,
            val infix: String,
            val operatorFunction: (left: Num, right: Num) -> Num
    ) {
        //Order of enums matters here - app will prefer top-most operators
        ADDITION(1, "+", { left, right -> left + right }),
        SUBTRACTION(1, "-", { left, right -> left - right }),
        MULTIPLICATION(3, "*", { left, right -> left * right }), // ↓
        DIVISION(2, "/", { left, right -> left / right }), // division is 2 and multiplication is 3 on purpose
        POWER(4, "^", { left, right -> Math.pow(left, right) }),
    }

    data class Equation(val equation: String, val operationOrder: Int, val parenthesesDepth: Int)

    private val cachedEquationPossibilities = mutableMapOf<String, Map<Num, Equation>>()

    fun invalidateCache() {
        Log.i(TAG, "Invalidating Cache")
        cacheHits = 0
        cacheMisses = 0
        cachedEquationPossibilities.clear()
    }

    private fun decrementStat(stat: Stats) {
        calculatedResults[stat] = calculatedResults[stat]!! - 1
    }

    private fun incrementStat(stat: Stats) {
        calculatedResults[stat] = calculatedResults[stat]!! + 1
    }

    private fun fractionalPartOfStat(stat: Stats): String {
        if (calculatedResults[Stats.ALL]!! == 0) return ""  // Cache already had the answer
        return "%4.1f%%".format(100 * calculatedResults[stat]!!.toNum() / calculatedResults[Stats.ALL]!!)
    }

    fun solveNumberString(numberString: String): String? {
        for (key in Stats.values())
            calculatedResults[key] = 0
        val possibleEquations = recursivelyGetAllEquationsFrom(numberString, 0)
        Log.d(TAG, "SOLUTION DONE." +
                "\nCache size ${cachedEquationPossibilities.size}" +
                "\nCache hits: $cacheHits/${cacheMisses + cacheHits}" +
                "=${cacheHits.toNum() / (cacheMisses + cacheHits)}" +
                "\nTOTAL calculated results:     ${calculatedResults[Stats.ALL]}" +
                "\nNon-dropped results (stored): ${calculatedResults[Stats.NotDropped]}" +
                "\nDrop rates optimizations:" +
                "\n    non finite:   ${fractionalPartOfStat(Stats.NonFinite)}" +
                "\n    suspicious:   ${fractionalPartOfStat(Stats.Suspicious)}" +
                "\n    out of range: ${fractionalPartOfStat(Stats.OutOfRange)}" +
                "\n    pre-existing: ${fractionalPartOfStat(Stats.PreExisting)}" +
                "\n    not dropped:  ${fractionalPartOfStat(Stats.NotDropped)}")
        for (possibleEquation in possibleEquations) {
//            Log.d(TAG, "${possibleEquation.value.equation} = ${possibleEquation.key}")
            if (possibleEquation.key == targetNumber)
                return possibleEquation.value.equation
        }

        Log.d(TAG, "No solution found that reaches $targetNumber :(")
        // Failed to find any solution - returning null to signal this
        return null
    }

    private fun recursivelyGetAllEquationsFrom(numberString: String, depth: Int): Map<Num, Equation> {
        if (numberString in cachedEquationPossibilities) {
            cacheHits++
            return cachedEquationPossibilities[numberString]!!
        }
        cacheMisses++
        val possibilities = mutableMapOf<Num, Equation>()
        // "1234" -> [1234]
        if (numberString.length == 1 || allowDigitConcatenation) {
            val concatenatedValue = numberString.toInt(radixBase).toNum()
            possibilities[+concatenatedValue] = Equation(numberString, 0, 0) // 0 = never add parentheses
            if (allowNegative)
                possibilities[-concatenatedValue] = Equation("(-$numberString)", 0, 1)
        }
        for (index in 1 until numberString.length) {
            val left = numberString.substring(0, index)
            val right = numberString.substring(index)
            val leftPossibilities = recursivelyGetAllEquationsFrom(left, depth + numberString.length - index)
            val rightPossibilities = recursivelyGetAllEquationsFrom(right, depth + index)
            for (leftEquation in leftPossibilities)
                for (rightEquation in rightPossibilities)
                    loop@ for (operator in BinaryOperator.values()) {
                        incrementStat(Stats.ALL)
                        val result = operator.operatorFunction(leftEquation.key, rightEquation.key)
                        if (!result.isFinite()) {
                            incrementStat(Stats.NonFinite)
                            continue
                        }
                        val absoluteResult = Math.abs(result)
                        incrementStat(Stats.Suspicious)
                        when (depth) {
                            0 -> if (absoluteResult % 1.0 != 0.0) continue@loop
                            1 -> if ((absoluteResult * (1 * 2 * 3 * 2 * 5 * 7 * 2 * 3)) % 1.0 != 0.0) continue@loop // REMOVE IF POWERS COULD FIT NICELY
                        }
                        decrementStat(Stats.Suspicious)
                        if (doNotCheckWeirdSolutions) {
                            incrementStat(Stats.OutOfRange)
                            if (absoluteResult > 1_000_000 || absoluteResult < 0.00_000_1) // this is my syntax and I like it
                                continue
                            when {
                                depth == 0 && absoluteResult != targetNumber -> continue@loop
                                depth == 1 && absoluteResult > targetNumber * radixBase || absoluteResult < targetNumber / radixBase ->
                                    continue@loop // REMOVE THIS IF TARGET IS NEGATIVE AND FOR POWER STUFF
                            }
                            decrementStat(Stats.OutOfRange)
                        }
                        val leftEquationWrapped = prettifyEquationNearOperator(leftEquation.value, operator, false)
                        val rightEquationWrapped = prettifyEquationNearOperator(rightEquation.value, operator, true)
                        val equationString = leftEquationWrapped.equation + operator.infix + rightEquationWrapped.equation
                        val newParenthesesDepth = Math.max(leftEquationWrapped.parenthesesDepth, rightEquationWrapped.parenthesesDepth)
                        incrementStat(Stats.PreExisting)
                        if (result in possibilities && possibilities[result]!!.parenthesesDepth <= newParenthesesDepth)
                            continue
                        decrementStat(Stats.PreExisting)
                        incrementStat(Stats.NotDropped)
                        possibilities[result] = Equation(equationString, operator.operationOrder, newParenthesesDepth)
                    }
        }
        if (depth <= 2) // don't want to log too much
            Log.d(TAG, "$depth - analyzed $numberString    (${possibilities.size} results)")
        cachedEquationPossibilities[numberString] = possibilities
        return cachedEquationPossibilities[numberString]!!
    }

    /**
     * (1+2)*(3) = (1+2)*3
     * (12) *(3) = 12*3
     * (1^2)*(3) = 1^2*3
     * (1*2)*(3) = 1*2*3
     * (1/2)*(3) = (1/2)*3
     * (1-2)-(3-4) = 1-2-(3-4)
     * (1-2)-(3+4) = 1-2-(3+4)
     * (1/2)/(3/4) = (1/2)/(3/4)
     * (1*2)/(3*4) = 1*2/(3*4)
     * (1^2)^(3^4) = (1^2)^(3^4)
     * (12)^(34) = 12^34
     */
    private fun prettifyEquationNearOperator(equation: Equation, operator: BinaryOperator, isRightSide: Boolean): Equation {
        val addParentheses = when {
            equation.operationOrder == 0 -> false
            operator == BinaryOperator.POWER -> true
            operator == BinaryOperator.DIVISION && equation.operationOrder == BinaryOperator.MULTIPLICATION.operationOrder -> isRightSide
            operator == BinaryOperator.SUBTRACTION && equation.operationOrder == BinaryOperator.ADDITION.operationOrder -> isRightSide
            equation.operationOrder > operator.operationOrder -> false
            operator == BinaryOperator.DIVISION -> true
            equation.operationOrder == operator.operationOrder -> false
            else -> true
        }

        return if (addParentheses) {
            Equation("(${equation.equation})", operator.operationOrder, equation.parenthesesDepth + 1)

        } else {
            Equation(equation.equation, operator.operationOrder, equation.parenthesesDepth)
        }
    }
}
