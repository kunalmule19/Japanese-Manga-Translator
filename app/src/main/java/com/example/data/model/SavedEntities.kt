package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_manga_pages")
data class SavedPage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val imagePath: String, // file path or drawable resource identifier
    val bubblesJson: String, // serialized List<MangaBubble>
    val sceneSummary: String,
    val bubbleCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_manga_vocab")
data class SavedVocab(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val reading: String,
    val meaning: String,
    val jlptLevel: String = "N4",
    val contextSentence: String = "",
    val isMastered: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
