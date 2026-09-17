<div align="center">

<img src="docs/logo.svg" width="88" alt="LeLing Launcher">

# LeLing Launcher 乐龄桌面

**An ad-free Android launcher built for seniors — zero ads · zero feeds · big & clear · remotely managed by family**

[![Live Demo](https://img.shields.io/badge/Live_Demo-safphere.github.io-E8722A)](https://safphere.github.io/leling-launcher/)
[![API](https://img.shields.io/badge/minSdk-26-blue)](#)
[![License](https://img.shields.io/badge/License-Apache_2.0-green)](LICENSE)
[![中文](https://img.shields.io/badge/中文-README-E8722A)](README.md)

</div>

---

## 🛡 Silver Certification · A Proposal

> **“Minors have anti-addiction systems. Senior citizens deserve the Silver Certification.”**
> **A phone number needs real-name registration; a senior's phone needs Silver Certification — once certified, the entire device goes ad-free: every app, every page.**

Silver Certification is an open social initiative — no organization, no certificates, no fees.
**Zero ads** for seniors, no fake “×” buttons or scare tactics, big text and short flows —
when you see 🛡, you know this app is clean for the elderly.

📖 Full initiative: [docs/银发认证倡议.md](docs/银发认证倡议.md) (Chinese)

---

## What is this

An ad-free Android launcher designed for elderly users. It turns smartphones back into tools seniors can actually use:

- **Readable**: big fonts, big icons, big contact photos — tap a photo to call
- **Ad-free**: zero ads and zero feeds on the desktop; whitelist mode makes ad-heavy apps completely invisible
- **Family-managed**: children configure everything in one hidden, PIN-protected place

**🌐 Interactive demo (no install needed)**: [safphere.github.io/leling-launcher](https://safphere.github.io/leling-launcher/)
Open a “phone” drowning in pop-up ads, hit “I've had enough” to clear them all, and experience what a Silver-Certified desktop feels like.

---

## ✨ Features

### Three home pages + tools page
- **Contacts**: large photos, tap to dial, import from phone book / pick photos
- **Life**: clock, weather (auto-location + voice), lunar calendar, monthly calendar
- **Apps**: whitelisted app grid with instant search
- **Tools**: flashlight, home-address card (for getting lost — big text + one-tap navigation)

### Health reminders & protection
- **Health reminders**: medication / blood-pressure / weather care — full-screen text + voice announcements, confirmation visible to children
- **Quiet hours**: reminders between 22:00–07:00 are deferred to the morning; SOS is never affected
- **SIM guard**: daily SIM checks, auto alert + **one-tap reboot** when the card loosens
- **Data guardian**: auto data-balance queries, cuts mobile data automatically to prevent overcharges
- **SOS**: hold 3 seconds — sends a located SMS and calls the emergency contact
- **LLZT status SMS**: children send one text, the phone replies with battery / SIM / data / reminder status

### Family mode (PIN protected)
- Hidden entry: tap the page indicator 5 times; default PIN 1234
- Contacts, whitelist, reminders, quiet hours, SIM checks, data thresholds — all in one place
- Hiding apps (what they *see*) and digital well-being (how *long* they use: daily / per-session / time-window) are handled separately
- Newly installed apps are reported instantly to prevent remote trick-installs

### AI voice assistant
- Hold to talk; powered by the GLM model API (one API Key to enable, zero ads and zero tracking in-app)

---

## 🚀 Getting started

Android Studio (JDK 17) ｜ minSdk 26 (Android 8.0) ｜ targetSdk 35

```bash
# Debug build (demo data + debug hooks included)
./gradlew assembleDebug

# Release build (R8-minified, ~2.4MB)
./gradlew assembleRelease
```

Release signing reads `keystore.properties` from the repo root (**never commit it**, already in .gitignore):

```properties
storeFile=keystore/your-release.jks
storePassword=your-password
keyAlias=your-alias
keyPassword=your-password
```

Without this file, release builds fall back to the debug signature (local testing only).

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

1. First launch opens a 3-step setup wizard: default launcher → permissions → contacts
2. Optional: activate device admin to unlock hard controls (hiding violating apps):

```bash
adb shell dpm set-device-owner com.safphere.launcher/.admin.ElderDeviceAdminReceiver
```

---

## 🔐 Privacy promise

- **Zero ads, zero feeds, zero user tracking** in the launcher itself; official libraries only, fully auditable source
- Contacts, reminders and settings are stored **locally** — no cloud, no telemetry
- LLZT replies **only to contacts**; unknown numbers are silently ignored; can be turned off in Family mode
- AI assistant is off by default and only works after children configure an API Key

---

## 🤖 Powered by Z.ai

<img src="docs/assets/zai-logo.png" width="56" align="left" alt="Z.ai" style="margin-right:14px">

**This entire project — architecture, health-reminder workflows, the well-being engine, this demo site and the Silver Certification initiative — was developed with Z.ai's GLM-5.3-Flash model on the ZCode Harness.**

In the AI era, the point is not flashy tricks — it is that building something practical for your family is finally within reach.

Model: [GLM-5.3-Flash](https://docs.z.ai/) ｜ Tooling: ZCode Harness ｜ Org: [zai-org](https://github.com/zai-org)

---

## 📄 License

[Apache-2.0](LICENSE)

<div align="center">

[中文](README.md) ｜ Feedback & feature requests: [Issues](https://github.com/Safphere/leling-launcher/issues)

</div>
