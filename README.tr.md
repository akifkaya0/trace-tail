# Trace Tail

[English](README.md) | Türkçe

IDE'den çalıştırdığınız uygulamaların loglarını canlı ve trace'e göre gruplanmış bir görünümde
gösteren bir IntelliJ IDEA eklentisi. Loglar tek bir uzun akış olarak değil, ait oldukları isteğe
göre toplanmış olarak gelir: her istek tek bir satıra katlanır; açıldığında o isteğin log satırları
`trace.id`, `span.id` ve `parent.id` alanlarına göre iç içe gösterilir. Böylece birden fazla
uygulamadan geçen bir isteğin hangi adımlardan oluştuğunu, hangi adımın hangisini başlattığını ve
nerede hata verdiğini tek bakışta görebilirsiniz.

![Tree sekmesi: her istek için bir satır ve seçili, reddedilmiş isteğin sequence diyagramı](docs/images/tree-sequence.png)

## Özellikler

- **Her istek için bir satır**: satır sayısı, sonucu, süresi ve `WARN` ile `ERROR` satırlarının
  sayısıyla; açıldığında adımlarını, hangi adımın hangisini başlattığına göre iç içe gösterir
- **Bir sequence diyagramı**: seçili isteği uygulamalar, sınıflar, kuyruklar ve dış sistemler
  arasında çizer; diyagram Mermaid olarak kopyalanabilir
- **Tüm satırların bulunduğu bir konsol**: her istek kendi rengindedir; bir isteğin kimliğine
  tıklamak diğerlerini soluklaştırır
- **Her Run ve Debug penceresinde bir Trace Tail sekmesi**: yalnızca o uygulamanın katıldığı
  istekleri gösterir
- **Jump to Source**: bir log satırından onu yazan sınıfa gider
- **Uygulamada değişiklik gerekmez**: bir Java agent çalışma anında kendini Logback'e ya da
  Log4j2'ye ekler

## Kurulum

1. Bu projeyi IntelliJ IDEA'da açın ve `buildPlugin` Gradle görevini çalıştırın (Gradle araç
   penceresi → **trace-tail → Tasks → intellij platform → buildPlugin**).
2. **Settings → Plugins** içinde dişli simgesine tıklayın, **Install Plugin from Disk…** seçeneğini
   seçin ve `build/distributions/trace-tail-1.0.0.zip` dosyasını gösterin.
3. IDE'den bir Java uygulaması çalıştırın. Logları **View → Tool Windows → Trace Tail** altında
   görünür.

