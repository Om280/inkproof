package com.inkproof.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FolderEntity::class,
        NotebookEntity::class,
        PageEntity::class,
        QuestionEntity::class,
        StrokeEntity::class,
        TextObjectEntity::class,
        ImageObjectEntity::class,
        CheckResultEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class InkProofDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun questionDao(): QuestionDao
    abstract fun strokeDao(): StrokeDao
    abstract fun textObjectDao(): TextObjectDao
    abstract fun imageObjectDao(): ImageObjectDao
    abstract fun checkResultDao(): CheckResultDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        @Volatile
        private var instance: InkProofDatabase? = null

        fun get(context: Context): InkProofDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    InkProofDatabase::class.java,
                    "inkproof.db"
                ).build().also { instance = it }
            }

        /** In-memory database for tests. */
        fun inMemory(context: Context): InkProofDatabase =
            Room.inMemoryDatabaseBuilder(context, InkProofDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
