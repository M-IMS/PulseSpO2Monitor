# Pulse & SpO2 Monitor — Samsung Galaxy Note 9

A native Android app that reads heart rate (BPM) and blood oxygen (SpO2)
using the Note 9's built-in rear sensors via the Android Sensor API.

---

## How to Open in Android Studio

1. Extract this ZIP file to a folder on your computer.
2. Open **Android Studio** → **Open** → select the extracted `PulseSpO2Monitor` folder.
3. Wait for Gradle sync to complete (requires internet for dependencies).
4. Connect your Samsung Galaxy Note 9 via USB with **USB Debugging** enabled.
5. Press **Run ▶** (or Shift+F10).

---

## Enable USB Debugging on Note 9

1. Go to **Settings → About Phone → Software Information**
2. Tap **Build Number** 7 times until "Developer mode enabled" appears
3. Go back to **Settings → Developer Options**
4. Enable **USB Debugging**
5. Connect to PC and tap **Allow** on the phone when prompted

---

## How the App Works

| Sensor | Android API | Notes |
|--------|-------------|-------|
| Heart Rate | `Sensor.TYPE_HEART_RATE` | Standard Android sensor, works on Note 9 |
| SpO2 | Samsung vendor sensor (auto-detected) | Scanned by name — `spo2`/`oxygen` |

- The app scans all sensors at launch and shows which ones were found.
- Tap **▶ Start** and place your **fingertip firmly** on the rear sensor
  (the small circular sensor beside the rear camera).
- Readings stabilize after ~10 seconds.
- BPM and SpO2 values are color-coded:
  - 🟢 Green = Normal range
  - 🟡 Orange = Slightly outside normal
  - 🔴 Red = Outside normal range

---

## Sensor Notes for Note 9

The Samsung Galaxy Note 9's SpO2 sensor is exposed as a **vendor-specific sensor**.
The app detects it by scanning all available sensors for keywords like "spo2" or "oxygen"
in the sensor name. If your device reports SpO2 unavailable, Samsung Health may need
to be installed for the sensor driver to be active.

---

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 26+ (Android 8.0+)
- Samsung Galaxy Note 9 (or any Android phone with a heart rate sensor)

---

## Disclaimer

This app is for **personal wellness monitoring only**, not medical diagnosis.
Consumer-grade sensors can vary in accuracy. Always use certified medical
equipment for clinical measurements.
