package com.inkproof.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }

        /** v1 → v2: per-page paper color (default = white paper). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE pages ADD COLUMN paperColor INTEGER NOT NULL DEFAULT -197640"
                )
            }
        }

        /** In-memory database for tests. */
        fun inMemory(context: Context): InkProofDatabase =
            Room.inMemoryDatabaseBuilder(context, InkProofDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
