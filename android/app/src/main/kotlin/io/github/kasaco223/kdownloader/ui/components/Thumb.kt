// SPDX-FileCopyrightText: 2026 Kasaco223 y colaboradores de K-Downloader
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kasaco223.kdownloader.ui.components

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kasaco223.kdownloader.download.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Las miniaturas de la cola. Null (pruebas, vistas sueltas): siempre el ícono del formato. */
val LocalThumbnails = compositionLocalOf<Thumbnails?> { null }

private val noThumbnails = MutableStateFlow(0)

/** Imágenes ya leídas, por ruta y fecha: volver a una pantalla no relee el disco. */
private object ThumbCache {
    private val cache = LruCache<String, ImageBitmap>(48)

    fun load(file: File): ImageBitmap? {
        if (!file.exists()) return null
        val key = file.path + ":" + file.lastModified()
        cache.get(key)?.let { return it }
        val image = BitmapFactory.decodeFile(file.path)?.asImageBitmap() ?: return null
        cache.put(key, image)
        return image
    }
}

/**
 * La miniatura del trabajo [id] (16:9 o lo que diga [modifier]); mientras no exista, el ícono [fallback] sobre
 * una superficie. [overlay] dibuja encima (duración, progreso). Decorativa: TalkBack lee el título de al lado.
 */
@Composable
fun Thumb(
    id: String,
    @DrawableRes fallback: Int,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    iconSize: Dp = 28.dp,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val thumbs = LocalThumbnails.current
    val version by (thumbs?.version ?: noThumbnails).collectAsStateWithLifecycle()
    val image by produceState<ImageBitmap?>(null, id, version, thumbs) {
        value = thumbs?.let { t -> withContext(Dispatchers.IO) { ThumbCache.load(t.file(id)) } }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(painterResource(fallback), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(iconSize))
        }
        overlay()
    }
}

/** Duración abajo a la derecha de una miniatura ("4:28", "1:02:03"). */
@Composable
fun BoxScope.DurationBadge(seconds: Long?) {
    if (seconds == null || seconds <= 0) return
    OverlayBadge(DateUtils.formatElapsedTime(seconds), Modifier.align(Alignment.BottomEnd).padding(6.dp))
}