Agent'ın desteklediği log kurulumları için
[Uygulamanın ihtiyaç duyduğu](#uygulamanın-ihtiyaç-duyduğu) bölümüne bakın.

## Araç penceresi

Araç penceresinde aynı loglara üç farklı açıdan bakan üç sekme vardır:

| Sekme | Gösterdiği |
|---|---|
| Tree | Her istek için bir satır; açıldığında adımlarını gösterir. Yanında, seçili satırın JSON'u ve stack trace'i (Line) ile seçili isteğin sequence diyagramı (Sequence) yer alır |
| Flat | Tüm satırlar geliş sırasına göre, konsol biçiminde; stack trace satırları kaynak koda bağlantı verir |
| Raw | JSON satırları alındığı haliyle; agent'ın tam olarak ne gönderdiğini görmek için kullanışlıdır |

### Tree

Her istek bir satırdır: önce isteğin ilk satırı, ardından kaç satırı olduğu, sonucu ve süresi, kaç
`WARN` ve `ERROR` satırı olduğu ve adımlarından kaçının henüz `END` satırı gelmediği. Bir satır
açıldığında isteğin adımları, her biri onu başlatan adımın altında olacak şekilde görünür. Bir satır
oku, çift tıklama ya da Sol ve Sağ tuşlarıyla katlanır/açılır.

**F4** ya da bağlam menüsündeki **Jump to Source**, satırı yazan sınıfı (`log.logger`) açar.
Agent satır numarası göndermez: bunu bulmak her log çağrısında thread'in stack'ini gezmeyi
gerektirir ve her çağrıyı 8 ila 30 kat yavaşlatır.

### Sequence

Tree'nin yanındaki **Sequence** diyagramı seçili isteği, kimin kimi çağırdığını gösteren bir akış
olarak çizer. Her uygulama için, metotları `METHOD` adımları loglayan her sınıf için (kendi
uygulamasının yanında tutulur) ve satır yazmayan taraflar için bir yaşam çizgisi bulunur: kullanıcı,
zamanlayıcı, bir kuyruk ve bir `HTTP_OUT`'un çağırdığı dış sistemler. Her çağrı, dönüş ve not,
satırların yazıldığı sırayla bir satırdır; bir satırın üzerine gelindiğinde ipucu (tooltip) olarak o
log satırı görünür. **Copy as Mermaid** diyagramı metin olarak kopyalar; bu metin örneğin bir
dokümana ya da pull request açıklamasına yapıştırılabilir.

### Flat

**Flat** sekmesi her satırı Tree'deki gibi renklendirir; uyarı ve hata satırlarının, stack
trace'leriyle birlikte, arka planı da renklidir. Her satır, zamanından ve seviyesinden sonra
isteğinin kimliğinin ilk sekiz karakterini, o isteğe özgü bir renkte gösterir. Bu kimliğe tıklamak
o isteğe odaklanır: diğer isteklerin satırları soluklaşır. Kimliğe yeniden tıklanınca tüm satırlar
eski haline döner.

![Flat sekmesi: tüm satırlar geliş sırasına göre; bir hata ve stack trace'i renkli arka planda](docs/images/flat.png)

### Raw

**Raw** sekmesi her satırı agent'ın gönderdiği JSON olarak gösterir; böylece bir satırın hangi
alanları taşıdığını kontrol edebilirsiniz.

![Raw sekmesi: ECS JSON satırları alındığı haliyle](docs/images/raw.png)

### Run ve Debug pencereleri

Her Run veya Debug penceresi de, uygulamasının ilk satırı geldiğinde bir **Trace Tail** sekmesi
kazanır. Bu sekme yalnızca o uygulamanın katıldığı istekleri, diğer uygulamaların bu istekler için
yazdığı satırlarla birlikte gösterir; yani bir uygulamaya odaklanırken isteğin geri kalanını da
kaybetmezsiniz. Uygulama, agent'ın `service.name` olarak gönderdiği run configuration adıyla
eşleştirilir. Düz bir Run konsolu gibi sekmesi olmayan bir run penceresine sekme eklenmez.

### Araç çubuğu

- görünümü duraklatır; yeni satırlar kaybolmaz, devam ettirilene kadar IDE'de bekler
- görünümü temizler
- bir uygulama ya da tümü için belirli bir seviyenin altındaki satırları gizler (örneğin yalnızca
  `WARN` ve üstü). Bir isteğin ana adımı, yani başka hiçbir adımın başlatmadığı adım, `START` ve
  `END` satırlarını korur; böylece görünmeye devam eden bir istek ilk satırını da korur.
- **Scroll to the End**: en yeni satırları gösterir. Sonuna kaydırılmış bir görünüm yeni satırları
  izlemeye devam eder; yukarı kaydırmak bunu durdurur, böylece okunan satırlar yerinde kalır.
- tüm istekleri açar veya kapatır
- **Soft-Wrap**'i açıp kapatır: açıkken uzun satırlar ağaçta ve konsollarda bir sonraki satırda
  devam eder; kapalıyken her biri tek satırda kalır ve görünüm yatay kaydırılır. IDE bu seçimi
  hatırlar.

## Nasıl çalışır

Eklenti, açık her proje için yalnızca bu bilgisayardan erişilebilen (loopback) bir portu dinler. Bir
Java run configuration'ı (Application, Spring Boot, …) çalıştırıldığında veya debug edildiğinde,
eklenti kendi agent'ını JVM'e ekler:

```
-javaagent:<eklenti klasörü>/agent/trace-tail-agent.jar=<port>,<run configuration adı>
```

Agent, uygulamanın Logback ya da Log4j2'si başlayana kadar bekler, ardından kök logger'a bir
appender ekler. Appender her log olayını tek bir
[ECS](https://www.elastic.co/guide/en/ecs-logging/java/current/setup.html) JSON satırı olarak porta
gönderir. Bunların hepsi çalışma anında olur: uygulamaya bir bağımlılık eklemek ya da log
yapılandırmasını değiştirmek gerekmez.

## Uygulamanın ihtiyaç duyduğu

Uygulama Logback 1.2 veya sonrası ya da Log4j2 2.17 veya sonrası ile loglama yapmalı ve Java 8 veya
sonrasında çalışmalıdır. Agent'ın testleri onu Logback 1.2.13 ve 1.5.20, Log4j2 2.17.2 ve 2.24.3 ile
ve bunların her biri üzerinde Spring Boot 3.5 ile, Java 21'de çalıştırır.

Hangi olayların gönderileceğine, konsolda olduğu gibi uygulamanın kendi logger seviyeleri karar
verir; bir logger'ın seviyesinin elediği satır Trace Tail'e de gelmez. Yalnızca konsol appender'ına
konan bir filtre ise Trace Tail'i etkilemez. Örneğin Spring Boot'un `logging.threshold.console`
ayarıyla konsolda gizlenen satırlar Trace Tail'de yine görünür. Appender kök logger üzerinde durduğu
için additivity'si kapalı bir logger (olaylarını kök logger'a iletmeyen bir logger) hiçbir şey
göndermez.

Agent şunları kendi adlarıyla ayrı alanlar olarak yazar:

- thread context (MDC) girdileri
- SLF4J 2'nin anahtar-değer çiftleri, örneğin `log.atInfo().addKeyValue("phase", "START")`
- bir Log4j2 map message'ının girdileri; `message` girdisi mesajın kendisi olur

Bu yüzden isteği tanımlayan kimliklerin thread context'te `trace.id` ve `span.id` adlarıyla
bulunması gerekir. Spring Boot ile tracing kullanılıyorsa Micrometer bunları `traceId` ve `spanId`
adlarıyla koyar; görünüm bu adları da kabul eder.

Agent, Logback'e `logback.statusListenerClass` sistem özelliği üzerinden bağlanır. Uygulama bu
özelliği kendisi ayarlıyorsa agent'ın bağlanacak yeri kalmaz ve Logback satırları gönderilmez.

## Görünümün okuduğu alanlar

| Alan | Kullanım |
|---|---|
| `@timestamp`, `log.level`, `message`, `service.name` | Her satırda bulunur |
| `trace.id`, `span.id` | Satırları isteğe göre gruplar; `trace.id`'si olmayan bir satır yalnızca Flat ve Raw sekmelerinde görünür |
| `parent.id` | Bir adımı, onu başlatan adımın altına yerleştirir |
| `event.action` | Olayın adı, örneğin `HTTP_IN`; bu alanı olmayan bir satır onun yerine mesajını gösterir |
| `phase` | Bir adımın başladığını (`START`) veya bittiğini (`END`) belirtir |
| `user.id` | İsteği yapan kullanıcı |

Bir adımın satırı, sonucunu (`outcome`, `status`) ve `START` ile `END` arasında geçen süreyi
gösterir. Diğer tüm alanlar `anahtar=değer` olarak gösterilir.

Eklenti son 10.000 satırı bellekte tutar; böylece seviye filtresi değiştirildiğinde yeni filtre
yalnızca yeni gelen satırlara değil, bu satırlara da uygulanır.

Tree son 300 isteği, toplamda en fazla 100.000 satırla tutar; durum satırı kaç satır tuttuğunu
gösterir. Bir istek en fazla 5.000 satır tutar; bunu geçince en eski satırlarını onda birlik
parçalar halinde atar. Önce adımların içindeki
satırlar atılır, en yeni 500 satır hariç; böylece her adım `START` ve `END` satırlarını korur. Bu
yetmezse en eski bitmiş adımlar atılır. İsteğin satırı bundan sonra kaç satır atıldığını gösterir;
`WARN` ve `ERROR` sayıları atılan satırları da içerir.

Bir uygulamanın loglaması hiçbir zaman IDE'yi beklemez; IDE yavaşlasa ya da kapansa bile uygulama
yavaşlamaz. Agent kendi thread'inden gönderir ve en fazla 10.000 gönderilmemiş satır tutar; eklenti
her bağlantıyı kendi thread'inde okur ve en fazla 20.000 okunmamış satır tutar. İkisi de dolduğunda
en eski satırı atar. Uygulama çalışmaya devam ederken IDE kapanırsa, agent satırları herhangi bir
mesaj vermeden atar.

## Gereksinimler

- Paketle gelen Java eklentisiyle birlikte IntelliJ IDEA 2026.2 veya sonrası
- Derlemek için: yerel bir IntelliJ IDEA 2026.2 kurulumu. Yolu `gradle.properties` dosyasında
  (`platformLocalPath`) ayarlanır ve paketle gelen Java 25 çalışma ortamı toolchain olarak kullanılır.

## Kaynaktan çalıştırma

Projeyi IntelliJ IDEA'da açın ve `runIde` Gradle görevini çalıştırın. Eklentinin kurulu olduğu ayrı
bir deneme (sandbox) IDE'si başlar; görünüm **View → Tool Windows → Trace Tail** altındadır. Deneme
IDE'si, eklentiyi denemek için hazırlanmış iki küçük uygulamanın bulunduğu `sample` klasörünü açar;
ayrıntılar için [sample/README.md](sample/README.md) dosyasına bakın. Bu IDE'de bir Java uygulamasını
çalıştırdığınızda logları burada görmeye başlarsınız. Yukarıdaki ekran görüntüleri bu uygulamalarla
alınmıştır.

Agent, `agent` alt projesidir. Derleme, jar dosyasını eklentinin `agent` klasörüne, eklentinin class
path'inin dışına koyar; böylece agent yalnızca çalıştırılan uygulamanın JVM'ine yüklenir, IDE'ye
yüklenmez.

Eklentinin testleri, kök projenin `test` görevidir. `src/test/resources/requests` altındaki
istekler, agent'ın satırlarını gönderdiği biçimde yazılmıştır. Testler bunları görünümün modeline
okur; adımları, seviye filtresini ve Sequence sekmesinin Mermaid metnini kontrol eder. Bir başka test
de eklentinin portuna soketlerle satır gönderir.

Agent'ın testleri, `agent` alt projesinin `test` görevidir. Her test, küçük bir uygulamayı
[Uygulamanın ihtiyaç duyduğu](#uygulamanın-ihtiyaç-duyduğu) bölümündeki log kurulumlarından biriyle,
agent'la kendi JVM'inde başlatır ve portuna gelen satırları kontrol eder.
