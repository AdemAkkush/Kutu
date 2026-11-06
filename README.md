# Kutu — NVIDIA Build için Android sohbet uygulaması

NVIDIA Build'deki (build.nvidia.com) modelleri kullanarak sohbet etmenizi sağlayan, hafızalı bir Android uygulaması.
Kotlin + Jetpack Compose (Material 3) ile yazıldı; `https://integrate.api.nvidia.com/v1` OpenAI uyumlu uç noktasını kullanır.

## Özellikler

**Sohbet**
- Akışlı (canlı yazılan) yanıtlar, **Durdur** düğmesi
- Markdown: başlıklar, listeler, tablolar, alıntılar, kod blokları (dil etiketi + kopyala düğmesi), bağlantılar
- Yanıtı yeniden üretme, kendi mesajını **düzenleyip yeniden gönderme**, kopyalama, paylaşma
- Düşünen modeller (`reasoning_content` ya da `<think>…</think>`) için açılır/kapanır "Düşünce süreci" bölümü
- Sohbet başına model seçimi, sohbet başına sistem istemi
- Hata durumunda anlaşılır mesajlar (401/404/429…) ve "Tekrar dene"
- Bir sohbeti Markdown olarak paylaşma

**Model seçimi**
- API'den canlı model listesi (`/v1/models`), arama, favoriler
- Listede olmayan bir modeli kimliğini yazarak doğrudan kullanabilirsiniz

**Hafıza ve süreklilik**
- **Sohbet geçmişi** Room (SQLite) veritabanında saklanır; uygulama açıldığında kaldığınız sohbet açılır
- Kenar çekmecesinde sohbet listesi: arama (başlık + mesaj içeriği), sabitleme, yeniden adlandırma, silme
- **Uzun süreli hafıza:** sohbetlerde kendinizle ilgili paylaştıklarınız (ad, meslek, tercihler, projeler…) arka planda çıkarılır ve *tüm* yeni sohbetlerde modele verilir. "Şunu hatırla: …" ya da "… bilgisini unut" demeniz yeterli. Hafıza ekranında her kaydı görebilir, düzenleyebilir, kapatabilir, silebilirsiniz
- **Sınırsız sohbet uzunluğu:** bağlam bütçesi aşılınca eski mesajlar otomatik özetlenir; özet + son mesajlar modele gönderilir
- Otomatik sohbet başlığı

**Gizlilik**
- API anahtarı cihazda Android Keystore destekli şifreli depoda saklanır
- Veriler yalnızca cihazınızda ve NVIDIA API'sinde bulunur; bulut yedeği kapalıdır

## Kurulum

1. [build.nvidia.com](https://build.nvidia.com/settings/api-keys) adresinden ücretsiz bir API anahtarı (`nvapi-…`) oluşturun.
2. Uygulamayı derleyip yükleyin (aşağıya bakın).
3. **Ayarlar → API anahtarı** alanına yapıştırın ve **Bağlantıyı sına**'ya basın.

## Derleme

Gereksinimler: JDK 17+ ve Android SDK (platform 35).

```bash
echo "sdk.dir=/yol/android-sdk" > local.properties   # Android Studio bunu kendisi oluşturur
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 41 test: API/SSE, bağlam, hafıza, Markdown, uçtan uca (Robolectric)
```

Ya da projeyi doğrudan Android Studio ile açın.

## Mimari

```
data/db        Room: sohbetler, mesajlar, hafıza kayıtları
data/api       OkHttp + kotlinx.serialization ile NVIDIA istemcisi (SSE akışı dahil)
data/          Ayarlar (şifreli anahtar deposu), model listesi önbelleği
domain/        ChatEngine (üretim, başlık, özet, hafıza çıkarımı), ContextBuilder, MemoryLogic
ui/            Compose ekranları: sohbet, çekmece, hafıza, ayarlar; Markdown oluşturucu
```

`ChatEngine` uygulama kapsamında çalışır: ekranı değiştirseniz ya da sohbetten çıksanız da yanıt üretmeye devam eder.

### Bağlam nasıl kurulur?

Her istekte modele şunlar gider:

1. Sistem istemi (genel ya da sohbete özel) + tarih/saat
2. Etkin hafıza kayıtları
3. Bu sohbetin önceki bölümünün özeti (varsa)
4. Özetlenmemiş son mesajlar (bütçeye sığacak kadarı)

Hafıza çıkarımı, başlık üretimi ve özetleme için **Arka plan modeli** kullanılır (varsayılan: `meta/llama-3.1-8b-instruct`).
Bunlar ek API çağrılarıdır; istemezseniz Ayarlar'dan "Sohbetlerden otomatik öğren"i kapatabilirsiniz.
