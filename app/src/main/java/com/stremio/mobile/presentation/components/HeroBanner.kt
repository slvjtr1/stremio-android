package com.stremio.mobile.presentation.components

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.stremio.mobile.core.theme.CardFallback
import com.stremio.mobile.core.theme.MutedText
import com.stremio.mobile.data.model.CatalogItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val ROTATE_MS = 7_000
private const val CROSSFADE_MS = 900

/** True when the user disabled animations system-wide (Accessibility → Remove animations). */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Home-screen Hero Banner.
 *
 * Full-bleed backdrop with a crossfade between titles, subtle parallax driven by the list scroll
 * (read inside `graphicsLayer`, so scrolling never recomposes the banner), auto-rotation that
 * only runs while the screen is resumed and visible, swipe navigation, and next-image prefetch.
 * With [reducedMotion] there is no auto-rotation, crossfade or parallax.
 */
@Composable
fun HeroBanner(
    items: List<CatalogItem>,
    onWatch: (CatalogItem) -> Unit,
    onMoreInfo: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    scrollOffsetPx: () -> Int = { 0 },
) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val density = LocalDensity.current
    var index by remember(items) { mutableIntStateOf(0) }
    var userInteractedAt by remember { mutableStateOf(0L) }
    val progress = remember(items) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val current = items[index.coerceIn(0, items.lastIndex)]

    // Prefetch the next backdrop so the crossfade never shows a loading frame.
    LaunchedEffect(index, items) {
        val next = items[(index + 1) % items.size]
        (next.background ?: next.poster)?.let { url ->
            SingletonImageLoader.get(context).enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }

    if (!reducedMotion && items.size > 1) {
        LaunchedEffect(items, userInteractedAt) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(ROTATE_MS, easing = LinearEasing))
                    index = (index + 1) % items.size
                }
            }
        }
    }

    fun go(delta: Int) {
        index = Math.floorMod(index + delta, items.size)
        userInteractedAt = System.currentTimeMillis()
        scope.launch { progress.snapTo(0f) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wide = maxWidth > 600.dp
        val bannerHeight = (maxWidth * if (wide) 0.42f else 0.98f).coerceIn(300.dp, 520.dp)
        val parallaxLimitPx = with(density) { (bannerHeight / 3).toPx() }

        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(bannerHeight)
                .clip(RoundedCornerShape(28.dp))
                .background(CardFallback)
                .pointerInput(items) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (abs(total) > 64.dp.toPx()) go(if (total < 0) 1 else -1)
                        },
                    ) { _, dx -> total += dx }
                },
        ) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    if (reducedMotion) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        fadeIn(tween(CROSSFADE_MS)) togetherWith fadeOut(tween(CROSSFADE_MS))
                    }
                },
                contentKey = { it.id },
                label = "hero-backdrop",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (!reducedMotion) {
                            val y = scrollOffsetPx().toFloat().coerceIn(0f, parallaxLimitPx)
                            translationY = y * 0.45f
                            val s = 1.06f + y / parallaxLimitPx * 0.04f
                            scaleX = s
                            scaleY = s
                        }
                    },
            ) { item ->
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.background ?: item.poster)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Legibility scrims: top for the status bar, bottom/left for the text block.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x33050515),
                            0.4f to Color.Transparent,
                            0.72f to Color(0xB3050515),
                            1f to Color(0xF2050515),
                        ),
                    ),
            )
            if (wide) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to Color(0xE6050515),
                                0.55f to Color(0x00050515),
                            ),
                        ),
                )
            }

            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    if (reducedMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else fadeIn(tween(500, delayMillis = 200)) togetherWith fadeOut(tween(250))
                },
                contentKey = { it.id },
                label = "hero-info",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                    .widthIn(max = 560.dp),
            ) { item ->
                HeroInfo(item = item, onWatch = { onWatch(item) }, onMoreInfo = { onMoreInfo(item) })
            }

            if (items.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 22.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items.indices.forEach { i ->
                        val selected = i == index
                        Box(
                            modifier = Modifier
                                .width(if (selected) 18.dp else 6.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0x40FFFFFF))
                                .clickable { go(i - index) },
                        ) {
                            if (selected) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val p = if (reducedMotion) 1f else progress.value
                                            scaleX = p
                                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                        }
                                        .background(Color.White),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroInfo(
    item: CatalogItem,
    onWatch: () -> Unit,
    onMoreInfo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!item.logo.isNullOrBlank()) {
            AsyncImage(
                model = item.logo,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier
                    .heightIn(max = 84.dp)
                    .widthIn(max = 280.dp),
            )
        } else {
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 32.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HeroMetaLine(item)
        item.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                color = Color(0xE6FFFFFF),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroButton(
                text = "Watch now",
                primary = true,
                onClick = onWatch,
                icon = { Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF0C0B11), modifier = Modifier.size(20.dp)) },
            )
            HeroButton(
                text = "More info",
                primary = false,
                onClick = onMoreInfo,
                icon = { Icon(Icons.Outlined.Info, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun HeroMetaLine(item: CatalogItem) {
    val parts = buildList {
        item.releaseInfo?.takeIf { it.isNotBlank() }?.let { add(it) }
        addAll(item.genres.take(3))
    }
    if (parts.isEmpty() && item.imdbRating.isNullOrBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item.imdbRating?.takeIf { it.isNotBlank() }?.let { rating ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { contentDescription = "IMDb rating $rating" },
            ) {
                Icon(Icons.Filled.Star, null, tint = Color(0xFFF5C518), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(rating, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (parts.isNotEmpty()) {
            Text(
                text = parts.joinToString("  ·  "),
                color = MutedText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HeroButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) Color.White else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon()
        Text(
            text = text,
            color = if (primary) Color(0xFF0C0B11) else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
