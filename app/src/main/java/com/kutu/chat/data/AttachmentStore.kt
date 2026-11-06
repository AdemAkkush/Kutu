package com.kutu.chat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.kutu.chat.domain.ATT_IMAGE
import com.kutu.chat.domain.ATT_TEXT
import com.kutu.chat.domain.Attachment
import com.kutu.chat.domain.AttachmentSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AttachmentException(message: String) : Exception(message)

/** Eklenen resim ve metin dosyalarını uygulamaya özel klasörde saklar. */
class AttachmentStore(private val context: Context) : AttachmentSource {
    val dir: File get() = File(context.filesDir, "attachments").apply { mkdirs() }

    fun file(a: Attachment) = File(dir, a.file)

    /** Resmi okur, EXIF yönüne göre döndürür, uzun kenarı [MAX_IMAGE_SIDE] pikselle sınırlayıp JPEG olarak saklar. */
    suspend fun importImage(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(uri) ?: "resim.jpg"
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // inJustDecodeBounds=true iken decodeStream null döner; yalnızca akışın açılıp açılmadığına bakılır.
        val opened = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true }
        if (opened != true) throw AttachmentException("Resim açılamadı.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AttachmentException("Bu dosya bir resim değil.")

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_SIDE)
        }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw AttachmentException("Resim çözülemedi.")

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        bitmap = applyOrientation(bitmap, orientation)

        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > MAX_IMAGE_SIDE) {
            val scale = MAX_IMAGE_SIDE.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }

        val id = UUID.randomUUID().toString() + ".jpg"
        val target = File(dir, id)
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        Attachment(ATT_IMAGE, name, id, "image/jpeg", target.length())
    }

    /** Metin/kod dosyasını okur. İkili dosyaları reddeder, çok uzunsa keser. */
    suspend fun importText(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "dosya.txt"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_TEXT_BYTES + 1) }
            ?: throw AttachmentException("Dosya açılamadı.")
        if (bytes.take(4096).any { it == 0.toByte() }) {
            throw AttachmentException("“$name” metin dosyası değil. Yalnızca resim, metin ve kod dosyaları eklenebilir.")
        }
        val truncated = bytes.size > MAX_TEXT_BYTES
        var text = String(bytes, 0, minOf(bytes.size, MAX_TEXT_BYTES), Charsets.UTF_8)
        if (text.count { it == '�' } > text.length / 20 + 3) {
            throw AttachmentException("“$name” okunamadı (UTF-8 metin değil).")
        }
        if (truncated) text += "\n\n[… dosya çok uzun olduğu için burada kesildi]"
        val id = UUID.randomUUID().toString() + ".txt"
        File(dir, id).writeText(text)
        Attachment(ATT_TEXT, name, id, "text/plain", text.length.toLong())
    }

    override fun imageDataUrl(a: Attachment): String? = runCatching {
        "data:image/jpeg;base64," + Base64.encodeToString(file(a).readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    override fun text(a: Attachment): String? = runCatching { file(a).readText() }.getOrNull()

    fun delete(list: List<Attachment>) = list.forEach { file(it).delete() }

    /**
     * Hiçbir mesajın kullanmadığı (yarım kalmış / silinmiş) ek dosyalarını temizler.
     * Yeni eklenmiş / yeni içe aktarılmış dosyalara dokunmamak için yalnızca [minAgeMs]'den eski olanlar silinir.
     */
    fun sweep(referenced: Set<String>, minAgeMs: Long = 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - minAgeMs
        dir.listFiles()?.forEach { if (it.name !in referenced && it.lastModified() <= cutoff) it.delete() }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    companion object {
        const val MAX_IMAGE_SIDE = 1536
        const val JPEG_QUALITY = 85
        const val MAX_TEXT_BYTES = 120_000

        fun sampleSize(w: Int, h: Int, target: Int): Int {
            var s = 1
            while (maxOf(w, h) / (s * 2) >= target) s *= 2
            return s
        }

        private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                else -> return src
            }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
    }
}
