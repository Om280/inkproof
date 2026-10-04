package com.inkproof.app.data.repo

import com.inkproof.app.data.db.CheckResultEntity
import com.inkproof.app.data.db.InkProofDatabase
import com.inkproof.app.model.CheckAction
import com.inkproof.app.model.CheckResponse
import com.inkproof.app.model.newId
import kotlinx.serialization.json.Json

/**
 * Caches CHECK/SOLVE results keyed by
 * (questionId, contentVersion, solutionVersion, action).
 *
 * Editing the solution bumps solutionVersion, which automatically invalidates
 * older cached results — they simply stop matching the key.
 */
class CheckRepository(private val db: InkProofDatabase) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun cached(
        questionId: String,
        contentVersion: Long,
        solutionVersion: Long,
        action: CheckAction
    ): CheckResponse? {
        val row = db.checkResultDao().find(questionId, contentVersion, solutionVersion, action.name)
            ?: return null
        return runCatching {
            json.decodeFromString(CheckResponse.serializer(), row.resultJson)
        }.getOrNull()
    }

    suspend fun store(
        questionId: String,
        contentVersion: Long,
        solutionVersion: Long,
        action: CheckAction,
        response: CheckResponse
    ) {
        db.checkResultDao().upsert(
            CheckResultEntity(
                id = newId(),
                questionId = questionId,
                contentVersion = contentVersion,
                solutionVersion = solutionVersion,
                action = action.name,
                resultJson = json.encodeToString(CheckResponse.serializer(), response),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun history(questionId: String): List<CheckResponse> =
        db.checkResultDao().historyFor(questionId).mapNotNull {
            runCatching {
                json.decodeFromString(CheckResponse.serializer(), it.resultJson)
            }.getOrNull()
        }

    suspend fun clearAll() = db.checkResultDao().clearAll()
}
