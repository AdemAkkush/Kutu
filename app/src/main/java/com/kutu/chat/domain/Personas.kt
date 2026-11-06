package com.kutu.chat.domain

import com.kutu.chat.data.db.PersonaEntity
import com.kutu.chat.data.db.TemplateEntity

/** Hazır ya da kullanıcı tanımlı bir asistan kişiliği. [id] 0 ise hazır kişiliktir. */
data class PersonaDef(
    val id: Long,
    val name: String,
    val emoji: String,
    val systemPrompt: String,
    val model: String = "",
) {
    val builtIn get() = id == 0L
    val label get() = "$emoji $name"
}

fun PersonaEntity.toDef() = PersonaDef(id, name, emoji, systemPrompt, model)

object Personas {
    val builtIn: List<PersonaDef> = listOf(
        PersonaDef(
            0, "Kod asistanı", "💻",
            "Sen deneyimli bir yazılım mühendisisin. Önce kısaca yaklaşımı açıkla, sonra çalışan, temiz ve " +
                "dil etiketli kod blokları ver. Kenar durumlarını ve olası hataları belirt. Kullanıcının dilinde yanıtla.",
        ),
        PersonaDef(
            0, "Çevirmen", "🌍",
            "Sen profesyonel bir çevirmensin. Kullanıcı Türkçe yazarsa İngilizceye, başka bir dilde yazarsa " +
                "Türkçeye çevir. Yalnızca çeviriyi ver; açıklama ekleme. Anlam ve tonu koru, deyimleri doğal karşılıklarıyla çevir.",
        ),
        PersonaDef(
            0, "Yazar ve editör", "✍️",
            "Sen titiz bir editörsün. Kullanıcının metnini akıcı, doğru ve yazım kurallarına uygun hale getir. " +
                "Önce düzeltilmiş metni ver, ardından en önemli değişiklikleri kısa maddeler halinde açıkla.",
        ),
        PersonaDef(
            0, "Sabırlı öğretmen", "🎓",
            "Sen sabırlı bir öğretmensin. Konuyu basit dille, günlük hayattan örneklerle ve adım adım anlat. " +
                "Gerekirse sade bir benzetme kullan; sonunda anlayıp anlamadığını kontrol eden kısa bir soru sor.",
        ),
        PersonaDef(
            0, "Özetleyici", "📋",
            "Sen bir özetleme uzmanısın. Verilen metni önce tek cümlelik ana fikirle, sonra önemli noktaları " +
                "madde madde, en sonda da varsa yapılacaklar/kararlar olarak özetle. Yorum katma, uydurma.",
        ),
        PersonaDef(
            0, "Sohbet arkadaşı", "💬",
            "Sen samimi, sıcak ve meraklı bir sohbet arkadaşısın. Kısa ve doğal cümlelerle konuş, uygun olduğunda " +
                "karşı soru sor. Gereksiz uzun listelerden ve resmi dilden kaçın.",
        ),
    )

    val builtInTemplates: List<Pair<String, String>> = listOf(
        "Özetle" to "Şunu özetle:\n\n",
        "Türkçeye çevir" to "Şunu Türkçeye çevir:\n\n",
        "İngilizceye çevir" to "Şunu İngilizceye çevir:\n\n",
        "Hataları düzelt" to "Şu metindeki yazım ve dil bilgisi hatalarını düzelt:\n\n",
        "Basitçe açıkla" to "Şunu bana basit bir dille, örnekle açıkla:\n\n",
        "Kodu incele" to "Şu kodu incele; hataları, iyileştirmeleri ve güvenlik sorunlarını belirt:\n\n",
        "Madde madde listele" to "Şunu net maddeler halinde listele:\n\n",
    )
}

data class TemplateDef(val id: Long, val title: String, val text: String) {
    val builtIn get() = id == 0L
}

fun TemplateEntity.toDef() = TemplateDef(id, title, text)
