package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

enum class BubbleType {
    BUBBLE,
    NARRATION,
    SFX,
    SHOUT
}

@JsonClass(generateAdapter = true)
data class MangaVocab(
    @param:Json(name = "word") val word: String = "",
    @param:Json(name = "reading") val reading: String = "",
    @param:Json(name = "meaning") val meaning: String = "",
    @param:Json(name = "jlptLevel") val jlptLevel: String = "N4"
)

@JsonClass(generateAdapter = true)
data class MangaBubble(
    @param:Json(name = "id") val id: String = "",
    // Normalized coordinates [ymin, xmin, ymax, xmax] in 0..1000
    @param:Json(name = "box_2d") val box2d: List<Int> = listOf(0, 0, 100, 100),
    @param:Json(name = "japanese") val japanese: String = "",
    @param:Json(name = "romaji") val romaji: String = "",
    @param:Json(name = "english") val english: String = "",
    @param:Json(name = "literal") val literal: String = "",
    @param:Json(name = "type") val type: String = "bubble", // "bubble", "narration", "sfx", "shout"
    @param:Json(name = "notes") val notes: String = "",
    @param:Json(name = "vocabulary") val vocabulary: List<MangaVocab> = emptyList()
) {
    val yMinFraction: Float get() = (box2d.getOrNull(0) ?: 0).coerceIn(0, 1000) / 1000f
    val xMinFraction: Float get() = (box2d.getOrNull(1) ?: 0).coerceIn(0, 1000) / 1000f
    val yMaxFraction: Float get() = (box2d.getOrNull(2) ?: 100).coerceIn(0, 1000) / 1000f
    val xMaxFraction: Float get() = (box2d.getOrNull(3) ?: 100).coerceIn(0, 1000) / 1000f

    val bubbleType: BubbleType
        get() = when (type.lowercase()) {
            "narration", "box" -> BubbleType.NARRATION
            "sfx", "sound", "onomatopoeia" -> BubbleType.SFX
            "shout", "action" -> BubbleType.SHOUT
            else -> BubbleType.BUBBLE
        }
}

@JsonClass(generateAdapter = true)
data class MangaTranslationResult(
    @param:Json(name = "scene_summary") val sceneSummary: String = "",
    @param:Json(name = "reading_order_direction") val readingOrder: String = "RTL",
    @param:Json(name = "bubbles") val bubbles: List<MangaBubble> = emptyList(),
    @param:Json(name = "overall_tone") val overallTone: String = "Dramatic Shonen"
)
