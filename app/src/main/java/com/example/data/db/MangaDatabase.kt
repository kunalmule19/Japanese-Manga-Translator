package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.SavedPage
import com.example.data.model.SavedVocab

@Database(entities = [SavedPage::class, SavedVocab::class], version = 1, exportSchema = false)
abstract class MangaDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao

    companion object {
        @Volatile
        private var INSTANCE: MangaDatabase? = null

        fun getDatabase(context: Context): MangaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MangaDatabase::class.java,
                    "manga_translator_db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
