package com.kutu.chat.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kutu.chat.KutuApp
import com.kutu.chat.domain.ATT_IMAGE
import com.kutu.chat.domain.Attachment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun attachmentFile(a: Attachment): File {
    val app = LocalContext.current.applicationContext as KutuApp
    return app.container.attachments.file(a)
}

/** Dosyayı en fazla [maxSide] pikselde çözer (ana iş parçacığını bloklamaz). */
@Composable
private fun rememberBitmap(file: File, maxSide: Int): Bitmap? {
    val state = produceState<Bitmap?>(null, file.path, maxSide) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    return state.value
}

/** Tek bir ek: resimse küçük resim, metinse dosya adı rozeti. [onRemove] verilirse sağ üstte kaldır düğmesi çıkar. */
@Composable
fun AttachmentThumb(a: Attachment, size: Dp = 64.dp, onRemove: (() -> Unit)? = null) {
    var preview by remember { mutableStateOf(false) }
    Box {
        if (a.type == ATT_IMAGE) {
            val bmp = rememberBitmap(attachmentFile(a), 256)
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { preview = true },
            ) {
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 180.dp).padding(top = 0.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, contentDescription = "Eki kaldır", tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(14.dp))
            }
        }
    }
    if (preview && a.type == ATT_IMAGE) {
        val big = rememberBitmap(attachmentFile(a), 1600)
        Dialog(onDismissRequest = { preview = false }) {
            Box(modifier = Modifier.fillMaxSize().clickable { preview = false }, contentAlignment = Alignment.Center) {
                if (big != null) Image(big.asImageBitmap(), contentDescription = a.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun AttachmentStrip(list: List<Attachment>, modifier: Modifier = Modifier, onRemove: ((Attachment) -> Unit)? = null) {
    if (list.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        list.forEach { a -> AttachmentThumb(a, onRemove = onRemove?.let { { it(a) } }) }
    }
}
