package com.kutu.chat.domain

import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.STATUS_DONE
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Bir asistan yanıtının (yeniden üretimle oluşan) tek sürümü. */
@Serializable
data class Version(
    val content: String = "",
    val reasoning: String = "",
    val model: String = "",
    val error: String = "",
    val status: Int = STATUS_DONE,
)

/**
 * Mesajın satırındaki `content/reasoning/…` alanları her zaman *etkin* sürümü tutar.
 * `versions` listesindeki etkin sürümün kaydı bayat olabilir; sürüm değiştirirken güncellenir.
 */
object Versions {
    fun parse(json: String): List<Version> =
        if (json.isBlank()) emptyList()
        else runCatching { NvidiaApi.JSON.decodeFromString<List<Version>>(json) }.getOrDefault(emptyList())

    fun encode(list: List<Version>): String = if (list.size <= 1) "" else NvidiaApi.JSON.encodeToString(list)

    fun snapshot(m: MessageEntity) = Version(m.content, m.reasoning, m.model, m.error, m.status)

    fun apply(m: MessageEntity, v: Version) =
        m.copy(content = v.content, reasoning = v.reasoning, model = v.model, error = v.error, status = v.status)

    fun count(m: MessageEntity): Int = maxOf(1, parse(m.versions).size)
}
