# HCU Wi-Fi Auto-Login (Lite)

An ultra-lightweight, zero-dependency native Android application designed specifically for Realme 7 (Android 12/13) to authenticate to the University of Hyderabad (HCU) Sophos Captive Portal Wi-Fi network.

## Key Features & Invariants

1. **Ultra-Lightweight Footprint (< 100 KB APK)**:
   - Zero third-party dependencies (no AndroidX, no OkHttp, no Kotlin runtime).
   - Pure standard Android SDK (`android.app.Activity`, `java.net.HttpURLConnection`, `android.service.quicksettings.TileService`, `android.appwidget.AppWidgetProvider`).
2. **Instant 1-Tap Access**:
   - **Quick Settings Tile**: Add the "HCU Wi-Fi" tile to your Android notification shade for 1-tap login from anywhere without opening the app.
   - **Home Screen Widget**: Clean, minimal 2x1 widget for immediate authentication from the home screen.
3. **Event-Driven Auto-Login**:
   - `NetworkReceiver` automatically triggers login when your phone connects to the HCU Wi-Fi network (`10.4.*.*` subnet).
   - Zero idle CPU/RAM usage: runs for ~1.2s to authenticate, then immediately terminates.
4. **Sophos Captive Portal Parameters**:
   - **Gateways**: Primary `192.168.56.2:8090`, Fallback `10.4.0.1:8090`.
   - **Protocol**: Plain HTTP on port 8090 (`/httpclient.html`).
   - **Probe**: `http://connectivitycheck.gstatic.com/generate_204` (HTTP 204 No Content).
   - **Network Security Config**: Cleartext HTTP explicitly whitelisted for gateway IPs and Google connectivity probe.
   - **Credential URL-Encoding**: Automatically handles special characters (e.g. `@`, `#` -> `%40%23`).

## Project Layout

```
hcu_wifi_lite/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/edu/uoh/hcuwifi/
│       │   ├── HcuAuthHelper.java       # Core HTTP connection and portal parsing logic
│       │   ├── MainActivity.java        # Pure Android UI with status & diagnostic console
│       │   ├── HcuTileService.java      # Quick Settings Tile provider
│       │   ├── HcuWidgetProvider.java   # Home screen widget provider
│       │   └── NetworkReceiver.java     # Connectivity broadcast receiver
│       └── res/
│           ├── drawable/                # Vector drawables and shape backgrounds
│           ├── layout/                  # Activity and widget layouts
│           ├── values/                  # Strings, colors, styles (Material Light)
│           └── xml/                     # Network security config and widget provider info
├── build.gradle
├── gradle.properties
└── settings.gradle
```

## Building the APK

Open the project in Android Studio or build via Gradle command line:

```bash
# Debug APK
./gradlew assembleDebug

# Optimized Release APK (< 100 KB)
./gradlew assembleRelease
```
