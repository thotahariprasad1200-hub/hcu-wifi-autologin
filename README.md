# HCU Wi-Fi Auto-Login for Android

A lightweight Android app for automatically authenticating to the University of Hyderabad (HCU) Wi-Fi captive portal. It is designed for Android phones that automatically connect to saved campus Wi-Fi networks.

## Version 1.4.4

### Authentication and reliability changes
- Opens the HCU portal page first to establish the portal session and collect cookies.
- Uses the Sophos-style `/login.xml` authentication endpoint rather than posting credentials to the portal display page.
- Handles a portal `CHALLENGE` response when the portal supplies a state token.
- Verifies actual internet access using the Wi-Fi network's connectivity probe before reporting login success.
- Waits for DHCP to assign a recognised campus IP address and retries connection setup with bounded backoff.
- Retries transient portal or network failures without repeating rejected credentials indefinitely.
- Binds each HTTP request to the Wi-Fi `Network` object instead of globally binding the whole app process to Wi-Fi.

### App features retained
- Background foreground-service monitor for Wi-Fi connection changes.
- Quick Settings tile for manual one-tap login.
- Home-screen login widget.
- Username and password settings.
- Password encryption using Android Keystore (AES-GCM).
- No SSID scanning or Location permission is required.

## How it works

1. Android connects to an available saved Wi-Fi network (Auto-connect must be enabled for that network in Android Wi-Fi settings).
2. The app waits for the Wi-Fi network and its IP configuration to become ready.
3. It checks whether the connection belongs to a supported HCU address range.
4. It loads the captive portal page, submits the saved credentials using the portal authentication endpoint, and handles a supported challenge response.
5. It checks for actual internet access before displaying a successful sign-in status.

The app does not select or switch Wi-Fi SSIDs itself. Android handles saved-network selection.

## Project layout

```
hcu-wifi-autologin/
├── .github/workflows/build.yml
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/edu/uoh/hcuwifi/
│       │   ├── HcuAuthHelper.java
│       │   ├── HcuForegroundService.java
│       │   ├── MainActivity.java
│       │   ├── HcuTileService.java
│       │   ├── HcuWidgetProvider.java
│       │   ├── BootReceiver.java
│       │   └── SecureStorage.java
│       └── res/
├── build.gradle
├── gradle.properties
└── settings.gradle
```

## Build

The GitHub Actions workflow uses Java 17 and Gradle 8.2:

```bash
gradle clean assembleRelease
```

The signed release APK is generated at:

```
app/build/outputs/apk/release/app-release.apk
```

A tagged version matching `v*` is published as a GitHub Release by the workflow. A normal branch build uploads the APK and SHA-256 checksum as a GitHub Actions artifact.

## Important security note

The campus captive portal endpoint is HTTP, not HTTPS. Credentials are therefore not encrypted in transit between the phone and the campus portal. The app encrypts the stored password on the device, but local encryption does not protect the HTTP request on the network. Use this app only on the intended university network.

The signing keystore currently used for update compatibility has previously been present in this public repository. Treat it as exposed and plan a signing-key rotation. Rotating the key requires uninstalling the existing app before installing an APK signed with the new key.
