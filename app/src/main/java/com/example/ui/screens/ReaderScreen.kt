package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BubbleType
import com.example.ui.components.DialogueInspectorSheet
import com.example.ui.components.MangaOverlayCanvas
import com.example.ui.theme.MangaCrimson
import com.example.ui.theme.MangaIndigoDark
import com.example.ui.theme.MangaIndigoPrimary
import com.example.ui.viewmodel.MangaViewModel
import com.example.ui.viewmodel.OverlayMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: MangaViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler { onBack() }

    var showToneMenu by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.currentTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Manga Translator · RTL",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MangaIndigoPrimary.copy(alpha = 0.2f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = uiState.preferredTone,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MangaIndigoPrimary
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("reader_back_btn")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Overlay Mode Toggle
                    IconButton(
                        onClick = {
                            val nextMode = when (uiState.overlayMode) {
                                OverlayMode.CLEAN_BUBBLE -> OverlayMode.SEMI_TRANSPARENT
                                OverlayMode.SEMI_TRANSPARENT -> OverlayMode.HIDE_OVERLAY
                                OverlayMode.HIDE_OVERLAY -> OverlayMode.CLEAN_BUBBLE
                            }
                            viewModel.setOverlayMode(nextMode)
                            val label = when (nextMode) {
                                OverlayMode.CLEAN_BUBBLE -> "Clean Bubble Inpainting"
                                OverlayMode.SEMI_TRANSPARENT -> "Translucent HUD"
                                OverlayMode.HIDE_OVERLAY -> "Original Japanese Artwork"
                            }
                            Toast.makeText(context, "Mode: $label", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("toggle_overlay_mode_btn")
                    ) {
                        Icon(
                            imageVector = when (uiState.overlayMode) {
                                OverlayMode.HIDE_OVERLAY -> Icons.Default.VisibilityOff
                                else -> Icons.Default.Layers
                            },
                            contentDescription = "Toggle Overlay Mode",
                            tint = if (uiState.overlayMode == OverlayMode.HIDE_OVERLAY) MangaCrimson else MaterialTheme.colorScheme.primary
                        )
                    }

                    // Tone selector
                    Box {
                        IconButton(
                            onClick = { showToneMenu = true },
                            modifier = Modifier.testTag("tone_selector_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Tune, contentDescription = "Tone")
                        }

                        DropdownMenu(
                            expanded = showToneMenu,
                            onDismissRequest = { showToneMenu = false }
                        ) {
                            val tones = listOf("Natural Manga", "Literal Breakdown", "Casual Slang", "Dramatic Shonen")
                            tones.forEach { tone ->
                                DropdownMenuItem(
                                    text = { Text(tone) },
                                    onClick = {
                                        showToneMenu = false
                                        viewModel.setPreferredTone(tone)
                                        viewModel.translateCurrentPage()
                                    }
                                )
                            }
                        }
                    }

                    // Re-translate with AI
                    IconButton(
                        onClick = { viewModel.translateCurrentPage() },
                        enabled = !uiState.isTranslating,
                        modifier = Modifier.testTag("retranslate_btn")
                    ) {
                        if (uiState.isTranslating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Re-translate")
                        }
                    }

                    // Save to Library
                    IconButton(
                        onClick = { viewModel.saveCurrentPageToLibrary() },
                        modifier = Modifier.testTag("save_to_library_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Bookmark, contentDescription = "Save Page")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            // Main Interactive Manga Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                MangaOverlayCanvas(
                    bitmap = uiState.currentBitmap,
                    bubbles = uiState.translationResult?.bubbles ?: emptyList(),
                    selectedBubble = uiState.selectedBubble,
                    overlayMode = uiState.overlayMode,
                    onBubbleClick = { bubble -> viewModel.selectBubble(bubble) },
                    modifier = Modifier.fillMaxSize()
                )

                // Translating progress banner
                if (uiState.isTranslating) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = MangaIndigoPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Translating Japanese manga panels...",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Bottom Sequential Dialogue Strip (Reading Order Timeline)
            val bubbles = uiState.translationResult?.bubbles ?: emptyList()
            if (bubbles.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "READING ORDER FLOW (${bubbles.size} bubbles)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Tap any bubble to inspect",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.testTag("dialogue_flow_strip")
                        ) {
                            itemsIndexed(bubbles) { index, bubble ->
                                val isSelected = uiState.selectedBubble?.id == bubble.id
                                BubbleChipItem(
                                    index = index + 1,
                                    bubble = bubble,
                                    isSelected = isSelected,
                                    onClick = { viewModel.selectBubble(bubble) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogue Inspector Bottom Sheet
    if (uiState.selectedBubble != null) {
        DialogueInspectorSheet(
            bubble = uiState.selectedBubble,
            onDismiss = { viewModel.selectBubble(null) },
            onSpeak = { text -> viewModel.speakJapanese(text) },
            onSaveVocab = { vocab, contextSentence ->
                viewModel.saveVocabWord(vocab, contextSentence)
            },
            isTtsReady = uiState.isTtsReady
        )
    }
}

@Composable
private fun BubbleChipItem(
    index: Int,
    bubble: com.example.data.model.MangaBubble,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .width(220.dp)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MangaIndigoPrimary else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MangaIndigoPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = index.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = bubble.type.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = "${bubble.vocabulary.size} words",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = bubble.english,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = bubble.japanese,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
