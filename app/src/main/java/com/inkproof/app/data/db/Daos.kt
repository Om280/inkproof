package com.inkproof.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: FolderEntity)

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: String): FolderEntity?

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface NotebookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notebook: NotebookEntity)

    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE folderId = :folderId ORDER BY updatedAt DESC")
    fun observeInFolder(folderId: String): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun byId(id: String): NotebookEntity?

    @Query("UPDATE notebooks SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE notebooks SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE notebooks SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM notebooks WHERE title LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun search(query: String): List<NotebookEntity>
}

@Dao
interface PageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(page: PageEntity)

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY orderIndex")
    fun observePages(notebookId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY orderIndex")
    suspend fun pagesFor(notebookId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun byId(id: String): PageEntity?

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE pages SET orderIndex = :orderIndex WHERE id = :id")
    suspend fun setOrder(id: String, orderIndex: Int)

    @Query("UPDATE pages SET template = :template, updatedAt = :now WHERE id = :id")
    suspend fun setTemplate(id: String, template: String, now: Long)

    @Query("SELECT COUNT(*) FROM pages WHERE notebookId = :notebookId")
    suspend fun countFor(notebookId: String): Int
}

@Dao
interface QuestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(question: QuestionEntity)

    @Query("SELECT * FROM questions WHERE pageId = :pageId ORDER BY orderIndex")
    fun observeForPage(pageId: String): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions WHERE pageId = :pageId ORDER BY orderIndex")
    suspend fun forPage(pageId: String): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun byId(id: String): QuestionEntity?

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE questions SET solutionVersion = solutionVersion + 1 WHERE id = :id")
    suspend fun bumpSolutionVersion(id: String)

    @Query("UPDATE questions SET contentVersion = contentVersion + 1 WHERE id = :id")
    suspend fun bumpContentVersion(id: String)

    @Query("UPDATE questions SET typedText = :text, contentVersion = contentVersion + 1 WHERE id = :id")
    suspend fun updateTypedText(id: String, text: String)

    @Query("SELECT * FROM questions WHERE typedText LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<QuestionEntity>
}

@Dao
interface StrokeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stroke: StrokeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(strokes: List<StrokeEntity>)

    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY createdAt")
    suspend fun forPage(pageId: String): List<StrokeEntity>

    @Query("SELECT * FROM strokes WHERE questionId = :questionId AND role = :role ORDER BY createdAt")
    suspend fun forQuestionRole(questionId: String, role: String): List<StrokeEntity>

    @Query("DELETE FROM strokes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM strokes WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: String)

    @Query("SELECT COUNT(*) FROM strokes WHERE pageId = :pageId")
    suspend fun countForPage(pageId: String): Int
}

@Dao
interface TextObjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(obj: TextObjectEntity)

    @Query("SELECT * FROM text_objects WHERE pageId = :pageId")
    suspend fun forPage(pageId: String): List<TextObjectEntity>

    @Query("DELETE FROM text_objects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ImageObjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(obj: ImageObjectEntity)

    @Query("SELECT * FROM image_objects WHERE pageId = :pageId")
    suspend fun forPage(pageId: String): List<ImageObjectEntity>

    @Query("DELETE FROM image_objects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CheckResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(result: CheckResultEntity)

    @Query(
        "SELECT * FROM check_results WHERE questionId = :questionId AND contentVersion = :contentVersion " +
            "AND solutionVersion = :solutionVersion AND action = :action LIMIT 1"
    )
    suspend fun find(
        questionId: String,
        contentVersion: Long,
        solutionVersion: Long,
        action: String
    ): CheckResultEntity?

    @Query("SELECT * FROM check_results WHERE questionId = :questionId ORDER BY createdAt DESC")
    suspend fun historyFor(questionId: String): List<CheckResultEntity>

    @Query("DELETE FROM check_results WHERE questionId = :questionId")
    suspend fun clearFor(questionId: String)

    @Query("DELETE FROM check_results")
    suspend fun clearAll()
}

@Dao
interface MaintenanceDao {
    @Transaction
    @Query("DELETE FROM strokes WHERE pageId IN (SELECT id FROM pages WHERE notebookId = :notebookId)")
    suspend fun deleteStrokesForNotebook(notebookId: String)

    @Query("DELETE FROM questions WHERE pageId IN (SELECT id FROM pages WHERE notebookId = :notebookId)")
    suspend fun deleteQuestionsForNotebook(notebookId: String)

    @Query("DELETE FROM pages WHERE notebookId = :notebookId")
    suspend fun deletePagesForNotebook(notebookId: String)

    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteStrokesForPage(pageId: String)

    @Query("DELETE FROM questions WHERE pageId = :pageId")
    suspend fun deleteQuestionsForPage(pageId: String)
}
