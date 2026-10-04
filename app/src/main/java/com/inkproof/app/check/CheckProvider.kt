package com.inkproof.app.check

import com.inkproof.app.model.CheckRequest
import com.inkproof.app.model.CheckResponse

/**
 * Replaceable provider for CHECK MY WORK / SOLVE.
 *
 * Implementations:
 *  - [BackendCheckProvider]: calls the InkProof backend (which protects API
 *    keys, calls the AI + symbolic math engine, and returns validated JSON).
 *  - [MockCheckProvider]: deterministic offline provider for development.
 */
interface CheckProvider {
    val name: String
    suspend fun check(request: CheckRequest): CheckResponse
    suspend fun solve(request: CheckRequest): CheckResponse
}

/**
 * Replaceable symbolic math verification abstraction.
 * The production implementation lives on the backend (e.g. WolframProvider);
 * the client only ever sees its conclusions inside the structured response.
 */
interface MathVerificationProvider {
    val name: String
    suspend fun isEquivalent(expressionA: String, expressionB: String): Boolean?
}
