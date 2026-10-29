# OKULUMBENİM

Okul içinde kullanılan Android cihazları (tablet/telefon) merkezi bir sunucudan
yönetmek için hazırlanmış basit bir MDM (Mobile Device Management) sistemi.
Öğrenci cihazlarını fabrika ayarlarından sonra QR kod ile tek adımda kaydeden,
sunucudan komut alarak cihazı kilitleyip kamerayı kapatan, saatin
değiştirilmesini engelleyen bir Android uygulamasından, Rust tabanlı güvenlik
modülünden ve FastAPI sunucusundan oluşur. **Yapay zeka yardımıyla yazılmıştır.**

**Problem: Okullarda olan telefon toplama işlemi hem ekonomik, hemde güvenlik açısından risk barındırmaktadır.**
**Çözüm: Okul saatlerinde kilitlenen ve acil durumda yeniden kilidi açılan bir uygulama.**

## Bileşenler

| Bileşen      | Teknoloji                 | Açıklama |
|--------------|---------------------------|----------|
| `app/`       | Kotlin, Android SDK 34    | Device Admin (MDM) uygulaması |
| `rust_core/` | Rust (cdylib, JNI)        | Saat değiştirme tespiti güvenlik modülü |
| `server/`    | Python, FastAPI           | Cihaz kaydı, komut kuyruğu, QR üretimi |

## Özellikler

- QR kod ile fabrika ayarlarına döndürülmüş cihazın tek adımda provisioning ve
  kayıt edilmesi (Android Device Owner kurulumu)
- Sunucudan cihaza komut gönderimi: `LOCK`, `UNLOCK`, `RESTRICT_CAMERA`,
  `ALLOW_CAMERA`, `EMERGENCY_LOCK`, `EMERGENCY_UNLOCK`
- `ALL` hedefiyle tüm kayıtlı cihazlara aynı anda komut gönderimi
- Cihaz açılışta otomatik servis başlatma (`BOOT_COMPLETED`)
- Saat / saat bölgesi değişikliğini algılama ve Rust modülüyle güvenli saat
  doğrulama (öğrencinin Ayarlar'dan saati geri almasını engelleme)
- Device Admin politikaları: kilit, kamera kapatma, şifre politikası,
  veri silme (wipe) vb.
- Sunucu tarafında kurulum QR'ini PNG olarak üretir (`/qr` sayfası)

## Proje Yapısı

```
app/                    Android uygulaması (Gradle modülü)
  src/main/java/...     MainActivity, SecurityService, ServerService,
                        BootReceiver, TimeChangeReceiver, AdminReceiver,
                        PolicyHelper
  src/main/jniLibs/     Rust'tan derlenen libokulumbenim_core.so
rust_core/              Rust güvenlik modülü (JNI kütüphanesi)
server/                 FastAPI MDM sunucusu
gradle/                 Gradle wrapper ve versiyon kataloğu
```

## Gereksinimler

- Android uygulamasını derlemek için: JDK 17, Android SDK 34
- Rust modülünü derlemek için: Rust toolchain + `aarch64-linux-android` hedefi
  (`rustup target add aarch64-linux-android`) ve Android NDK
- Sunucuyu çalıştırmak için: Python 3.10+ ve `qrencode` (QR PNG üretimi için)

## Kurulum ve Çalıştırma

### Sunucu

```bash
cd server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

Cihaz kurulum sayfası: `http://<sunucu-ip>:8000/qr?wifi_ssid=OKUL_WIFI&server_url=http://<sunucu-ip>:8000/api/v1`

### Rust modülü (Android için)

```bash
cd rust_core
cargo build --release --target aarch64-linux-android --target-dir target/aarch64-linux-android
```

Üretilen `libokulumbenim_core.so` dosyası `app/src/main/jniLibs/arm64-v8a/`
altına kopyalanır (ARM64 cihazlar için).

### Android uygulaması

```bash
./gradlew :app:assembleDebug
```

## Cihaz Kayıt Akışı

1. Cihazı fabrika ayarlarına döndürün.
2. Hoş geldiniz ekranında 6 kez dokunarak provisioning ekranını açın.
3. Sunucunun `/qr` sayfasından üretilen QR kodu okutun.
4. Cihaz APK'yı indirip Device Owner olarak kurulur; ilk açılışta
   `server_url` parametresiyle sunucuya `/api/v1/register` üzerinden kayıt olur.
5. Cihaz periyodik olarak `/api/v1/poll/<device_id>` üzerinden komut bekler.

## Sunucu API'si

| Metot | Yol | Açıklama |
|-------|-----|----------|
| GET  | `/` | Servis durum kontrolü |
| POST | `/api/v1/register` | Cihaz kaydı |
| GET  | `/api/v1/poll/{device_id}` | Bekleyen komutları çekme |
| POST | `/api/v1/send-command` | Cihaza (veya `ALL`) komut gönderme |
| GET  | `/api/v1/devices` | Kayıtlı cihaz listesi |
| POST | `/api/v1/provision-qr` | QR payload JSON üretimi |
| GET  | `/api/v1/provision-qr.png` | Kurulum QR PNG |
| GET  | `/api/v1/config-qr.png` | Sunucu adresi QR PNG |
| GET  | `/qr` | Tarayıcıdan erişilen cihaz kurulum sayfası |

## Notlar

- `server/` içindeki veriler bellek içindedir (`devices_db`, `pending_commands`);
  sunucu yeniden başlayınca kayıtlar silinir.
- APK indirme adresi ve checksum sabit olarak `server/main.py` içinde tanımlı;
  yeni APK ile güncellemeniz gerekir.
- `app/src/main/AndroidManifest.xml` içinde `testOnly="true"` ibaresi var;
  yayın için kaldırılması gereken bir geliştirme ayarıdır.
