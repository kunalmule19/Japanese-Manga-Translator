package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.ui.theme.MangaCrimson
import com.example.ui.theme.MangaEmerald
import com.example.ui.theme.MangaIndigoPrimary
import com.example.ui.viewmodel.MangaViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MangaViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    BackHandler { onBack() }
    val uiState by viewModel.uiState.collectAsState()

    var apiKeyInput by remember { mutableStateOf(uiState.apiKey) }
    val isKeyConfigured = uiState.apiKey.isNotBlank() && uiState.apiKey != "MY_GEMINI_API_KEY"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings & Manga Guide",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_btn")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("settings_screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API Key Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (isKeyConfigured) MangaEmerald.copy(alpha = 0.2f) else MangaCrimson.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isKeyConfigured) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (isKeyConfigured) MangaEmerald else MangaCrimson,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Gemini 3.5 Flash Vision AI",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = if (isKeyConfigured) "API Key Configured & Ready" else "API Key Required for Custom Scans",
                                        fontSize = 12.sp,
                                        color = if (isKeyConfigured) MangaEmerald else MangaCrimson,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "To scan live physical manga books or custom gallery uploads with real-time AI, provide your Gemini API key (or configure it in AI Studio Secrets panel). Built-in demo manga panels work offline without a key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("Gemini API Key") },
                            placeholder = { Text("AIzaSy...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("api_key_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                viewModel.setCustomApiKey(apiKeyInput.trim())
                                Toast.makeText(context, "API Key updated", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.align(Alignment.End).testTag("save_api_key_btn")
                        ) {
                            Text("Save Key")
                        }
                    }
                }
            }

            // Heavy High-Accuracy Local AI Engine (MediaPipe Local LLM + ML Kit)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("local_ai_engine_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = MangaIndigoPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Local AI Engine (Dual-Mode Operation)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Supports Google MediaPipe GenAI / ONNX Runtime alongside Google ML Kit Japanese V2. If you load a quantized local LLM (e.g. Gemma-2-2B-JPN-IT or Qwen2.5-3B in .task/.bin format), manga dialogue is routed through the local LLM. Without an external file, it automatically uses the high-speed offline ML Kit model out of the box.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        var localModelPathInput by remember { mutableStateOf(uiState.localLlmPath ?: "") }

                        OutlinedTextField(
                            value = localModelPathInput,
                            onValueChange = {
                                localModelPathInput = it
                                viewModel.setLocalLlmPath(if (it.isBlank()) null else it)
                            },
                            label = { Text("Local LLM Path (.task / .bin / .onnx)") },
                            placeholder = { Text("/sdcard/Download/gemma-2-2b-jpn-it.bin") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("local_llm_path_input")
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (localModelPathInput.isNotBlank()) "● Model Path Set" else "● Using Offline ML Kit V2 (Default)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MangaEmerald
                            )

                            Button(
                                onClick = {
                                    Toast.makeText(context, "Local model path saved", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Save Model")
                            }
                        }
                    }
                }
            }

            // Offline Japanese Audio (Tap-to-Speak TTS)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MangaIndigoPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Offline Japanese Audio (Tap-to-Speak)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tapping any translated bubble reads the original Japanese sentence aloud using Android's offline Japanese TextToSpeech (Locale.JAPAN) with local-only forced mode (zero network latency).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                viewModel.speakJapanese("お前の力はそんなものか！？")
                                Toast.makeText(context, "Playing offline Japanese audio...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().testTag("test_offline_tts_btn")
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test Offline Voice: 「お前の力はそんなものか！？」")
                        }
                    }
                }
            }

            // Translation Tone Preference
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = MangaIndigoPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Manga Scanlation Tone",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val tones = listOf(
                            "Natural Manga" to "Punchy scanlator style fitting manga dialogue",
                            "Dramatic Shonen" to "Intense, spirited battle expressions & shouted oaths",
                            "Literal Breakdown" to "Close to direct syntax for Japanese language learners",
                            "Casual Slang" to "Modern conversational Japanese slang & colloquialisms"
                        )

                        tones.forEach { (tone, desc) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = uiState.preferredTone == tone,
                                    onClick = { viewModel.setPreferredTone(tone) }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(text = tone, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(text = desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // Technical Issues Resolved in Manga Translation
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = MangaIndigoPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Technical Solutions in Manga Translation",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        TechNote(
                            title = "1. Vertical Text & Right-to-Left (RTL) Order",
                            body = "Traditional Japanese manga is read right-to-left and top-to-bottom. Our model prompt segments columns vertically and sequences dialogue bubbles in true reading order."
                        )

                        TechNote(
                            title = "2. Furigana Deduplication",
                            body = "Manga often features tiny phonetic hiragana (furigana) beside kanji. Standard OCR engines duplicate these characters into garbled text. Gemini Vision identifies ruby annotations without repeating syllables."
                        )

                        TechNote(
                            title = "3. Sound Effects (SFX / Onomatopoeia)",
                            body = "Dynamic sound effects (e.g. ドゴォォン, ドキッ) are classified separately from speech bubbles so sound words are rendered with stylized comic typography."
                        )

                        TechNote(
                            title = "4. Inpainting Clean Bubble Overlays",
                            body = "Interactive overlays scale and position directly over the detected bubble boundary coordinates [ymin, xmin, ymax, xmax], cleanly masking original Japanese with English text."
                        )
                    }
                }
            }

            // About
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Manga Live Translator v1.0",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Engineered with Gemini 3.5 Flash & Jetpack Compose",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TechNote(title: String, body: String) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = body,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp
        )
    }
}
