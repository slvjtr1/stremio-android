package com.stremio.mobile.presentation.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size

/** Spoiler protection strength for unwatched episode artwork. */
enum class SpoilerBlurLevel(val storageValue: String, val radiusDp: Int, val decodeWidthPx: Int) {
    OFF("off", 0, 0),
    LIGHT("light", 10, 64),
    MEDIUM("medium", 18, 32),
    STRONG("strong", 30, 16);

    companion object {
        fun from(value: String?): SpoilerBlurLevel = entries.firstOrNull { it.storageValue == value } ?: MEDIUM
    }
}

/**
 * Episode thumbnail that hides its content until the episode has been watched.
 *
 * Android 12+ uses a GPU RenderEffect blur. Older versions have no blur primitive, so the image
 * is decoded at a tiny size and scaled up, which produces a soft, unreadable image at a fraction
 * of the decode cost.
 */
@Composable
fun SpoilerArtwork(
    url: String,
    hidden: Boolean,
    level: SpoilerBlurLevel,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val blur = hidden && level != SpoilerBlurLevel.OFF
    val useRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val request = remember(url, blur, level, useRenderEffect) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                if (blur && !useRenderEffect) size(Size(level.decodeWidthPx, level.decodeWidthPx * 9 / 16))
            }
            .crossfade(true)
            .build()
    }
    Box(modifier = modifier) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (blur && useRenderEffect) {
                        Modifier.blur(level.radiusDp.dp, BlurredEdgeTreatment.Rectangle)
                    } else {
                        Modifier
                    },
                ),
        )
        if (blur) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x40000000)),
            )
            Icon(
                imageVector = Icons.Outlined.VisibilityOff,
                contentDescription = null,
                tint = Color(0xCCFFFFFF),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(16.dp),
            )
        }
    }
}
