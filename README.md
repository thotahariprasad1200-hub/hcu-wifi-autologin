# HCU Wi-Fi Auto-Login for Android

Version **1.7.1** hardens background sign-in for ColorOS / realme UI and adds on-device background-health diagnostics.

## ColorOS / realme UI background blockers (fixed or guided in 1.7.1)

Realme UI can stop every standard background mechanism unless the app is explicitly allowed: it kills the listener service, defers or drops scheduled jobs for killed apps, blocks the boot receiver until "Auto-launch" is enabled, and a swipe-away from Recent apps can force-stop the app (which cancels jobs and alarms). Version 1.7.1:

- Upgrades the network-triggered watchdog job to an **expedited job** (Android 12+): near-instant execution and far more resistant to standby deferral.
- Re-arms the watchdog jobs from every app component that runs, so any wake re-establishes all safety nets.
- Shows **background health on the home screen**: battery-exemption state, last background wake time and result, and when the listener service was last alive. If background execution is being blocked, the screen says so instead of failing silently.
- Asks for the battery-optimization exemption automatically on first launch, and the settings button now deep-links into the ColorOS/realme UI **Auto-launch** manager (with fallback instructions).
- Listener service heartbeat every minute (was 5), so expired HCU sessions are retried faster while the app is alive.

**Required one-time phone setup for guaranteed background login (Realme):** (1) tap ALLOW on the battery dialog the app shows, (2) enable **Auto-launch** for the app (settings button deep-links there), (3) lock the app in Recent apps. Without these, ColorOS can still freeze the app between wakes.

Version **1.7.0** made automatic sign-in survive the app being killed in the background, and probes both known HCU portal gateways.

## Intended behavior

1. Android chooses and connects to a Wi-Fi network already saved on the phone. The app does not turn Wi-Fi on, choose an SSID, or scan for SSIDs.
2. The app listens for Wi-Fi network, link-property, and captive-portal capability changes. OS-scheduled watchdog jobs re-check the connection even if the app process was killed.
3. If Android already marks that Wi-Fi network as validated, the app does nothing.
4. Otherwise, the app probes the known HCU portal at `http://192.168.56.2:8090/httpclient.html` over that exact Wi-Fi network.
5. Credentials are sent only if the response looks like the HCU/Sophos/Cyberoam login page. A normal `10.x.x.x` address by itself is not treated as proof that the network is HCU.
6. The app submits the saved credentials to the Sophos-style `/login.xml` endpoint, handles a returned `CHALLENGE` state, and verifies Internet access over the same Wi-Fi network before reporting success.

## Background reliability changes in 1.7.0

- Adds OS-held `JobScheduler` watchdog jobs that survive the app process being killed by Android or Realme battery management: a network-triggered job wakes the app when any network connects, a periodic job re-checks the session every 15 minutes (Android's minimum), and a one-shot job re-arms the listener if the app is swiped away. Each job can complete the portal sign-in by itself, even when the foreground service is not running.
- Adds a one-tap "Allow" system dialog (battery-optimization exemption) from the background-settings button so Realme/Android stops killing the app.
- Probes both known campus portal gateways (`192.168.56.2` and `10.4.0.1`), ordered by the subnet the phone actually joined. Earlier builds only tried `192.168.56.2`, so buildings that issue `10.4.x.x` addresses could never sign in.

## Reliability and battery changes in 1.5.0

- Uses the Android callback's exact `Network` object instead of choosing the first Wi-Fi network returned by the system.
- Detects the HCU portal by checking the known portal endpoint and its page content, rather than treating every private `10.x.x.x` Wi-Fi network as campus Wi-Fi.
- Waits for DHCP/IPv4 link properties with short event-driven retries.
- Removes the three-minute watchdog. There is no periodic network polling while idle.
- Coalesces duplicate triggers from Android's network callback and Wi-Fi broadcasts.
- Cancels stale attempts when the Wi-Fi network changes.
- Uses an Android `specialUse` foreground service for the continuous event listener. This avoids the Android 15 six-hour `dataSync` service limit and allows the listener to be restarted after boot. The service keeps a quiet, low-importance ongoing notification because Android requires foreground services to show one.
- Keeps per-request Wi-Fi binding; it never globally binds the whole app process to campus Wi-Fi.
- No Location permission, SSID scan, or third-party library is required.

## Timing and Android limitations

The app starts its work as soon as Android reports the Wi-Fi connection and IP configuration as ready. A successful sign-in in one to two seconds is a performance goal, not a guarantee: Android controls Wi-Fi association and DHCP, and the university portal controls server response time. The app cannot authenticate before those layers are ready.

A foreground service substantially improves background reliability, but Android and Realme battery-management settings can still stop or restrict an app. The ongoing notification is intentional and silent; completely invisible, guaranteed 24/7 background execution is not a supported Android app behavior.

The app does not change the phone's saved Wi-Fi priorities or auto-connect settings. The campus SSIDs must already be saved and allowed to auto-connect in Android Wi-Fi settings.

## Important protocol limitation

The app uses the HCU portal address observed on the user's device and a Sophos-style login exchange. The private campus portal is not reachable from GitHub Actions, so CI can compile the app but cannot prove that the university's current server accepts the exact POST fields, challenge sequence, or credentials. That final protocol check requires a real HCU Wi-Fi connection.

The campus portal uses plain HTTP. Credentials are therefore not encrypted in transit between the phone and the portal. Device-side Android Keystore encryption protects the saved password at rest but does not change this network limitation.

## Security warning: app signing key

The repository previously exposed the Android signing keystore and its signing configuration in public source. Treat that signing identity as compromised. The current branch keeps the existing signing identity temporarily so an installed 1.4.4 app can still be updated without uninstalling it. A safe key rotation requires creating a new private key outside the public repository, configuring it as a GitHub Actions secret, and then installing the first newly signed APK as a fresh install. Do not reuse the old exposed key for a public distribution.

## Build

The CI workflow builds a release APK with Java 17, Gradle 8.11.1, Android Gradle Plugin 8.9.1, and Android SDK 36:

```bash
gradle clean assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Branch builds upload an APK and SHA-256 checksum as a GitHub Actions artifact. Version tags `v*` publish a GitHub Release.
