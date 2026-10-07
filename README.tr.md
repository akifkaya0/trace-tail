# Trace Tail

[English](README.md) | Türkçe

IDE'den çalıştırdığınız uygulamaların loglarını canlı ve trace'e göre gruplanmış bir görünümde
gösteren bir IntelliJ IDEA eklentisi. Loglar tek bir uzun akış olarak değil, ait oldukları isteğe
göre toplanmış olarak gelir: her istek tek bir satıra katlanır; açıldığında o isteğin log satırları
`trace.id`, `span.id` ve `parent.id` alanlarına göre iç içe gösterilir. Böylece birden fazla
uygulamadan geçen bir isteğin hangi adımlardan oluştuğunu, hangi adımın hangisini başlattığını ve
nerede hata verdiğini tek bakışta görebilirsiniz.

**Durum:** eklenti, başlattığı uygulamaların loglarını alır ve bunları IDE'nin kendi bileşenleriyle
oluşturulmuş Trace Tail araç penceresinde gösterir.

## Araç penceresi

Araç penceresinde aynı loglara üç farklı açıdan bakan üç sekme vardır:

| Sekme | Gösterdiği |
|---|---|
| Tree | Her istek için bir satır; açıldığında adımlarını gösterir. Yanında, seçili satırın JSON'u ve stack trace'i (Line) ile seçili isteğin sequence diyagramı (Sequence) yer alır |
| Flat | Tüm satırlar geliş sırasına göre, konsol biçiminde; stack trace satırları kaynak koda bağlantı verir |
| Raw | JSON satırları alındığı haliyle; agent'ın tam olarak ne gönderdiğini görmek için kullanışlıdır |

**Flat** sekmesi her satırı Tree'deki gibi renklendirir; uyarı ve hata satırlarının, stack
trace'leriyle birlikte, arka planı da renklidir. Her satır, zamanından ve seviyesinden sonra
isteğinin kimliğinin ilk sekiz karakterini, o isteğe özgü bir renkte gösterir. Bu kimliğe tıklamak
o isteğe odaklanır: diğer isteklerin satırları soluklaşır. Kimliğe yeniden tıklanınca tüm satırlar
eski haline döner.

**Sequence** diyagramı seçili isteği, kimin kimi çağırdığını gösteren bir akış olarak çizer. Her
uygulama için, metotları `METHOD` adımları loglayan her sınıf için (kendi uygulamasının yanında
tutulur) ve satır yazmayan taraflar için bir yaşam çizgisi bulunur: kullanıcı, zamanlayıcı, bir
kuyruk ve bir `HTTP_OUT`'un çağırdığı dış sistemler. Her çağrı, dönüş ve not, satırların yazıldığı
sırayla bir satırdır; bir satırın üzerine gelindiğinde ipucu (tooltip) olarak o log satırı görünür.
**Copy as Mermaid** diyagramı metin olarak kopyalar; bu metin örneğin bir dokümana ya da pull
request açıklamasına yapıştırılabilir.

Her Run veya Debug penceresi de, uygulamasının ilk satırı geldiğinde bir **Trace Tail** sekmesi
kazanır. Bu sekme yalnızca o uygulamanın katıldığı istekleri, diğer uygulamaların bu istekler için
yazdığı satırlarla birlikte gösterir; yani bir uygulamaya odaklanırken isteğin geri kalanını da
kaybetmezsiniz. Uygulama, agent'ın `service.name` olarak gönderdiği run configuration adıyla
eşleştirilir. Düz bir Run konsolu gibi sekmesi olmayan bir run penceresine sekme eklenmez.

Tree sekmelerinde **F4** ya da bağlam menüsündeki **Jump to Source**, satırı yazan sınıfı
(`log.logger`) açar; satırda `log.origin.file.line` varsa doğrudan o satıra gider. Log4j2'nin
asenkron logger'ları satır numarası göndermediği için bu durumda yalnızca sınıf açılır.

Araç çubuğundaki düğmeler şunları yapar:

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

Tree'de bir satır oku, çift tıklama ya da Sol ve Sağ tuşlarıyla katlanır/açılır.

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
sonrasında çalışmalıdır. Agent; Logback 1.2.13 ve 1.5.20, Log4j2 2.17.2 ve 2.24.3 ile ve bunların her
biri üzerinde Spring Boot 3.5 ile denenmiştir.

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

Bir uygulamanın loglaması hiçbir zaman IDE'yi beklemez; IDE yavaşlasa ya da kapansa bile uygulama
yavaşlamaz. Agent kendi thread'inden gönderir ve en fazla 10.000 gönderilmemiş satır tutar; eklenti
her bağlantıyı kendi thread'inde okur ve en fazla 20.000 okunmamış satır tutar. İkisi de dolduğunda
en eski satırı atar. Uygulama çalışmaya devam ederken IDE kapanırsa, agent satırları herhangi bir
mesaj vermeden atar.

## Gereksinimler

- Paketle gelen Java eklentisiyle birlikte IntelliJ IDEA 2026.2 veya sonrası
- Derlemek için: yerel bir IntelliJ IDEA 2026.2 kurulumu. Yolu `gradle.properties` dosyasında
  (`platformLocalPath`) ayarlanır ve paketle gelen Java 25 çalışma ortamı toolchain olarak kullanılır.

## Çalıştırma

Projeyi IntelliJ IDEA'da açın ve `runIde` Gradle görevini çalıştırın. Eklentinin kurulu olduğu ayrı
bir deneme (sandbox) IDE'si başlar; görünüm **View → Tool Windows → Trace Tail** altındadır. Deneme
IDE'si, eklentiyi denemek için hazırlanmış iki küçük uygulamanın bulunduğu `sample` klasörünü açar;
ayrıntılar için [sample/README.md](sample/README.md) dosyasına bakın. Bu IDE'de bir Java uygulamasını
çalıştırdığınızda logları burada görmeye başlarsınız.

Agent, `agent` alt projesidir. Derleme, jar dosyasını eklentinin `agent` klasörüne, eklentinin class
path'inin dışına koyar; böylece agent yalnızca çalıştırılan uygulamanın JVM'ine yüklenir, IDE'ye
yüklenmez.
