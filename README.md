# Android TV Remote — phone remote for Xiaomi Mi TV, Mi Box & Google TV

Lost your TV remote or tired of hunting for it? Turn your Android phone into a remote for **any TV running Android TV or Google TV** — Xiaomi Mi TV / Mi Box / TV Stick, Philips, Sony, TCL, Hisense, Chromecast with Google TV and more.

No account, no ads, no tracking. Free and open source.

**[⬇ Download the latest APK](https://github.com/sami93ks-kam/android-tv-remote/releases/latest)**

> 🇵🇱 Polska wersja opisu — [przewiń niżej](#-po-polsku)

## Features

- **Automatic TV discovery** on your Wi-Fi network — no IP addresses to type
- **Secure pairing** with the 6-character code shown on the TV (same official Android TV Remote protocol v2 that Google's app uses, over TLS)
- Remembers paired TVs and reconnects automatically
- D-pad, OK, Back, Home, Menu
- Volume up / down, mute, power
- Input source switching
- One-tap shortcuts to **YouTube** and **Netflix**
- **Wake the TV over Bluetooth** — the phone can act as a Bluetooth remote, which helps on TVs that turn off Wi-Fi in standby (Android 9+, depends on the phone's Bluetooth HID support)

## Requirements

- Android 7.0 or newer on the phone
- TV with Android TV / Google TV, on the same Wi-Fi network as the phone

## Install

1. Download `android-tv-remote-*.apk` from [Releases](https://github.com/sami93ks-kam/android-tv-remote/releases/latest).
2. Open it on your phone and allow installing from this source when asked.
3. Launch the app, pick your TV from the list and type the code shown on the TV screen.

> The app UI is currently in Polish. An English translation is planned — contributions welcome.

## How it works

The app finds TVs via mDNS (`_androidtvremote2._tcp`), pairs using a client certificate generated on your phone, and sends key events over an encrypted connection. Nothing leaves your local network.

## Build from source

Open the project in Android Studio (Hedgehog or newer) or build with Gradle 8.5 and JDK 17:

```
gradle assembleDebug
```

## Feedback

Found a TV model that works (or doesn't)? [Open an issue](https://github.com/sami93ks-kam/android-tv-remote/issues) with the brand and model — it helps others decide. If the app saved you from buying a new remote, a ⭐ on the repo is appreciated.

---

## 🇵🇱 Po polsku

Zgubiony pilot albo padły baterie? Zamień telefon z Androidem w pilota do **każdego telewizora z Android TV lub Google TV**: Xiaomi Mi TV, Mi Box, Mi TV Stick, Philips, Sony, TCL, Hisense, Chromecast z Google TV i innych.

Bez konta, bez reklam, bez śledzenia. Darmowa aplikacja open source.

**[⬇ Pobierz najnowszy plik APK](https://github.com/sami93ks-kam/android-tv-remote/releases/latest)**

### Co potrafi

Sama znajduje telewizory w Twojej sieci Wi-Fi i paruje się kodem z ekranu TV. Zapamiętuje sparowane telewizory i łączy się z nimi ponownie bez pytania. Masz strzałki, OK, Wstecz, Ekran główny, Menu, głośność, wyciszenie, zasilanie, zmianę źródła oraz szybkie skróty do YouTube i Netflixa.

Do tego telewizor można wybudzić przez Bluetooth. Telefon udaje wtedy pilota Bluetooth, co pomaga przy telewizorach wyłączających Wi-Fi w czuwaniu (Android 9+, zależy od telefonu).

### Instalacja

Pobierz plik APK z zakładki [Releases](https://github.com/sami93ks-kam/android-tv-remote/releases/latest), otwórz go na telefonie i zezwól na instalację z tego źródła. Uruchom aplikację, wybierz telewizor i wpisz kod z ekranu.

Wymagania: Android 7.0 lub nowszy, telewizor z Android TV / Google TV w tej samej sieci Wi-Fi.

---

Not affiliated with Xiaomi, Google or any TV manufacturer. Brand names are used only to describe compatibility.

MIT License © 2026 Kamil Samiec
