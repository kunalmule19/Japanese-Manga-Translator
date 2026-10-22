package com.example.data.api

import android.graphics.Bitmap
import android.util.Base64
import com.example.BuildConfig
import com.example.data.model.MangaBubble
import com.example.data.model.MangaTranslationResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object GeminiMangaTranslator {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val resultAdapter = moshi.adapter(MangaTranslationResult::class.java)

    /**
     * Converts a Bitmap to base64 JPEG string
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 85): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Calls Gemini 3.5 Flash vision model to detect speech bubbles, OCR Japanese, and translate to English
     */
    suspend fun translateMangaPanel(
        bitmap: Bitmap,
        apiKey: String = BuildConfig.GEMINI_API_KEY,
        preferredTone: String = "Natural Manga"
    ): Result<MangaTranslationResult> = withContext(Dispatchers.IO) {
        val effectiveKey = apiKey.trim()
        if (effectiveKey.isEmpty() || effectiveKey == "MY_GEMINI_API_KEY") {
            return@withContext Result.failure(
                IllegalStateException("Gemini API key is not configured. Please add your key in the Secrets panel or Settings.")
            )
        }

        try {
            val base64Data = bitmapToBase64(bitmap)

            val prompt = """
                You are a master Japanese manga translator, scanlator, and OCR engine.
                Analyze this manga image panel or page.

                Manga Translation Rules:
                1. Reading Order: Japanese manga reads from RIGHT to LEFT, and TOP to BOTTOM. Group speech bubbles according to this chronological flow.
                2. Vertical & Stylized Text: Detect vertical text columns correctly. Distinguish between speech bubbles, narration caption boxes, shout bubbles, and background SFX (onomatopoeia).
                3. Furigana Handling: Recognize kanji and ignore small ruby furigana duplication.
                4. Translation Quality: Translate into punchy, emotional, natural English dialogue suitable for manga publication in $preferredTone tone.
                5. Coordinates: For each detected bubble or text region, provide bounding box in normalized [ymin, xmin, ymax, xmax] coordinates from 0 to 1000.
                6. Educational Breakdown: For each bubble, extract Romaji pronunciation, literal meaning, and key vocabulary words with JLPT level.

                Output ONLY raw JSON with no markdown wrapping matching this exact schema:
                {
                  "scene_summary": "Brief 1-sentence description of the manga panel action or dialogue scene",
                  "reading_order_direction": "RTL",
                  "overall_tone": "$preferredTone",
                  "bubbles": [
                    {
                      "id": "b1",
                      "box_2d": [ymin, xmin, ymax, xmax],
                      "japanese": "Original Japanese text with kanji/kana",
                      "romaji": "Romaji reading",
                      "english": "Natural English translation",
                      "literal": "Literal word-by-word meaning",
                      "type": "bubble | narration | sfx | shout",
                      "notes": "Tone or nuance explanation",
                      "vocabulary": [
                        {
                          "word": "Kanji/Kana word",
                          "reading": "hiragana reading (romaji)",
                          "meaning": "English definition",
                          "jlptLevel": "N5/N4/N3/N2/N1/SFX"
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()

            val request = GenerateContentRequest(
                contents = listOf(
                    Content(
                        parts = listOf(
                            Part(text = prompt),
                            Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Data))
                        )
                    )
                ),
                generationConfig = GenerationConfig(
                    temperature = 0.2f,
                    responseMimeType = "application/json"
                ),
                systemInstruction = Content(
                    parts = listOf(
                        Part(text = "You are an AI manga translator specialist for Japanese to English scanlation. Always respond in valid JSON.")
                    )
                )
            )

            val response = RetrofitClient.geminiService.generateContent(effectiveKey, request)
            val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: return@withContext Result.failure(Exception("Empty response received from Gemini API"))

            val cleanJson = cleanJsonResponse(rawText)
            val result = resultAdapter.fromJson(cleanJson)
                ?: return@withContext Result.failure(Exception("Failed to parse manga translation JSON"))

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Cleans possible markdown backticks or extra formatting around JSON
     */
    private fun cleanJsonResponse(raw: String): String {
        var trimmed = raw.trim()
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.removePrefix("```json").trim()
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.removePrefix("```").trim()
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.removeSuffix("```").trim()
        }
        return trimmed
    }
}
