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

**Ekler ve ses**
- **Resim ekleme:** galeriden (6'ya kadar) ya da kameradan; görsel anlayan modellere sorulabilir (`gemma-4-31b-it`, `llama-3.2-90b-vision`, `nemotron-3.5-super-vl`, `minimax`…). Resimler gönderilmeden önce 1536 px'e küçültülür, EXIF yönüne göre döndürülür
- **Metin/kod dosyası ekleme:** içeriği mesaja eklenir; ikili dosyalar reddedilir, çok uzun dosyalar kesilir
- **Sesle yazma:** mikrofon düğmesi (cihazın konuşma tanıması); **sesli okuma:** yanıtın altındaki hoparlör düğmesi (Markdown/kod blokları okunmaz)

**Belgeler (PDF, Excel, Word, PowerPoint)**
- + menüsünden **Belge** ekleyin: PDF, `.xlsx`, `.docx`, `.pptx`, CSV ve her türlü metin/kod dosyası. Metin cihazda çıkarılır (PDF için PDFBox, Office dosyaları için ZIP+XML); dosyanız bu amaçla ayrıca bir yere yüklenmez
- Excel: her sayfa CSV'ye çevrilir; tarih biçimli hücreler gerçek tarih olarak okunur, formül sonuçları, boş hücreler ve zengin metin korunur
- **Uzun belgeler:** belge bağlama sığmıyorsa her soruda, sorunuzla en ilgili bölümler (sayfa/slayt etiketleriyle) seçilir; böylece yüzlerce sayfalık bir PDF'de bile "87. sayfadaki fatura vadesi kaç gün?" diye sorabilirsiniz
- **Taranmış PDF** (metin katmanı yok): ilk 6 sayfa resim olarak eklenir, görsel anlayan bir model okur
- Yanıttaki bir **tabloyu** altındaki düğmeyle CSV/Excel olarak paylaşabilirsiniz
- Eski `.xls/.doc/.ppt` biçimleri desteklenmez (`.xlsx/.docx/.pptx` olarak kaydedin)

**Yapay zekâ ile seslendirme**
- Yanıtın altındaki hoparlör düğmesi, NVIDIA'nın **Chatterbox** (Türkçe dahil 23 dil) ve **Magpie** (12 dil, 10 konuşmacı) modelleriyle okur; parça parça sentezlenir, ilk parça çalarken sonrası üretilir
- **Metin uzunluğu:** Chatterbox sunucuda tek istekte en fazla 500 karakter kabul eder (ölçüldü; Türkçe konuşan başka NVIDIA modeli yok). Uygulama uzun metni cümle sınırlarında 470 karakterlik parçalara böler, bu yüzden okunabilecek uzunluk sınırsızdır. Magpie'de pratikte sınır yoktur (6000 karakter denendi), 2500 karakterlik parçalar kullanılır
- **Akışlı sentez:** ses üretildikçe çalınır; ilk ses ~0,5 saniyede başlar, sonraki parçalar önceden üretilir
- **Hız ve ton:** Ayarlar'da konuşma hızı (0,5×–2×) ve ses tonu (daha kalın / daha ince) iki motor için de çalışır. Hız değişince ses tonu bozulmaz: akışlı WSOLA zaman ölçekleme ve yeniden örnekleme uygulama içinde yapılır (sunucunun SSML hız desteği güvenilmez olduğu için). Gerçek Türkçe kayıtta doğrulandı: 1,5× hızda süre 3,7→2,5 sn, perde aynı, Whisper cümleyi aynen çözüyor
- **Türkçede tek ses:** NVIDIA'nın Türkçe modeli (Chatterbox) yalnızca tek erkek ses sunar; konuşmacı seçimi ve ses klonlama bu servisde yoktur. Magpie konuşmacıları yalnızca Magpie'nin okuduğu dillerde (İngilizce vb.) etkilidir. Türkçede farklı ses için "Türkçeyi cihaz sesiyle oku" açılır ve cihazın Türkçe sesleri arasından seçim yapılır (dinleme düğmesiyle)
- Dil metinden otomatik algılanır; Türkçe → Chatterbox, İngilizce vb. → Magpie. Hata olursa otomatik cihaz sesine geçer. Ayarlar'dan motor, model ve konuşmacı seçilir, "Sesi dene" düğmesi vardır
- Sesler, NVIDIA'nın Riva gRPC servisinden (`grpc.nvcf.nvidia.com`) gelir; bu sade istemci OkHttp üzerinde, ağır gRPC kütüphaneleri olmadan yazıldı

**Kişilikler ve şablonlar**
- 6 hazır kişilik (kod asistanı, çevirmen, editör, öğretmen, özetleyici, sohbet arkadaşı) + kendi kişiliklerin (ad, simge, sistem istemi, isteğe bağlı model)
- Yeni sohbet ekranından tek dokunuşla kişilikle başla; kişilik sohbet başlığında görünür
- ✨ düğmesiyle hızlı komut şablonları (hazır + kendi şablonların)

**Yönetim**
- Yeniden üretince eski yanıt silinmez: **‹ 2/3 ›** ile sürümler arasında gez
- Tek mesajı silme, sohbet içinde arama (sonuçlar arasında gezinme)
- **Yedekleme:** tüm sohbetler, ekler, hafıza, kişilikler ve şablonlar tek ZIP dosyasına aktarılır / geri yüklenir (tekrar içe aktarmak kopya oluşturmaz; API anahtarı yedeğe girmez)

**Arka planda kesintisiz çalışma**
- Yanıt hazırlanırken başka uygulamaya geçebilirsiniz: üretim sürerken bildirimli bir **ön plan servisi**, kısmi uyku kilidi ve Wi-Fi kilidi uygulamayı canlı tutar (bitince kapanır; bildirimdeki "Durdur" ile iptal edilebilir). Android 13+ için ilk gönderimde bildirim izni istenir; reddedilse de üretim sürer
- Bağlantı yine de koparsa (ağ değişimi, proxy, sistem) **otomatik yeniden denenir** (3 kez, giderek artan bekleyişle): yanıt hiç başlamadıysa baştan, yazılmışsa **kaldığı yerden** sürdürülür; yarım yanıt her denemede kaydedilir
- Akış `[DONE]`/`finish_reason` olmadan kapanırsa artık "tamamlandı" sayılmaz, kopma olarak ele alınır (önceden yarım yanıt sessizce kaydedilebilirdi)
- Tüm denemeler tükenirse ya da süreç sistem tarafından öldürülürse yarım yanıt korunur ve **"Devam et"** düğmesi çıkar
- Ayarlar'da pil kısıtlaması durumu görünür; agresif pil yönetimli telefonlarda Kutu'yu muaf tutmak için sistem ekranına kısayol vardır

**Model seçimi — yeteneğe göre gruplama**
- API'den canlı model listesi (`/v1/models`) + NVIDIA'nın herkese açık kataloğu (build.nvidia.com) birleştirilir
- Filtre çipleri: ⭐ Favoriler, 👁 Görsel, 🧠 Düşünen, 💻 Kod, 🌍 Çeviri, 📄 Belge, ⚡ Hızlı, 🛠 Ajan; her modelde rozetler görünür, popüler olanlar üstte sıralanır
- **Çalışmayan modeller ayıklanır:** `/v1/models` listesindeki modellerin yaklaşık yarısı gerçekte kaldırılmış durumda (404/410). Katalogda olmayan, hata veren (404/410) ve kaldırılma tarihi geçmiş modeller seçicide **hiç gösterilmez** (gerçek hesapta 14/14 çalışan model katalogda, 42/42 kaldırılmış model katalogda yok); kaldırılma tarihi yaklaşanlar uyarıyla görünür. Seçili model çalışmıyorsa listenin başında uyarıyla gösterilir. Hata yüzünden gizlenenler Ayarlar'dan sıfırlanabilir
- Resim eklenmişken seçili model görsel anlamıyorsa, en iyi görsel model önerilir; uygulama hangi modelin resim kabul ettiğini kullandıkça kendisi öğrenir
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
./gradlew :app:testDebugUnitTest      # 102 test: API/SSE, bağlam, hafıza, Markdown, ekler, belgeler, yetenekler, seslendirme, yedekleme, veritabanı göçü, uçtan uca (Robolectric)
```

Ya da projeyi doğrudan Android Studio ile açın.

### İmzalı release APK
Telefona kurulabilen sürüm için bir imza anahtarı gerekir. Kök dizine `keystore.properties` koyun (repoya girmez):

```properties
storeFile=kutu-release.jks
storePassword=...
keyAlias=kutu
keyPassword=...
```
Anahtar yoksa `keytool -genkeypair -keystore kutu-release.jks -alias kutu -keyalg RSA -keysize 2048 -validity 10000` ile üretin. Sonra:

```bash
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk (R8 ile küçültülmüş, imzalı)
```
Anahtar yoksa derleme yine çalışır ama APK imzasız çıkar. **Anahtarı ve şifreleri kaybetmeyin:** güncellemeler aynı anahtarla imzalanmak zorundadır.


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
