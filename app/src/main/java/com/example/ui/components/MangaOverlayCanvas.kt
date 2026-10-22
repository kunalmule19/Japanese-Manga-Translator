package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BubbleType
import com.example.data.model.MangaBubble
import com.example.ui.theme.BubbleNarrationBg
import com.example.ui.theme.BubbleSfxBg
import com.example.ui.theme.MangaCrimson
import com.example.ui.theme.MangaIndigoPrimary
import com.example.ui.viewmodel.OverlayMode
import kotlin.math.roundToInt

@Composable
fun MangaOverlayCanvas(
    bitmap: Bitmap?,
    bubbles: List<MangaBubble>,
    selectedBubble: MangaBubble?,
    overlayMode: OverlayMode,
    onBubbleClick: (MangaBubble) -> Unit,
    modifier: Modifier = Modifier
) {
    if (bitmap == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No manga page loaded",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset = if (scale > 1f) {
            Offset(offset.x + offsetChange.x, offset.y + offsetChange.y)
        } else {
            Offset.Zero
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .transformable(state = transformState)
            .testTag("manga_overlay_canvas"),
        contentAlignment = Alignment.Center
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight

        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()
        val imageAspect = imageWidth / imageHeight
        val containerAspect = containerWidth.value / containerHeight.value

        val renderedWidth: Float
        val renderedHeight: Float

        if (imageAspect > containerAspect) {
            renderedWidth = containerWidth.value
            renderedHeight = containerWidth.value / imageAspect
        } else {
            renderedHeight = containerHeight.value
            renderedWidth = containerHeight.value * imageAspect
        }

        Box(
            modifier = Modifier
                .width(renderedWidth.dp)
                .height(renderedHeight.dp)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        ) {
            // Base Manga Image
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Manga page panel",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            // Overlays on top of speech bubbles
            if (overlayMode != OverlayMode.HIDE_OVERLAY) {
                bubbles.forEach { bubble ->
                    val isSelected = selectedBubble?.id == bubble.id

                    val bubbleX = (bubble.xMinFraction * renderedWidth).dp
                    val bubbleY = (bubble.yMinFraction * renderedHeight).dp
                    val bubbleW = ((bubble.xMaxFraction - bubble.xMinFraction) * renderedWidth).coerceAtLeast(36f).dp
                    val bubbleH = ((bubble.yMaxFraction - bubble.yMinFraction) * renderedHeight).coerceAtLeast(24f).dp

                    BubbleOverlayItem(
                        bubble = bubble,
                        overlayMode = overlayMode,
                        isSelected = isSelected,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    bubbleX.toPx().roundToInt(),
                                    bubbleY.toPx().roundToInt()
                                )
                            }
                            .width(bubbleW)
                            .height(bubbleH)
                            .clickable { onBubbleClick(bubble) }
                            .testTag("bubble_${bubble.id}")
                    )
                }
            }
        }
    }
}

@Composable
private fun BubbleOverlayItem(
    bubble: MangaBubble,
    overlayMode: OverlayMode,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val isClean = overlayMode == OverlayMode.CLEAN_BUBBLE

    val bgColor = when {
        !isClean -> Color(0xCC0B0F19)
        bubble.bubbleType == BubbleType.NARRATION -> BubbleNarrationBg
        bubble.bubbleType == BubbleType.SFX -> BubbleSfxBg
        else -> Color.White
    }

    val textColor = when {
        !isClean -> Color.White
        bubble.bubbleType == BubbleType.SFX -> MangaCrimson
        else -> Color(0xFF0F172A)
    }

    val shape = when (bubble.bubbleType) {
        BubbleType.NARRATION -> RoundedCornerShape(4.dp)
        BubbleType.SFX -> RoundedCornerShape(8.dp)
        BubbleType.SHOUT -> RoundedCornerShape(12.dp)
        else -> RoundedCornerShape(20.dp) // Elliptical speech bubble shape
    }

    val borderStroke = when {
        isSelected -> 2.5.dp
        isClean -> 1.5.dp
        else -> 1.dp
    }

    val borderColor = when {
        isSelected -> MangaIndigoPrimary
        bubble.bubbleType == BubbleType.SFX -> MangaCrimson
        isClean -> Color(0xFF1E293B)
        else -> Color(0x806366F1)
    }

    Surface(
        modifier = modifier
            .shadow(if (isSelected) 6.dp else 2.dp, shape = shape)
            .border(borderStroke, borderColor, shape),
        shape = shape,
        color = bgColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = bubble.english,
                color = textColor,
                fontSize = if (bubble.bubbleType == BubbleType.SFX) 11.sp else 10.sp,
                lineHeight = 12.sp,
                fontWeight = if (bubble.bubbleType == BubbleType.SHOUT || bubble.bubbleType == BubbleType.SFX) {
                    FontWeight.ExtraBold
                } else {
                    FontWeight.Bold
                },
                fontFamily = FontFamily.SansSerif,
                textAlign = TextAlign.Center,
                maxLines = 6
            )
        }
    }
}
