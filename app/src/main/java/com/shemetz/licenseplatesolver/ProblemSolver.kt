package com.shemetz.licenseplatesolver

import android.util.Log

object ProblemSolver {
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
    private var cacheHits = 0
    private var cacheMisses = 0

    enum class BinaryOperator(
            val operationOrder: Int,
            val infix: String,
            val operatorFunction: (left: Double, right: Double) -> Double
    ) {
        //Order of enums matters here - app will prefer top-most operators
        ADDITION(1, "+", { left, right -> left + right }),
        SUBTRACTION(1, "-", { left, right -> left - right }),
        MULTIPLICATION(3, "*", { left, right -> left * right }), // ↓
        DIVISION(2, "/", { left, right -> left / right }), // division is 2 and multiplication is 3 on purpose
        POWER(4, "^", { left, right -> Math.pow(left, right) }),
    }

    data class Equation(val equation: String, val operationOrder: Int)

    private val cachedEquationPossibilities = mutableMapOf<String, Map<Double, Equation>>()

    fun invalidateCache() {
        Log.i("Solver", "Invalidating Cache")
        cacheHits = 0
        cacheMisses = 0
        cachedEquationPossibilities.clear()
    }

    fun solveNumberString(numberString: String, targetNumber: Double): String? {
        val possibleEquations = recursivelyGetAllEquationsFrom(numberString)
        Log.d("Solver", "DONE." +
                " Possibility space size is ${possibleEquations.size}" +
                ", with final cache size ${cachedEquationPossibilities.size}" +
                ". Cache hits: $cacheHits/${cacheMisses + cacheHits}" +
                "=${cacheHits.toDouble() / (cacheMisses + cacheHits)}")
        for (possibleEquation in possibleEquations) {
            if (possibleEquation.key == targetNumber)
                return possibleEquation.value.equation
        }

        // Failed to find any solution - returning null to signal this
        return null
    }

    private fun recursivelyGetAllEquationsFrom(numberString: String): Map<Double, Equation> {
        if (numberString in cachedEquationPossibilities) {
            cacheHits++
            return cachedEquationPossibilities[numberString]!!
        }
        cacheMisses++
        Log.d("Solver", "> $numberString")
        val possibilities = mutableMapOf<Double, Equation>()
        // "1234" -> [1234]
        if (numberString.length == 1 || allowDigitConcatenation) {
            val concatenatedValue = numberString.toInt(radixBase).toDouble()
            possibilities[+concatenatedValue] = Equation(numberString, 0) // 0 = never add parentheses
            if (allowNegative)
                possibilities[-concatenatedValue] = Equation("(-$numberString)", 0)
        }
        for (index in 1 until numberString.length) {
            val left = numberString.substring(0, index)
            val right = numberString.substring(index)
            val leftPossibilities = recursivelyGetAllEquationsFrom(left)
            val rightPossibilities = recursivelyGetAllEquationsFrom(right)
            for (operator in BinaryOperator.values())
                for (leftEquation in leftPossibilities)
                    for (rightEquation in rightPossibilities) {
                        val result = operator.operatorFunction(leftEquation.key, rightEquation.key)
                        if (!result.isFinite())
                            continue
                        if (doNotCheckWeirdSolutions) {
                            val absoluteResult = Math.abs(result)
                            if (absoluteResult > 10_000 || absoluteResult < 0.0001)
                                continue
                        }
                        if (result in possibilities)
                            continue
                        val leftEquationString = prettifyEquationNearOperator(leftEquation.value, operator, false)
                        val rightEquationString = prettifyEquationNearOperator(rightEquation.value, operator, true)
                        val equation = leftEquationString + operator.infix + rightEquationString
                        possibilities[result] = Equation(equation, operator.operationOrder)
                    }
        }
        Log.d("Solver", "< $numberString    (${possibilities.size} results)")
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
    private fun prettifyEquationNearOperator(equation: Equation, operator: BinaryOperator, isRightSide: Boolean): String {
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

        return if (addParentheses) "(${equation.equation})" else equation.equation
    }
}
