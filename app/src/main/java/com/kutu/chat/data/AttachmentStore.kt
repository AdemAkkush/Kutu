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
    private val extractor by lazy { DocumentExtractor(context) }

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
        storeText(bytes, name)
    }

    private fun storeText(bytes: ByteArray, name: String): Attachment {
        if (bytes.take(4096).any { it == 0.toByte() }) {
            throw AttachmentException("“$name” metin dosyası değil. Desteklenenler: resim, PDF, Excel, Word, PowerPoint, metin ve kod dosyaları.")
        }
        val truncated = bytes.size > MAX_TEXT_BYTES
        var text = String(bytes, 0, minOf(bytes.size, MAX_TEXT_BYTES), Charsets.UTF_8)
        if (text.count { it == REPLACEMENT_CHAR } > text.length / 20 + 3) {
            throw AttachmentException("“$name” okunamadı (UTF-8 metin değil).")
        }
        if (truncated) text += "\n\n[… dosya çok uzun olduğu için burada kesildi]"
        val id = UUID.randomUUID().toString() + ".txt"
        File(dir, id).writeText(text)
        return Attachment(ATT_TEXT, name, id, "text/plain", text.length.toLong(), "${DocumentExtractor.kilo(text.length)} karakter")
    }

    /**
     * PDF, Excel, Word, PowerPoint ya da düz metin dosyasını ekler. Metni cihazda çıkarır.
     * Taranmış (metinsiz) PDF'lerin ilk sayfaları resim olarak eklenir.
     */
    suspend fun importDocument(uri: Uri): List<Attachment> = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "dosya"
        val tmp = File(context.cacheDir, "import-" + UUID.randomUUID() + ".bin")
        try {
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOC_BYTES) throw AttachmentException("“$name” çok büyük (en fazla 50 MB).")
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw AttachmentException("Dosya açılamadı.")

            val kind = extractor.detect(tmp)
            if (kind == DocKind.TEXT) {
                val bytes = tmp.inputStream().use { it.readNBytes(MAX_TEXT_BYTES + 1) }
                return@withContext listOf(storeText(bytes, name))
            }

            val result = extractor.extract(tmp, kind)
            if (result.scanned) {
                val pages = renderPdfPages(tmp, name)
                if (pages.isEmpty()) throw AttachmentException("“$name” taranmış bir PDF (metin yok) ve sayfaları resme çevrilemedi.")
                return@withContext pages.map { it.copy(note = result.note + " · görsel olarak eklendi") }
            }
            if (result.text.isBlank()) throw AttachmentException("“$name” içinde okunabilir metin bulunamadı.")
            val id = UUID.randomUUID().toString() + ".txt"
            File(dir, id).writeText(result.text)
            listOf(Attachment(ATT_TEXT, name, id, mimeOf(kind), result.text.length.toLong(), result.note))
        } finally {
            tmp.delete()
        }
    }

    /** Taranmış PDF için ilk [MAX_SCAN_PAGES] sayfayı JPEG'e çevirir. */
    private fun renderPdfPages(file: File, name: String): List<Attachment> = runCatching {
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                (0 until minOf(renderer.pageCount, MAX_SCAN_PAGES)).map { i ->
                    renderer.openPage(i).use { page ->
                        val width = 1300
                        val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val id = UUID.randomUUID().toString() + ".jpg"
                        File(dir, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                        Attachment(ATT_IMAGE, "$name – sayfa ${i + 1}", id, "image/jpeg", File(dir, id).length())
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun mimeOf(kind: DocKind) = when (kind) {
        DocKind.PDF -> "application/pdf"
        DocKind.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        DocKind.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        DocKind.PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "text/plain"
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
        const val MAX_TEXT_BYTES = 1_000_000
        const val MAX_DOC_BYTES = 50L * 1024 * 1024
        const val MAX_SCAN_PAGES = 6
        private const val REPLACEMENT_CHAR = '\uFFFD'

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
