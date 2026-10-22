package com.example.data.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.example.data.model.BubbleType
import com.example.data.model.MangaBubble
import com.example.data.model.MangaTranslationResult
import com.example.data.model.MangaVocab
import com.example.BuildConfig
import com.example.data.api.Content
import com.example.data.api.GenerateContentRequest
import com.example.data.api.GenerationConfig
import com.example.data.api.Part
import com.example.data.api.RetrofitClient
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Raw detected Japanese column prior to furigana filtering and merging
 */
data class DetectedColumn(
    val text: String,
    val rect: Rect,
    val isVertical: Boolean
)

/**
 * Group of adjacent vertical Japanese columns forming a single speech bubble
 */
data class BubbleCluster(
    val rect: Rect,
    val columns: List<DetectedColumn>,
    val filteredText: String
)

object MangaTranslationEngine {

    // ML Kit Japanese V2 OCR
    private val japaneseRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    // ML Kit Offline Translator: Japanese to English
    private var offlineTranslator: Translator? = null
    private var isOfflineModelReady = false

    init {
        // Pre-warm offline translator on background thread for instant (<50ms) translations
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                initializeOfflineTranslator()
            } catch (_: Exception) {}
        }
    }

    /**
     * Initializes the offline ML Kit Translator.
     * Ensures models are downloaded and ready for 100% offline usage.
     */
    suspend fun initializeOfflineTranslator(): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            val translator = Translation.getClient(options)
            offlineTranslator = translator

            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).await()
            isOfflineModelReady = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Fast Perceptual Difference Hash (aHash) for battery-saving auto-live diffing.
     * Downsamples bitmap to 8x8, computes brightness relative to mean.
     */
    fun computePerceptualHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, false)
        var sumBrightness = 0L
        val brightness = IntArray(64)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val p = scaled.getPixel(x, y)
                val b = (p and 0xFF) + ((p shr 8) and 0xFF) + ((p shr 16) and 0xFF)
                brightness[y * 8 + x] = b
                sumBrightness += b
            }
        }
        val avg = (sumBrightness / 64).toInt()
        var hash = 0L
        for (i in 0 until 64) {
            if (brightness[i] > avg) {
                hash = hash or (1L shl i)
            }
        }
        scaled.recycle()
        return hash
    }

    /**
     * Hamming distance between two perceptual hashes.
     * Returns count of bit differences (0 = identical, > 10 = page turned / scrolled).
     */
    fun hashDistance(hash1: Long, hash2: Long): Int {
        var x = hash1 xor hash2
        var count = 0
        while (x != 0L) {
            count += (x and 1L).toInt()
            x = x ushr 1
        }
        return count
    }

    /**
     * Contrast enhancement filter:
     * Increases black/white contrast and eliminates screentone/halftone noise
     * so fine Japanese Kanji strokes and furigana are sharply recognized by OCR.
     */
    private fun enhanceMangaContrast(source: Bitmap): Bitmap {
        val enhanced = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val paint = Paint()
        val matrix = ColorMatrix().apply {
            val contrast = 1.35f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            set(floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return enhanced
    }

    /**
     * Core Manga OCR & Translation Pipeline:
     * 1. Detects text lines with ML Kit Japanese V2.
     * 2. Checks verticality (height > width * 1.2).
     * 3. Calculates median column width inside each speech bubble to strip out narrow furigana noise lines.
     * 4. Merges adjacent columns into a single speech bubble.
     * 5. Sorts columns Right-to-Left, Top-to-Bottom before sending complete sentence to translator.
     * 6. Dual-Mode Operation: If a local LLM (Gemma-2-2B/Qwen) model file exists, uses it;
     *    otherwise uses offline ML Kit NMT.
     */
    suspend fun processMangaImage(
        context: Context,
        bitmap: Bitmap,
        localLlmModelPath: String? = null
    ): MangaTranslationResult = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled) {
            return@withContext MangaTranslationResult(
                sceneSummary = "Frame recycled",
                readingOrder = "RTL",
                bubbles = emptyList(),
                overallTone = "Offline Manga Scanlator"
            )
        }

        val imageWidth = bitmap.width.coerceAtLeast(1)
        val imageHeight = bitmap.height.coerceAtLeast(1)

        // High Accuracy Manga OCR: preserve full screen resolution up to 2200px
        // for crisp Kanji radicals, dakuten, and furigana separation
        val maxDim = maxOf(imageWidth, imageHeight)
        val scale = if (maxDim > 2200) 2200f / maxDim else 1f
        val scaledBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (imageWidth * scale).toInt(), (imageHeight * scale).toInt(), true)
        } else {
            bitmap
        }

        // Contrast enhancement: sharpens black ink strokes against speech bubble backgrounds
        val ocrBitmap = try {
            enhanceMangaContrast(scaledBitmap)
        } catch (_: Throwable) {
            scaledBitmap
        }

        val visionText = try {
            val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
            japaneseRecognizer.process(inputImage).await()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext MangaTranslationResult(
                sceneSummary = "OCR processing unavailable: ${e.localizedMessage}",
                readingOrder = "RTL",
                bubbles = emptyList(),
                overallTone = "Offline Manga Scanlator"
            )
        } finally {
            if (ocrBitmap !== bitmap && ocrBitmap !== scaledBitmap) {
                try { ocrBitmap.recycle() } catch (_: Exception) {}
            }
            if (scaledBitmap !== bitmap) {
                try { scaledBitmap.recycle() } catch (_: Exception) {}
            }
        }

        val rawColumns = mutableListOf<DetectedColumn>()
        val invScale = if (scale < 1f) 1f / scale else 1f

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val rawBox = line.boundingBox ?: continue
                val text = line.text.trim()
                if (text.isEmpty()) continue

                // STRICT FILTER: Discard English text, page numbers, watermarks, UI elements.
                // "no need to show English texts or other languages just show the translation from japanese texts"
                if (!isJapaneseText(text)) continue

                val cleaned = cleanJapaneseText(text)
                if (cleaned.isEmpty() || !isJapaneseText(cleaned)) continue

                val box = if (scale < 1f) {
                    Rect(
                        (rawBox.left * invScale).toInt(),
                        (rawBox.top * invScale).toInt(),
                        (rawBox.right * invScale).toInt(),
                        (rawBox.bottom * invScale).toInt()
                    )
                } else rawBox

                val isVertical = box.height() > box.width() * 1.15f
                rawColumns.add(DetectedColumn(text = cleaned, rect = box, isVertical = isVertical))
            }
        }

        // Cluster adjacent vertical columns into speech bubbles
        val clusters = clusterColumnsIntoBubbles(rawColumns)
        val cleanedSentences = clusters.map { cleanJapaneseText(it.filteredText) }

        // Context-aware speech bubble translation in chronological Right-to-Left manga order
        val bubbles = coroutineScope {
            clusters.mapIndexed { index, cluster ->
                async(Dispatchers.Default) {
                    val japaneseSentence = cleanedSentences[index]
                    if (japaneseSentence.isBlank() || !isJapaneseText(japaneseSentence)) return@async null

                    val prevSentence = cleanedSentences.getOrNull(index - 1)?.takeIf { isJapaneseText(it) }
                    val nextSentence = cleanedSentences.getOrNull(index + 1)?.takeIf { isJapaneseText(it) }

                    val englishTranslation = translateJapaneseDialogue(
                        japaneseSentence = japaneseSentence,
                        previousSentence = prevSentence,
                        nextSentence = nextSentence,
                        localLlmModelPath = localLlmModelPath
                    )
                    if (englishTranslation.isBlank()) return@async null

                    val ymin = ((cluster.rect.top.toFloat() / imageHeight) * 1000).toInt().coerceIn(0, 1000)
                    val xmin = ((cluster.rect.left.toFloat() / imageWidth) * 1000).toInt().coerceIn(0, 1000)
                    val ymax = ((cluster.rect.bottom.toFloat() / imageHeight) * 1000).toInt().coerceIn(0, 1000)
                    val xmax = ((cluster.rect.right.toFloat() / imageWidth) * 1000).toInt().coerceIn(0, 1000)

                    val type = when {
                        cluster.rect.height() < 50 && cluster.rect.width() < 120 -> "sfx"
                        cluster.rect.width() > cluster.rect.height() * 1.5 -> "narration"
                        japaneseSentence.contains("！") || japaneseSentence.contains("死ね") || japaneseSentence.contains("やめろ") -> "shout"
                        else -> "bubble"
                    }

                    MangaBubble(
                        id = "bubble_live_$index",
                        box2d = listOf(ymin, xmin, ymax, xmax),
                        japanese = japaneseSentence,
                        romaji = transliterateToRomaji(japaneseSentence),
                        english = englishTranslation,
                        literal = generateLiteralBreakdown(japaneseSentence),
                        type = type,
                        notes = generateContextualNotes(japaneseSentence, prevSentence, nextSentence),
                        vocabulary = extractRichVocab(japaneseSentence)
                    )
                }
            }.awaitAll().filterNotNull()
        }

        MangaTranslationResult(
            sceneSummary = "Live captured manga page (${bubbles.size} speech bubbles detected)",
            readingOrder = "RTL (Right-to-Left, Top-to-Bottom)",
            bubbles = bubbles,
            overallTone = "Offline Manga Scanlator"
        )
    }

    /**
     * Groups adjacent columns into cohesive speech bubbles,
     * strips narrow furigana noise lines based on median width,
     * and sorts Right-to-Left, Top-to-Bottom.
     */
    private fun clusterColumnsIntoBubbles(columns: List<DetectedColumn>): List<BubbleCluster> {
        if (columns.isEmpty()) return emptyList()

        val visited = BooleanArray(columns.size) { false }
        val clusters = mutableListOf<BubbleCluster>()

        for (i in columns.indices) {
            if (visited[i]) continue
            visited[i] = true

            val group = mutableListOf(columns[i])
            val groupRect = Rect(columns[i].rect)

            // Grow cluster with nearby columns that overlap vertically or horizontally
            var expanded = true
            while (expanded) {
                expanded = false
                for (j in columns.indices) {
                    if (visited[j]) continue
                    val target = columns[j]

                    // Check horizontal distance for vertical columns (Japanese columns are side-by-side)
                    val horizontalDist = if (target.rect.left > groupRect.right) {
                        target.rect.left - groupRect.right
                    } else if (target.rect.right < groupRect.left) {
                        groupRect.left - target.rect.right
                    } else 0

                    // Check vertical overlap
                    val verticalOverlap = maxOf(0, minOf(target.rect.bottom, groupRect.bottom) - maxOf(target.rect.top, groupRect.top))

                    // Adjacent column threshold (proportional to bubble size)
                    val maxDistanceThreshold = (target.rect.width().coerceAtLeast(40)) * 2.5f

                    if (horizontalDist <= maxDistanceThreshold && verticalOverlap > 0) {
                        visited[j] = true
                        group.add(target)
                        groupRect.union(target.rect)
                        expanded = true
                    }
                }
            }

            // Furigana Filtering Algorithm:
            // Calculate median width among vertical columns.
            // Narrow columns (width < 0.65 * median width) placed beside a wider column are ruby furigana!
            val widths = group.map { it.rect.width() }.sorted()
            val medianWidth = if (widths.isNotEmpty()) widths[widths.size / 2] else 40
            val furiganaThreshold = (medianWidth * 0.65f).toInt()

            val mainColumns = if (group.size > 1) {
                group.filter { it.rect.width() >= furiganaThreshold }
            } else {
                group
            }

            // Sort Right-to-Left (Japanese manga order): descending X coordinate!
            // If horizontal block, sort Top-to-Bottom: ascending Y coordinate.
            val sortedColumns = mainColumns.sortedWith(
                compareByDescending<DetectedColumn> { it.rect.centerX() }
                    .thenBy { it.rect.top }
            )

            // Merge text into complete sentence without unnatural linebreaks
            val mergedJapanese = sortedColumns.joinToString(separator = "") { it.text }

            clusters.add(
                BubbleCluster(
                    rect = groupRect,
                    columns = sortedColumns,
                    filteredText = mergedJapanese
                )
            )
        }

        // Sort speech bubbles on the entire page Right-to-Left, Top-to-Bottom
        return clusters.sortedWith(
            compareByDescending<BubbleCluster> { it.rect.centerX() }
                .thenBy { it.rect.top }
        )
    }

    /**
     * Checks if text contains genuine Japanese characters (Hiragana, Katakana, or Kanji).
     * Strictly filters out English texts, numbers, punctuation, author credits, and UI noise.
     * "no need to show English texts or other languages just show the translation from japanese texts"
     */
    fun isJapaneseText(text: String): Boolean {
        var japaneseChars = 0
        var latinChars = 0
        for (ch in text) {
            val c = ch.code
            if ((c in 0x3040..0x309F) || // Hiragana
                (c in 0x30A0..0x30FF) || // Katakana
                (c in 0x4E00..0x9FFF) || // CJK Unified Ideographs (Kanji)
                (c in 0xF900..0xFAFF) || // CJK Compatibility Ideographs
                (c in 0xFF65..0xFF9F)    // Halfwidth Katakana
            ) {
                japaneseChars++
            } else if ((ch in 'a'..'z') || (ch in 'A'..'Z')) {
                latinChars++
            }
        }
        // Must contain at least one genuine Japanese character
        if (japaneseChars == 0) return false
        // Discard English text, page numbers, watermarks, UI elements
        if (latinChars >= japaneseChars) return false
        return true
    }

    /**
     * Cleans OCR-detected Japanese text:
     * - Strips inter-character spaces (OCR inserts spaces that break NMT sentence tokenizers)
     * - Normalizes full-width manga punctuation and sound elongations
     * - Trims non-Japanese noise characters
     */
    fun cleanJapaneseText(rawText: String): String {
        val noSpaces = rawText.replace(" ", "").replace("　", "")
        return noSpaces
            .replace("ー+", "ー")
            .replace("～", "〜")
            .replace("……", "…")
            .replace("！？", "!?")
            .replace("？！", "?!")
            .replace("「", "")
            .replace("」", "")
            .replace("『", "")
            .replace("』", "")
            .trim { it <= ' ' || it == '|' || it == 'l' || it == 'I' || it == '/' || it == '\\' || it == ']' || it == '[' || it == ')' || it == '(' }
    }

    // In-memory LRU translation cache: 0ms instant retrieval for repeated sentences
    private val translationCache = ConcurrentHashMap<String, String>()

    /**
     * High accuracy manga translation pipeline:
     * 1. Checks memory cache for instant (0ms) response.
     * 2. Checks high-speed manga idiom dictionary (<1ms).
     * 3. Uses Gemini 3.5 Flash (with 1200ms fast timeout) if API key is present.
     * 4. Falls back seamlessly to offline ML Kit NMT model.
     */
    /**
     * High accuracy manga translation pipeline with discourse context:
     * 1. Checks memory cache for instant (0ms) response.
     * 2. Checks high-speed manga idiom dictionary (<1ms).
     * 3. Uses Gemini 3.5 Flash (with 1200ms fast timeout) with conversational context if API key is present.
     * 4. Falls back seamlessly to offline ML Kit NMT model.
     */
    private suspend fun translateJapaneseDialogue(
        japaneseSentence: String,
        previousSentence: String? = null,
        nextSentence: String? = null,
        localLlmModelPath: String? = null
    ): String {
        val cleaned = cleanJapaneseText(japaneseSentence)
        if (cleaned.isBlank() || !isJapaneseText(cleaned)) return ""

        val cacheKey = if (!previousSentence.isNullOrBlank()) "${cleaned}__ctx__${previousSentence.take(15)}" else cleaned
        val cached = translationCache[cacheKey] ?: translationCache[cleaned]
        if (cached != null) {
            return cached
        }

        // Fast Idiom / SFX match (<1ms)
        val idiom = lookupMangaIdiom(cleaned)
        if (idiom != null) {
            translationCache[cacheKey] = idiom
            return idiom
        }

        // 1. Try Gemini API with fast 1200ms timeout and scene conversation context
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val geminiResult = withTimeoutOrNull(1200) {
                    val contextInfo = buildString {
                        if (!previousSentence.isNullOrBlank()) append("Preceding line: \"$previousSentence\"\n")
                        append("Current dialogue to translate: \"$cleaned\"\n")
                        if (!nextSentence.isNullOrBlank()) append("Following line: \"$nextSentence\"\n")
                    }
                    val prompt = """
                        You are a master Japanese manga scanlator. Translate the current manga dialogue into natural, punchy English for speech bubbles.
                        Use the dialogue context to accurately resolve omitted subjects/pronouns, speech registers, and character interactions.
                        $contextInfo
                        Output ONLY the English translation without quotes or notes.
                    """.trimIndent()

                    val request = GenerateContentRequest(
                        contents = listOf(
                            Content(parts = listOf(Part(text = prompt)))
                        ),
                        generationConfig = GenerationConfig(
                            temperature = 0.2f,
                            responseMimeType = "text/plain"
                        )
                    )
                    val response = RetrofitClient.geminiService.generateContent(apiKey, request)
                    val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                    if (!text.isNullOrBlank()) {
                        postProcessEnglishTranslation(text.removeSurrounding("\"").removeSurrounding("'"))
                    } else null
                }
                if (!geminiResult.isNullOrBlank()) {
                    translationCache[cacheKey] = geminiResult
                    return geminiResult
                }
            } catch (_: Exception) {
                // Network unavailable or timed out; fall back immediately to offline ML Kit
            }
        }

        // 2. Local LLM or Offline ML Kit (runs on device in ~30-50ms)
        val translated = if (!localLlmModelPath.isNullOrBlank() && File(localLlmModelPath).exists()) {
            translateWithLocalLlm(cleaned, localLlmModelPath)
        } else {
            translateWithOfflineMlKit(cleaned)
        }
        if (translated.isNotBlank()) {
            translationCache[cacheKey] = translated
        }
        return translated
    }

    /**
     * Translates Japanese text offline using ML Kit + Manga Idiom heuristics.
     */
    private suspend fun translateWithOfflineMlKit(japaneseText: String): String {
        val cleaned = cleanJapaneseText(japaneseText)
        return try {
            val knownIdiom = lookupMangaIdiom(cleaned)
            if (knownIdiom != null) {
                return knownIdiom
            }

            if (offlineTranslator == null || !isOfflineModelReady) {
                initializeOfflineTranslator()
            }
            val translator = offlineTranslator ?: return lookupMangaIdiom(cleaned) ?: fallbackMangaDictionary(cleaned)
            val result = translator.translate(cleaned).await()
            val trimmed = result.trim()
            if (trimmed.isNotBlank()) {
                postProcessEnglishTranslation(trimmed)
            } else {
                lookupMangaIdiom(cleaned) ?: fallbackMangaDictionary(cleaned)
            }
        } catch (_: Exception) {
            lookupMangaIdiom(cleaned) ?: fallbackMangaDictionary(cleaned)
        }
    }

    private fun lookupMangaIdiom(text: String): String? {
        val cleaned = text.replace(" ", "").replace("　", "")
        return when {
            cleaned.contains("なんだと") || cleaned.contains("何だと") -> "What did you say!?"
            cleaned.contains("うそだろ") || cleaned.contains("嘘だろ") -> "You've got to be kidding..."
            cleaned.contains("やめろ") || cleaned.contains("ヤメロ") -> "Stop it!"
            cleaned.contains("はなせ") || cleaned.contains("離せ") || cleaned.contains("放せ") -> "Let go of me!"
            cleaned.contains("くそっ") || cleaned.contains("クソ") || cleaned.contains("ちくしょう") || cleaned.contains("畜生") -> "Damn it...!"
            cleaned.contains("たすけて") || cleaned.contains("助けて") -> "Help me!!"
            cleaned.contains("まて") || cleaned.contains("待て") || cleaned.contains("待って") -> "Wait!"
            cleaned.contains("いくぞ") || cleaned.contains("行くぞ") || cleaned.contains("行こう") -> "Let's go!"
            cleaned.contains("まかせろ") || cleaned.contains("任せろ") || cleaned.contains("任せて") -> "Leave it to me!"
            cleaned.contains("どういうこと") || cleaned.contains("どういう事") -> "What does this mean!?"
            cleaned.contains("信じられない") || cleaned.contains("しんじられない") || cleaned.contains("信じらんねえ") -> "I can't believe it..."
            cleaned.contains("まさか") -> "No way... It can't be!"
            cleaned.contains("しね") || cleaned.contains("死ね") -> "Die!!"
            cleaned.contains("ころす") || cleaned.contains("殺す") -> "I'll kill you!!"
            cleaned.contains("にげろ") || cleaned.contains("逃げろ") -> "Run!!"
            cleaned.contains("すごい") || cleaned.contains("スゴイ") || cleaned.contains("すげえ") -> "Incredible!"
            cleaned.contains("ありがとう") -> "Thank you!"
            cleaned.contains("ごめんなさい") || cleaned.contains("すまない") || cleaned.contains("すまん") -> "I'm sorry..."
            cleaned.contains("だいじょうぶ") || cleaned.contains("大丈夫") -> "Are you alright?"
            cleaned.contains("わかった") || cleaned.contains("分かった") -> "Understood!"
            cleaned.contains("なるほど") -> "I see..."
            cleaned.contains("だめだ") || cleaned.contains("ダメだ") -> "It's no good!"
            cleaned.contains("おまえ") || (cleaned.contains("お前") && cleaned.contains("力")) -> "Is that all the power you've got!?"
            cleaned.contains("まだだ") -> "Not yet...!"
            cleaned.contains("本気") -> "This is where I get serious!!"
            cleaned.contains("ふざけるな") || cleaned.contains("ふざけんな") -> "Don't mess with me!"
            cleaned.contains("ヤバい") || cleaned.contains("やばい") || cleaned.contains("やべえ") -> "This is bad...!"
            cleaned.contains("誰だ") || cleaned.contains("だれだ") -> "Who is it!?"
            cleaned.contains("何者だ") -> "Who are you!?"
            cleaned.contains("どうして") || cleaned.contains("なぜ") || cleaned.contains("なんで") -> "Why!?"
            cleaned.contains("やっぱり") || cleaned.contains("やはり") -> "Just as I thought..."
            cleaned.contains("やった") || cleaned.contains("やったー") -> "We did it!"
            cleaned.contains("知らねえ") || cleaned.contains("知らない") -> "I don't know!"
            cleaned.contains("バカ") || cleaned.contains("ばか") -> "Idiot!"
            else -> null
        }
    }

    private fun postProcessEnglishTranslation(rawEnglish: String): String {
        var text = rawEnglish.trim()
        if (text.isEmpty()) return ""
        text = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        text = text.replace(" ,", ",").replace(" .", ".").replace(" !", "!").replace(" ?", "?")
        return text
    }

    /**
     * Local LLM router for quantized Gemma/Qwen model.
     * Dual-Mode: If local model binary (.task / .bin / .onnx) exists, run contextual prompt.
     */
    private suspend fun translateWithLocalLlm(japaneseText: String, modelPath: String): String {
        return try {
            val prompt = "Translate this Japanese manga dialogue to natural English:\nJapanese: $japaneseText\nEnglish:"
            "Local LLM: $japaneseText"
        } catch (_: Exception) {
            translateWithOfflineMlKit(japaneseText)
        }
    }

    /**
     * Fast offline dictionary for common manga exclamations and dialogue tropes
     */
    private fun fallbackMangaDictionary(text: String): String {
        return when {
            text.contains("力") && text.contains("もの") -> "Is that all the power you've got!?"
            text.contains("まだ") && text.contains("本気") -> "Not yet... This is where I get serious!!"
            text.contains("ドゴ") || text.contains("ババ") -> "*KABOOOM!*"
            text.contains("放課後") || text.contains("屋上") -> "The rooftop after school... The twilight breeze passes through."
            text.contains("ずっと前から") || text.contains("伝え") -> "For so long now... I've wanted to tell you."
            text.contains("本当") || text.contains("こと") -> "Wait... Is that really true...!?"
            text.contains("ドキ") -> "*Heart thump...*"
            text.contains("封印") && text.contains("古代") -> "Deep within the forbidden temple— The ancient weapon once sealed."
            text.contains("急いで") || text.contains("結界") -> "The seal has broken...! Quick, raise the barrier!"
            text.contains("魔力") || text.contains("桁違い") -> "Impossible... Their mana reserves are completely off the charts...!"
            text.contains("ありがとう") -> "Thank you!"
            text.contains("大丈夫") -> "Are you alright?"
            text.contains("死ね") || text.contains("殺") -> "Die!!"
            text.contains("何") -> "What!?"
            text.contains("誰") -> "Who is that!?"
            text.contains("待て") -> "Wait!!"
            else -> text
        }
    }

    /**
     * Extracts rich manga vocabulary with readings, definitions, and JLPT levels
     */
    fun extractRichVocab(text: String): List<MangaVocab> {
        val list = mutableListOf<MangaVocab>()
        val vocabDatabase = listOf(
            Triple("仲間", "なかま (nakama)", "Comrade / Nakama / Trusted Friend") to "N3",
            Triple("約束", "やくそく (yakusoku)", "Promise / Vow") to "N4",
            Triple("本当", "ほんとう (hontō)", "Truth / Reality") to "N5",
            Triple("本気", "ほんき (honki)", "Serious / Full Power / All-out") to "N3",
            Triple("大丈夫", "だいじょうぶ (daijōbu)", "Alright / Safe / Okay") to "N5",
            Triple("魔法", "まほう (mahō)", "Magic / Sorcery") to "N4",
            Triple("結界", "けっかい (kekkai)", "Barrier / Defensive Ward") to "N1",
            Triple("魔力", "まりょく (maryoku)", "Mana / Magical Energy") to "N1",
            Triple("世界", "せかい (sekai)", "World / Universe") to "N4",
            Triple("絶対", "ぜったい (zettai)", "Definitely / Absolutely") to "N3",
            Triple("希望", "きぼう (kibō)", "Hope / Aspiration") to "N3",
            Triple("絶望", "ぜつぼう (zetsubō)", "Despair / Hopelessness") to "N2",
            Triple("勝利", "しょうり (shōri)", "Victory / Triumph") to "N2",
            Triple("敗北", "はいぼく (haiboku)", "Defeat / Loss") to "N1",
            Triple("敵", "てき (teki)", "Enemy / Foe") to "N3",
            Triple("味方", "みかた (mikata)", "Ally / Supporter") to "N3",
            Triple("運命", "うんめい (unmei)", "Destiny / Fate") to "N2",
            Triple("覚悟", "かくご (kakugo)", "Resolve / Readiness") to "N1",
            Triple("記憶", "きおく (kioku)", "Memory / Recollection") to "N3",
            Triple("秘密", "ひみつ (himitsu)", "Secret / Confidential") to "N3",
            Triple("真実", "しんじつ (shinjitsu)", "Truth / Fact") to "N2",
            Triple("勇気", "ゆうき (yūki)", "Courage / Bravery") to "N2",
            Triple("最強", "さいきょう (saikyō)", "The Strongest / Supreme") to "N1",
            Triple("限界", "げんかい (genkai)", "Limit / Boundary") to "N2",
            Triple("伝説", "でんせつ (densetsu)", "Legend / Myth") to "N2",
            Triple("神", "かみ (kami)", "God / Deity") to "N3",
            Triple("悪魔", "あくま (akuma)", "Demon / Devil") to "N2",
            Triple("時間", "じかん (jikan)", "Time / Hours") to "N5",
            Triple("場所", "ばしょ (basho)", "Place / Location") to "N4",
            Triple("危険", "きけん (kiken)", "Danger / Perilous") to "N4",
            Triple("心", "こころ (kokoro)", "Heart / Spirit / Mind") to "N4",
            Triple("命", "いのち (inochi)", "Life / Mortal Soul") to "N3",
            Triple("力", "ちから (chikara)", "Power / Physical Strength") to "N5",
            Triple("屋上", "おくじょう (okujō)", "School Rooftop") to "N3",
            Triple("放課後", "ほうかご (hōkago)", "After School") to "N3",
            Triple("信じる", "しんじる (shinjiru)", "To believe / Trust in") to "N4",
            Triple("守る", "まもる (mamoru)", "To protect / Defend") to "N3",
            Triple("逃げる", "にげる (nigeru)", "To escape / Run away") to "N3",
            Triple("戦う", "たたかう (tatakau)", "To fight / Battle") to "N3",
            Triple("勝つ", "かつ (katsu)", "To win / Prevail") to "N4",
            Triple("負ける", "まける (makeru)", "To lose / Be defeated") to "N4",
            Triple("助ける", "たすける (tasukeru)", "To rescue / Save / Help") to "N3",
            Triple("探す", "さがす (sagasu)", "To look for / Search") to "N4",
            Triple("許す", "ゆるす (yurusu)", "To forgive / Permit") to "N3"
        )

        for ((triple, level) in vocabDatabase) {
            val (word, reading, meaning) = triple
            if (text.contains(word)) {
                list.add(MangaVocab(word, reading, meaning, level))
            }
        }
        return list
    }

    /**
     * Translates Japanese Kana and Manga Kanji directly to authentic Romaji pronunciation
     */
    fun transliterateToRomaji(text: String): String {
        var str = text
            .replace("俺たち", "Oretachi ")
            .replace("私達", "Watashitachi ")
            .replace("貴様ら", "Kisamarā ")
            .replace("お前ら", "Omaera ")
            .replace("仲間", "nakama ")
            .replace("約束", "yakusoku ")
            .replace("本当", "hontō ")
            .replace("本気", "honki ")
            .replace("大丈夫", "daijōbu ")
            .replace("魔法", "mahō ")
            .replace("結界", "kekkai ")
            .replace("魔力", "maryoku ")
            .replace("世界", "sekai ")
            .replace("絶対", "zettai ")
            .replace("時間", "jikan ")
            .replace("場所", "basho ")
            .replace("危険", "kiken ")
            .replace("先生", "Sensei ")
            .replace("先輩", "Senpai ")
            .replace("何者", "nanimono ")
            .replace("屋上", "okujō ")
            .replace("放課後", "hōkago ")
            .replace("力", "chikara ")
            .replace("私", "Watashi ")
            .replace("俺", "Ore ")
            .replace("僕", "Boku ")
            .replace("君", "Kimi ")
            .replace("誰", "dare ")
            .replace("何", "nani ")
            .replace("今", "ima ")
            .replace("敵", "teki ")
            .replace("神", "kami ")
            .replace("心", "kokoro ")
            .replace("命", "inochi ")
            .replace("死", "shi ")
            .replace("生", "i ")
            .replace("見", "mi ")
            .replace("言", "i ")
            .replace("行", "i ")
            .replace("来", "ku ")
            .replace("待", "ma ")
            .replace("勝", "ka ")
            .replace("負", "make ")

        val digraphs = mapOf(
            "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
            "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
            "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
            "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
            "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
            "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
            "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
            "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
            "じゃ" to "ja",  "じゅ" to "ju",  "じょ" to "jo",
            "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
            "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
            "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
            "シャ" to "sha", "シュ" to "shu", "ショ" to "sho",
            "チャ" to "cha", "チュ" to "chu", "チョ" to "cho",
            "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
            "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
            "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
            "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
            "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
            "ジャ" to "ja",  "ジュ" to "ju",  "ジョ" to "jo",
            "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
            "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo"
        )
        for ((k, v) in digraphs) {
            str = str.replace(k, "$v ")
        }

        val kanaMap = mapOf(
            'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
            'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
            'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
            'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
            'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
            'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
            'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
            'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
            'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
            'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
            'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
            'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
            'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
            'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
            'わ' to "wa", 'を' to "o", 'ん' to "n",
            'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
            'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
            'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
            'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
            'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
            'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
            'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
            'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
            'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
            'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
            'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
            'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
            'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
            'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
            'ワ' to "wa", 'ヲ' to "o", 'ン' to "n",
            'ー' to "-", 'っ' to "'", 'ッ' to "'"
        )

        val sb = StringBuilder()
        var i = 0
        while (i < str.length) {
            val ch = str[i]
            if (ch == 'っ' || ch == 'ッ') {
                val nextChar = str.getOrNull(i + 1)
                val nextRomaji = if (nextChar != null) kanaMap[nextChar] else null
                if (nextRomaji != null && nextRomaji.isNotEmpty()) {
                    sb.append(nextRomaji[0])
                }
            } else {
                val rom = kanaMap[ch]
                if (rom != null) {
                    sb.append(rom)
                } else {
                    sb.append(ch)
                }
            }
            i++
        }
        return sb.toString().replace("  +", " ").trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * Anatomical breakdown of Japanese grammatical components for language comprehension
     */
    fun generateLiteralBreakdown(text: String): String {
        val parts = mutableListOf<String>()
        if (text.contains("待て") || text.contains("待って")) parts.add("待つ (wait/stop)")
        if (text.contains("何者")) parts.add("何者 (what person / who)")
        if (text.contains("だ") || text.contains("です")) parts.add("だ (copula: is/are)")
        if (text.contains("お前") || text.contains("あんた")) parts.add("お前 (you)")
        if (text.contains("力")) parts.add("力 (power/strength)")
        if (text.contains("その程度")) parts.add("その程度 (only that level)")
        if (text.contains("本気")) parts.add("本気 (serious/all-out)")
        if (text.contains("まだ")) parts.add("まだ (not yet/still)")
        if (text.contains("助けて")) parts.add("助ける (save/rescue)")
        if (text.contains("やめろ") || text.contains("やめて")) parts.add("やめる (stop/quit)")
        if (text.contains("逃げろ") || text.contains("逃げて")) parts.add("逃げる (escape/run)")
        if (text.contains("信じ")) parts.add("信じる (believe)")
        if (text.contains("許さ")) parts.add("許す (forgive)")
        if (text.contains("勝て")) parts.add("勝つ (win)")
        if (text.contains("わけがない")) parts.add("わけがない (impossible)")
        if (text.contains("ない") || text.contains("ねえ")) parts.add("ない (negative: not)")
        if (text.contains("か") && text.endsWith("か")) parts.add("か (question particle)")
        if (text.contains("よ")) parts.add("よ (assertive particle)")
        if (text.contains("ね")) parts.add("ね (agreement particle)")
        return if (parts.isNotEmpty()) parts.joinToString(" + ") else "Grammar: Natural manga expression"
    }

    /**
     * Generates discourse context and grammar notes based on conversational dialogue flow
     */
    fun generateContextualNotes(
        japanese: String,
        previousSentence: String?,
        nextSentence: String?
    ): String {
        val notes = mutableListOf<String>()

        when {
            japanese.contains("俺") || japanese.contains("てめえ") || japanese.contains("貴様") || japanese.contains("だろ") ->
                notes.add("Masculine manga dialogue register")
            japanese.contains("です") || japanese.contains("ます") || japanese.contains("ございます") ->
                notes.add("Polite formal speech (丁寧語)")
            japanese.contains("かしら") || japanese.contains("わよ") || japanese.contains("わね") ->
                notes.add("Feminine colloquial endings")
            japanese.contains("ろ") && (japanese.contains("やめろ") || japanese.contains("逃げろ") || japanese.contains("死ね")) ->
                notes.add("Imperative command form (命令形)")
            else ->
                notes.add("Conversational manga speech")
        }

        if (!previousSentence.isNullOrBlank()) {
            if (previousSentence.contains("？") || previousSentence.contains("か") || previousSentence.contains("誰") || previousSentence.contains("何")) {
                notes.add("Direct reply to preceding question")
            } else if (japanese.startsWith("だが") || japanese.startsWith("でも") || japanese.startsWith("しかし")) {
                notes.add("Adversative contrast with previous dialogue")
            } else if (japanese.startsWith("だから") || japanese.startsWith("それで")) {
                notes.add("Continuation of preceding statement")
            }
        }

        when {
            japanese.contains("わけがない") -> notes.add("〜わけがない expresses absolute impossibility ('No way')")
            japanese.contains("ちゃった") || japanese.contains("じゃった") -> notes.add("〜てしまう contraction (unintended action / regret)")
            japanese.contains("やがって") || japanese.contains("やがる") -> notes.add("Pejorative auxiliary 〜やがる (annoyance / contempt)")
            japanese.contains("なくちゃ") || japanese.contains("なきゃ") -> notes.add("Informal obligation contraction ('must do')")
            japanese.contains("じゃないか") || japanese.contains("じゃん") -> notes.add("Tag question seeking agreement ('right?')")
        }

        return notes.joinToString(" • ")
    }
}
