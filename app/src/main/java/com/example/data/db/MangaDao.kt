package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.SavedPage
import com.example.data.model.SavedVocab
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaDao {
    // Saved Pages
    @Query("SELECT * FROM saved_manga_pages ORDER BY timestamp DESC")
    fun getAllSavedPages(): Flow<List<SavedPage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedPage(page: SavedPage): Long

    @Delete
    suspend fun deleteSavedPage(page: SavedPage)

    @Query("DELETE FROM saved_manga_pages WHERE id = :id")
    suspend fun deleteSavedPageById(id: Long)

    // Saved Vocabulary
    @Query("SELECT * FROM saved_manga_vocab ORDER BY timestamp DESC")
    fun getAllVocab(): Flow<List<SavedVocab>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVocab(vocab: SavedVocab): Long

    @Update
    suspend fun updateVocab(vocab: SavedVocab)

    @Delete
    suspend fun deleteVocab(vocab: SavedVocab)

    @Query("UPDATE saved_manga_vocab SET isMastered = :mastered WHERE id = :id")
    suspend fun setVocabMastered(id: Long, mastered: Boolean)
}
