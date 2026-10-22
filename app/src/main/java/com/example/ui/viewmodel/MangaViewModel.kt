package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.R
import com.example.data.api.GeminiMangaTranslator
import com.example.data.db.MangaDatabase
import com.example.data.model.MangaBubble
import com.example.data.model.MangaTranslationResult
import com.example.data.model.MangaVocab
import com.example.data.model.SavedPage
import com.example.data.model.SavedVocab
import com.example.data.sample.SampleMangaData
import com.example.data.sample.SampleMangaPage
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

enum class OverlayMode {
    CLEAN_BUBBLE,     // Inpainting style: Solid white/theme bubble with comic typography
    SEMI_TRANSPARENT, // Translucent badge overlay
    HIDE_OVERLAY      // Peek original artwork
}

data class MangaUiState(
    val currentBitmap: Bitmap? = null,
    val currentDrawableResId: Int? = R.drawable.sample_manga_battle,
    val currentTitle: String = "Clash of Rivals",
    val translationResult: MangaTranslationResult? = null,
    val selectedBubble: MangaBubble? = null,
    val isTranslating: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val overlayMode: OverlayMode = OverlayMode.CLEAN_BUBBLE,
    val preferredTone: String = "Natural Manga",
    val apiKey: String = BuildConfig.GEMINI_API_KEY,
    val isTtsReady: Boolean = false,
    val useOfflineEngine: Boolean = true,
    val localLlmPath: String? = null,
    val isLiveOverlayActive: Boolean = false
)

class MangaViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val db = MangaDatabase.getDatabase(application)
    private val dao = db.mangaDao()

    private val _uiState = MutableStateFlow(MangaUiState())
    val uiState: StateFlow<MangaUiState> = _uiState.asStateFlow()

    val savedPages: StateFlow<List<SavedPage>> = dao.getAllSavedPages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedVocab: StateFlow<List<SavedVocab>> = dao.getAllVocab()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var tts: TextToSpeech? = null

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val bubbleListType = Types.newParameterizedType(List::class.java, MangaBubble::class.java)
    private val bubbleListAdapter = moshi.adapter<List<MangaBubble>>(bubbleListType)

    init {
        // Init TTS for Japanese pronunciation
        tts = TextToSpeech(application, this)

        // Load the first sample panel by default
        loadSamplePage(SampleMangaData.samplePages.first())
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.JAPANESE)
            val ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            _uiState.update { it.copy(isTtsReady = ready) }
        }
    }

    fun speakJapanese(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "manga_tts_${System.currentTimeMillis()}")
    }

    fun setOverlayMode(mode: OverlayMode) {
        _uiState.update { it.copy(overlayMode = mode) }
    }

    fun setPreferredTone(tone: String) {
        _uiState.update { it.copy(preferredTone = tone) }
    }

    fun setCustomApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    fun selectBubble(bubble: MangaBubble?) {
        _uiState.update { it.copy(selectedBubble = bubble) }
    }

    fun loadSamplePage(sample: SampleMangaPage) {
        val bitmap = BitmapFactory.decodeResource(getApplication<Application>().resources, sample.drawableResId)
        _uiState.update {
            it.copy(
                currentBitmap = bitmap,
                currentDrawableResId = sample.drawableResId,
                currentTitle = sample.title,
                translationResult = sample.result,
                selectedBubble = null,
                errorMessage = null,
                successMessage = null
            )
        }
    }

    fun loadBitmap(bitmap: Bitmap, title: String) {
        _uiState.update {
            it.copy(
                currentBitmap = bitmap,
                currentDrawableResId = null,
                currentTitle = title,
                translationResult = null,
                selectedBubble = null,
                errorMessage = null,
                successMessage = null
            )
        }
        // Automatically trigger translation for new image
        translateCurrentPage()
    }

    fun setUseOfflineEngine(useOffline: Boolean) {
        _uiState.update { it.copy(useOfflineEngine = useOffline) }
    }

    fun setLocalLlmPath(path: String?) {
        _uiState.update { it.copy(localLlmPath = path) }
    }

    fun setLiveOverlayActive(active: Boolean) {
        _uiState.update { it.copy(isLiveOverlayActive = active) }
    }

    fun translateCurrentPage() {
        val bitmap = _uiState.value.currentBitmap ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isTranslating = true, errorMessage = null) }

            if (_uiState.value.useOfflineEngine) {
                // 100% Offline ML Kit Japanese V2 + Tategaki vertical sorting + Furigana filtering
                try {
                    val result = com.example.data.engine.MangaTranslationEngine.processMangaImage(
                        context = getApplication(),
                        bitmap = bitmap,
                        localLlmModelPath = _uiState.value.localLlmPath
                    )
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            translationResult = result,
                            successMessage = "Translated ${result.bubbles.size} dialogue bubbles with Offline Engine!"
                        )
                    }
                } catch (e: Exception) {
                    val matchingSample = SampleMangaData.samplePages.find { it.title == _uiState.value.currentTitle }
                    if (matchingSample != null) {
                        _uiState.update {
                            it.copy(
                                isTranslating = false,
                                translationResult = matchingSample.result,
                                successMessage = "Loaded sample dataset for ${matchingSample.title}"
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isTranslating = false,
                                errorMessage = "Offline OCR error: ${e.localizedMessage}"
                            )
                        }
                    }
                }
            } else {
                // Cloud Gemini Vision AI Mode
                val apiKey = _uiState.value.apiKey
                val isConfigured = apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY"

                if (isConfigured) {
                    val result = GeminiMangaTranslator.translateMangaPanel(
                        bitmap = bitmap,
                        apiKey = apiKey,
                        preferredTone = _uiState.value.preferredTone
                    )

                    result.fold(
                        onSuccess = { translation ->
                            _uiState.update {
                                it.copy(
                                    isTranslating = false,
                                    translationResult = translation,
                                    successMessage = "Translated ${translation.bubbles.size} dialogue bubbles with Gemini AI!"
                                )
                            }
                        },
                        onFailure = { error ->
                            val matchingSample = SampleMangaData.samplePages.find { it.title == _uiState.value.currentTitle }
                            if (matchingSample != null) {
                                _uiState.update {
                                    it.copy(
                                        isTranslating = false,
                                        translationResult = matchingSample.result,
                                        errorMessage = "API Notice: ${error.localizedMessage ?: "Network issue"}. Loaded verified dataset."
                                    )
                                }
                            } else {
                                _uiState.update {
                                    it.copy(
                                        isTranslating = false,
                                        errorMessage = error.localizedMessage ?: "Translation failed"
                                    )
                                }
                            }
                        }
                    )
                } else {
                    val matchingSample = SampleMangaData.samplePages.find { it.title == _uiState.value.currentTitle }
                    if (matchingSample != null) {
                        _uiState.update {
                            it.copy(
                                isTranslating = false,
                                translationResult = matchingSample.result,
                                successMessage = "Demo mode active for ${matchingSample.title}. Add your Gemini API key in Settings or switch to Offline Engine."
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isTranslating = false,
                                errorMessage = "Gemini API key is required for cloud mode. Switch to Offline Mode or enter your key in Settings."
                            )
                        }
                    }
                }
            }
        }
    }

    fun saveCurrentPageToLibrary() {
        val bitmap = _uiState.value.currentBitmap ?: return
        val result = _uiState.value.translationResult ?: return

        viewModelScope.launch {
            try {
                // Save bitmap to app private storage
                val filename = "manga_page_${System.currentTimeMillis()}.jpg"
                val file = File(getApplication<Application>().filesDir, filename)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }

                val bubblesJson = bubbleListAdapter.toJson(result.bubbles)
                val savedPage = SavedPage(
                    title = _uiState.value.currentTitle,
                    imagePath = file.absolutePath,
                    bubblesJson = bubblesJson,
                    sceneSummary = result.sceneSummary,
                    bubbleCount = result.bubbles.size
                )
                dao.insertSavedPage(savedPage)
                _uiState.update { it.copy(successMessage = "Page saved to your Library!") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to save: ${e.localizedMessage}") }
            }
        }
    }

    fun loadSavedPage(page: SavedPage) {
        viewModelScope.launch {
            try {
                val bitmap = BitmapFactory.decodeFile(page.imagePath)
                val bubbles = bubbleListAdapter.fromJson(page.bubblesJson) ?: emptyList()
                val result = MangaTranslationResult(
                    sceneSummary = page.sceneSummary,
                    readingOrder = "RTL",
                    bubbles = bubbles,
                    overallTone = "Saved"
                )

                _uiState.update {
                    it.copy(
                        currentBitmap = bitmap,
                        currentDrawableResId = null,
                        currentTitle = page.title,
                        translationResult = result,
                        selectedBubble = null,
                        errorMessage = null,
                        successMessage = "Loaded '${page.title}' from Library"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Could not open saved page") }
            }
        }
    }

    fun deleteSavedPage(page: SavedPage) {
        viewModelScope.launch {
            try {
                File(page.imagePath).delete()
                dao.deleteSavedPage(page)
                _uiState.update { it.copy(successMessage = "Removed from library") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to delete page") }
            }
        }
    }

    fun saveVocabWord(vocab: MangaVocab, contextSentence: String) {
        viewModelScope.launch {
            val saved = SavedVocab(
                word = vocab.word,
                reading = vocab.reading,
                meaning = vocab.meaning,
                jlptLevel = vocab.jlptLevel,
                contextSentence = contextSentence
            )
            dao.insertVocab(saved)
            _uiState.update { it.copy(successMessage = "Saved '${vocab.word}' to your Vocabulary Deck!") }
        }
    }

    fun toggleVocabMastery(vocab: SavedVocab) {
        viewModelScope.launch {
            dao.setVocabMastered(vocab.id, !vocab.isMastered)
        }
    }

    fun deleteVocab(vocab: SavedVocab) {
        viewModelScope.launch {
            dao.deleteVocab(vocab)
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts?.stop()
        tts?.shutdown()
    }
}
