# Vesper

**Ambient Reality Wallpaper Engine for Android**

Vesper quietly synthesizes real-world morning news, local meteorological arcs, and coarse regional context via Gemini Search Grounding (`gemini-3.8-flash`), renders subtle museum-grade art (`gemini-nano-banana-2.1` in `4K`), fits cleanly to your phone's exact native display pixels (`WindowMetrics`), and explains the full real-world briefing inside the app.

## Features
- **Morning Edition & Objective Chronicle:** Default 06:30 Daily (or Weekly) `WorkManager` refresh on Wi-Fi prioritizes corroborated front-page global/national developments from the last ~24h, active day events, and day-ahead meteorological shifts (frost, storms, heavy rain, sharp temperature changes) — keeping visual variety strict while never sacrificing real-world significance.
- **Subtlety Over Spectacle:** Forbids flags, clipart, text, numerals, clocks, or sensational news depictions on the artwork itself.
- **Today's Dossier:** Cleanly summarizes the news, decoded visual clues, and clickable grounded source links right inside the app.
- **Exact Device Pixel Fit:** Auto-detects screen resolution via Android `WindowMetrics`, generates in 4K (`9:16` / mapped ratio), and center-crops cleanly on-device.
- **Lock Art + Calm Home Twin:** Full 4K art on the Lock Screen and a softly blurred, dimmed Home Screen twin derived on-device (plus 4 other screen target modes).
- **Per-Section & Global Resets:** Reset individual parameter cards, one-off Studio overrides, or all preferences cleanly while preserving your API key and archive.
- **Auto-Updating Models + Manual Control:** Queries `/v1beta/models` automatically or lets you pin/enter custom model IDs.
- **Hardware-Backed Key Storage:** Uses Android Keystore AES-256-GCM + Jetpack DataStore Preferences.

## Build & Install (Windows, No Android Studio Required)
1. Open PowerShell inside this repository and run:
   ```powershell
   .\setup-and-install.ps1
   ```
2. This installs the latest stable Android SDK + Build-Tools, runs unit tests (`gradlew testDebugUnitTest`), builds `app\build\outputs\apk\debug\app-debug.apk`, and installs via USB or Wi-Fi ADB (`.\setup-and-install.ps1 -SkipBuild -Device <PHONE_IP>:<PORT>`).

## API & Google AI Pro Setup
- Paste your API key from [Google AI Studio](https://aistudio.google.com/app/apikey) in **Settings**.
- If you have **Google AI Pro**, claim your developer credit via [Google Developer Program Benefits](https://me.developers.google.com/benefits) and link it to your Cloud Billing project.
