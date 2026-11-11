# Xiaomi RC Bridge

**Xiaomi BLE kumandaları (Mi TV Stick / Mi Box kumandası) için Android TV köprüsü — root gerektirmez.**

Xiaomi'nin Bluetooth kumandası bazı Android TV kutularında (ör. Skyworth **TVPLUS BOX**, Android 14) eşleşiyor ama **hiçbir tuşu çalışmıyor**. Bu uygulama kumandayı bulur, eşler ve tuş basışlarını sisteme aktarır: yön tuşları, OK, Home, Geri, Menü, ses ±, güç, Netflix ve Prime Video tuşları çalışır hale gelir.

> 🇬🇧 *English summary at the bottom.*

---

## İçindekiler

- [Sorunun kaynağı](#sorunun-kaynağı)
- [Nasıl çalışıyor](#nasıl-çalışıyor)
- [Gereksinimler](#gereksinimler)
- [Kurulum](#kurulum)
- [Kumandayı eşleme](#kumandayı-eşleme)
- [Tuş eşlemesi](#tuş-eşlemesi)
- [Tuşları özelleştirme](#tuşları-özelleştirme)
- [Açılışta otomatik başlama](#açılışta-otomatik-başlama)
- [Güç tuşuna basılı tutunca kapatma menüsü](#güç-tuşuna-basılı-tutunca-kapatma-menüsü)
- [Sorun giderme](#sorun-giderme)
- [Bilinen kısıtlamalar](#bilinen-kısıtlamalar)
- [Kaynaktan derleme](#kaynaktan-derleme)
- [Teknik ayrıntılar](#teknik-ayrıntılar)
- [English summary](#english-summary)

---

## Sorunun kaynağı

HCI snoop kaydıyla doğrulandı:

- Kumanda (Realtek çipli, VID `0x2717` / PID `0x32b9`) HID servisinde **28 adet Report karakteristiği** sunuyor.
- Android'in BLE HID (HOGP) sürücüsü cihaz başına en fazla **20 rapor** takip ediyor (`BTA_HH_LE_RPT_MAX`).
- Tuşların geldiği **Consumer Control giriş raporu (Report ID 1, handle `0x86`)** listenin sonunda kaldığı için **hiç abone olunmuyor** → kumanda tuş gönderiyor, TV dinlemiyor.

Ayarlar menüsünden eşleme de çoğu zaman başarısız oluyor; bu yüzden uygulama kendi eşleme aracını da içeriyor.

## Nasıl çalışıyor

```
 ┌──────────────┐   yerel adb (127.0.0.1:5555)    ┌──────────────────────────────┐
 │ Xiaomi RC    │ ──────────────────────────────▶ │ Daemon (app_process, shell)  │
 │ Bridge (app) │   supervisor script başlatır    │  • GATT ile kumandaya bağlan  │
 └──────────────┘                                 │  • Report ID 1'e abone ol     │
        ▲ açılışta (LOCKED_BOOT_COMPLETED)        │  • bit → KeyEvent enjekte et  │
        │                                         └──────────────┬───────────────┘
   BootReceiver                                                  │ BLE notify
                                                         ┌───────┴───────┐
                                                         │  Xiaomi RC    │
                                                         └───────────────┘
```

1. Uygulama, TV'nin **kendi adb sunucusuna** (ağ hata ayıklama, `127.0.0.1:5555`) gömülü küçük bir adb istemcisiyle bağlanır.
2. Bu yolla APK'nın içindeki `Daemon` sınıfını `app_process` ile **shell kullanıcısı** olarak çalıştırır. Shell kullanıcısında `BLUETOOTH_PRIVILEGED` ve `INJECT_EVENTS` yetkileri vardır — root gerekmez.
3. Daemon kumandaya kendi GATT bağlantısını açar, sistemin atladığı Consumer Control raporuna abone olur ve gelen 24 bitlik tuş bitmap'ini Android tuş olaylarına çevirip sisteme enjekte eder (basılı tutunca otomatik tekrar dahil).
4. Bir supervisor betiği (`/data/local/tmp/xrcd.sh`) daemon çökerse 3 sn içinde yeniden başlatır.

## Gereksinimler

- Android TV / Google TV, **Android 12+** (API 31+). Android 14 TVPLUS BOX üzerinde test edildi.
- **Geliştirici seçenekleri** açık ve **ağ üzerinden hata ayıklama (ADB over network, port 5555)** etkin.
  Birçok kutuda bu varsayılan olarak açıktır. Uygulama TV'nin kendi adb'sini kullandığı için **geliştirici seçeneklerini kapatmayın** — kapatırsanız köprü başlayamaz.
- Xiaomi BLE kumanda (Mi TV Stick 1. nesil kumandası ile test edildi; adında "Xiaomi", "Mi RC" veya "Mi Remote" geçen kumandaları tanır).

## Kurulum

1. [Releases](../../releases) sayfasından son `xiaomi-rc-bridge-vX.Y.apk` dosyasını indirin.
2. TV'ye kurun — bilgisayardan:
   ```sh
   adb connect <TV-IP>:5555
   adb install -r xiaomi-rc-bridge-v0.4.apk
   ```
   ya da USB bellek / "Send Files to TV" gibi bir uygulamayla.
3. TV'de **Xiaomi RC Bridge** uygulamasını açın.
4. İlk açılışta TV ekranında **"USB hata ayıklamasına izin verilsin mi?"** penceresi çıkar → **"Bu bilgisayardan her zaman izin ver"** kutusunu işaretleyip **İzin ver**'e basın, sonra **Yenile**.
5. Durum satırı yeşil **"● Köprü çalışıyor"** olmalı.

> ⚠️ Uygulamayı "Durmaya zorla" ile kapatırsanız Android onu açılışta başlatmaz. Böyle bir durumda uygulamayı bir kez açmanız yeterli.

## Kumandayı eşleme

Bluetooth Scanner gibi ayrı bir uygulamaya gerek yok:

1. Uygulamada **Kumandayı eşle**'ye basın.
2. Kumandayı TV'ye yaklaştırıp **Home + Menü (☰)** tuşlarına birlikte basılı tutun; ışık yanıp sönmeye başlayınca bırakın.
   (Olmazsa **Home + Geri** deneyin.)
3. Uygulama 60 sn boyunca BLE taraması yapar, kumandayı bulunca varsa eski (bozuk) eşlemeyi siler, LE üzerinden yeniden eşler ve köprüyü yeniden başlatır.
4. **"✓ Kumanda eşlendi"** yazısını görünce tuşlar çalışır.

Kayıt: `/data/local/tmp/xrc-pair.log`

Elle (bilgisayardan) çalıştırmak için:

```sh
adb shell 'CLASSPATH=$(pm path com.kaan.xrc | cut -d: -f2) app_process / com.kaan.xrc.Pairer 60'
# belirli bir MAC adresiyle:
adb shell 'CLASSPATH=$(pm path com.kaan.xrc | cut -d: -f2) app_process / com.kaan.xrc.Pairer 60 AA:BB:CC:DD:EE:FF'
```

## Tuş eşlemesi

Kumandanın Report ID 1 paketi 24 bitlik bir bitmap'tir. Varsayılan eşleme:

| Bit | Kumanda tuşu | Android karşılığı |
|----:|--------------|-------------------|
| 0 | OK | `DPAD_CENTER` |
| 1–4 | Yukarı / Aşağı / Sol / Sağ | `DPAD_UP/DOWN/LEFT/RIGHT` (tekrar eder) |
| 5 | Güç | `POWER` (basılı tut → güç menüsü) |
| 6 | Mikrofon | `SEARCH` (arama ekranı) |
| 7 / 8 | Ses + / − | `VOLUME_UP` / `VOLUME_DOWN` (tekrar eder) |
| 9 | Menü (☰) | `MENU` |
| 10 | Netflix | `com.netflix.ninja` uygulamasını açar |
| 13 | Prime Video | `com.amazon.amazonvideo.livingroom` uygulamasını açar |
| 17 | Sesli komut | `SEARCH` |
| 18 | Home | `HOME` |
| 19 | Geri | `BACK` |

Yön ve ses tuşları basılı tutulunca 400 ms sonra 80 ms aralıkla tekrar eder.

## Tuşları özelleştirme

`/data/local/tmp/xrc-keymap.txt` dosyası oluşturun (her satır `bit=KEYCODE` veya `bit=app:paket.adı`):

```text
# Menü tuşu ayarları açsın
9=SETTINGS
# Netflix tuşu YouTube açsın
10=app:com.google.android.youtube.tv
# Prime tuşu oynat/duraklat olsun
13=MEDIA_PLAY_PAUSE
```

```sh
adb push xrc-keymap.txt /data/local/tmp/
```

Sonra uygulamada **Başlat / Yeniden başlat**. Tuş adları Android'in `KeyEvent.KEYCODE_*` adlarıdır (`KEYCODE_` öneki isteğe bağlı).

## Açılışta otomatik başlama

- Uygulama **direct-boot aware**; `LOCKED_BOOT_COMPLETED` (açılıştan ~25 sn sonra) geldiği anda adb hazırsa daemon'u başlatır, değilse 1,5 sn aralıkla tekrar dener, olmazsa JobScheduler ile yeniden dener. `BOOT_COMPLETED` ve uygulama güncellemesinde de tetiklenir (komut idempotent).
- adb anahtarı cihaz-korumalı depolamada tutulur, böylece kilit açılmadan da kullanılabilir.
- Consumer raporunun handle'ı `/data/local/tmp/xrc-handle` dosyasında önbelleğe alınır; bağlantı kurulduktan ~10 ms sonra tuşlar hazırdır.

## Güç tuşuna basılı tutunca kapatma menüsü

Bazı kutularda güç tuşuna uzun basmak cihazı doğrudan kapatır. Yeniden başlat / kapat menüsünü getirmek için:

```sh
adb shell settings put global power_button_long_press 1
```

## Sorun giderme

| Belirti | Çözüm |
|---|---|
| "adb'ye bağlanılamadı (127.0.0.1:5555)" | Geliştirici seçeneklerinde ağ üzerinden hata ayıklamayı açın. Bazı cihazlarda bilgisayardan bir kez `adb tcpip 5555` gerekir. |
| "Hata ayıklamaya izin verilsin mi" penceresi çıkmıyor | Geliştirici seçeneklerinde "USB hata ayıklama yetkilerini iptal et" yapıp uygulamayı yeniden açın. |
| Köprü çalışıyor ama tuşlar çalışmıyor | **Kumandayı eşle** ile yeniden eşleyin; kumanda pilini kontrol edin. |
| Açılışta başlamıyor | Uygulamayı bir kez açın ("Durmaya zorla" sonrası Android boot yayını göndermez). |

Kayıtlar:

```sh
adb shell tail -50 /data/local/tmp/xrcd.log      # daemon
adb shell cat /data/local/tmp/xrc-pair.log       # eşleme
adb logcat -s XRC                                # açılış alıcısı
```

Kaldırma: uygulamayı kaldırmanız yeterli — supervisor APK'yı bulamayınca kendini kapatır. İsterseniz `adb shell rm /data/local/tmp/xrc*` ile dosyaları da silin.

## Bilinen kısıtlamalar

- **Mikrofon henüz çalışmıyor.** Kumanda mikrofona basıldığında Report ID 5 ile ses akışı istiyor ancak Xiaomi/Realtek'in ses başlatma komutu belgelenmemiş. Ses (muhtemelen Report ID 6/7/8, 120 bayt) çözülse bile shell kullanıcısı sanal mikrofon oluşturamadığı için Google TV Remote protokolü üzerinden aktarılması gerekecek. Şimdilik mikrofon tuşu arama ekranını açar.
- Shizuku ek bir şey sağlamaz: uygulama zaten shell yetkisiyle çalışıyor.
- Sadece tek kumanda desteklenir.

## Kaynaktan derleme

Gradle gerekmez; Android SDK (build-tools 36, platform 35) ve JDK yeterli:

```sh
./build.sh          # → build/xrc.apk
```

`build.sh` sırasıyla `aapt2 link`, `javac`, `d8`, `zipalign` ve `apksigner` çalıştırır. İmza anahtarı yoksa `debug.keystore` oluşturur (repoya dahil değildir; kendi anahtarınızla imzalanan APK, release APK'sının üzerine kurulamaz — önce kaldırın).

### Kaynak dosyaları

| Dosya | Görev |
|---|---|
| `Daemon.java` | Shell olarak çalışan köprü: GATT bağlantısı, rapor aboneliği, tuş enjeksiyonu, tuş haritası |
| `Pairer.java` | BLE tarama + eşleme aracı |
| `AdbClient.java` | Minimal adb protokol istemcisi (CNXN/AUTH/OPEN, RSA anahtarı) |
| `Bridge.java` | Supervisor betiği ve başlat/durdur/durum/eşle komutları |
| `BootReceiver.java`, `StartJob.java` | Açılışta otomatik başlatma |
| `MainActivity.java` | TV arayüzü |
| `VoiceProbe.java` | Tanı aracı: tüm HID/vendor bildirimlerini kaydeder (mikrofon araştırması) |

## Teknik ayrıntılar

- Bluetooth, `app_process` içinde yansıma ile kurulur: `BluetoothFrameworkInitializer.setBluetoothServiceManager(...)` + `BluetoothAdapter.createAdapter(AttributionSource "com.android.shell")`.
- Tuş enjeksiyonu: `InputManagerGlobal.getInstance().injectInputEvent(event, 0)`.
- Kumanda GATT'ı: HID servisi (`0x1812`), Xiaomi vendor servisi `d1ff…3c17` (a001 notify / a002 write) ve Realtek OTA servisi `d0ff…` (**dokunulmaz**).
- Report map ID 1 (consumer + tuş bitmap), 2 (klavye + LED), 6/7/8 (vendor `0xff00`, 120 bayt) tanımlar; ayrıca map dışında özel ID 3, 4, 5, 9, 10 ve `0xe0–0xee` feature raporları vardır.
- Tek örnek kilidi: soyut `LocalServerSocket("xiaomi-rc-bridge")`.

---

## English summary

**Xiaomi RC Bridge** makes Xiaomi BLE remotes (Mi TV Stick / Mi Box remote) work on Android TV boxes where they pair but no key does anything — **no root required**.

**Root cause:** the remote exposes 28 HID report characteristics, but Android's HOGP host only tracks 20 (`BTA_HH_LE_RPT_MAX`), so the Consumer Control input report (ID 1) carrying every key is never subscribed.

**Fix:** the app connects to the TV's own adbd (`127.0.0.1:5555`) with a built-in adb client and launches a daemon from its APK via `app_process` as the **shell** user (`BLUETOOTH_PRIVILEGED` + `INJECT_EVENTS`). The daemon opens its own GATT connection, subscribes to report ID 1 and injects key events (with auto-repeat). Netflix/Prime buttons launch the apps; keys are remappable via `/data/local/tmp/xrc-keymap.txt`. It starts at `LOCKED_BOOT_COMPLETED`, and a built-in **Pair remote** button scans for and bonds the remote (no "Bluetooth Scanner" app needed).

**Requirements:** Android 12+, developer options with network ADB (port 5555) enabled — keep it on. Accept the "Allow debugging?" prompt once.

**Not yet working:** the microphone (undocumented Xiaomi/Realtek voice start command).

Build: `./build.sh` (no Gradle). License: MIT.
