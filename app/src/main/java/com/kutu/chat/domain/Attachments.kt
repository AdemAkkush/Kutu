package com.kutu.chat.domain

import com.kutu.chat.data.api.NvidiaApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

const val ATT_IMAGE = "image"
const val ATT_TEXT = "text"

/** Bir mesaja eklenmiş dosya. [file], uygulamanın ek klasöründeki dosya adıdır. */
@Serializable
data class Attachment(
    val type: String,
    val name: String,
    val file: String,
    val mime: String = "",
    val size: Long = 0,
)

object Attachments {
    fun parse(json: String): List<Attachment> =
        if (json.isBlank()) emptyList()
        else runCatching { NvidiaApi.JSON.decodeFromString<List<Attachment>>(json) }.getOrDefault(emptyList())

    fun encode(list: List<Attachment>): String =
        if (list.isEmpty()) "" else NvidiaApi.JSON.encodeToString(list)
}

/** Ek içeriğine erişim (dosya sistemi); testlerde sahtesi kullanılır. */
interface AttachmentSource {
    /** `data:image/jpeg;base64,…` ya da dosya okunamazsa null. */
    fun imageDataUrl(a: Attachment): String?
    fun text(a: Attachment): String?
}
