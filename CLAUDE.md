# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug
```

The CI pipeline (`.github/workflows/build.yml`) runs `gradle assembleDebug` on push to `main` and uploads the APK as a GitHub Actions artifact named `KitchenDashboard-debug` (30-day retention). Java 17 is required for the build.

There are no tests in this project.

## Architecture

This app is a **WebView wrapper with a native Bluetooth ESC/POS printer bridge**. The dashboard UI itself runs remotely in a WebView; the Android app's only native responsibilities are loading that WebView and handling thermal printing.

### Key Components

**`MainActivity.java`** — The entire app. It:
- Hosts the WebView pointing to the configured dashboard URL
- Manages a Bluetooth RFCOMM socket (SPP UUID `00001101-0000-1000-8000-00805F9B34FB`) for thermal printing
- Exposes `window.KDPrint` to the WebView via `PrintBridge` (an inner class annotated `@JavascriptInterface`)
- Runs all blocking Bluetooth I/O on a single-threaded `ExecutorService`, posting results back via `Handler(Looper.getMainLooper())`
- Holds a `PARTIAL_WAKE_LOCK` (1-hour cap) to keep the CPU alive for SSE/Bluetooth while the screen may be off
- Auto-reconnects on print failure: 5-second delay, up to 10 attempts

**`SettingsActivity.java`** — `PreferenceFragmentCompat` screen for configuring: dashboard URL, keep-screen-on, text zoom, transparent status bar, top margin offset, Bluetooth printer selection (dynamically populated from paired devices), paper width (58mm/80mm), and auto-reconnect toggle.

**`PrintBridge` (inner class)** — The JavaScript interface exposed as `window.KDPrint`. Methods available to the web dashboard:
- `print(String hexData)` — sends raw ESC/POS hex bytes to the connected printer
- `isConnected()` — returns connection status
- `getPairedPrinters()` — returns comma-separated paired BT device names

### Data Flow

1. User configures dashboard URL and pairs a Bluetooth printer in Settings
2. `MainActivity` loads the URL in the WebView with JS enabled
3. The web dashboard calls `window.KDPrint.print(hexString)` when it needs to print
4. Native code decodes hex → bytes → writes to Bluetooth `OutputStream`
5. On failure, auto-reconnect kicks in; once reconnected, `window.KDPrint._connected = true` is injected into the WebView via `evaluateJavascript`

### ViewBinding

ViewBinding is enabled. Layouts are bound in activities via the generated `ActivityMainBinding` / `ActivitySettingsBinding` classes.

### Permissions

The app targets SDK 34 and handles two Bluetooth permission models:
- Android <12: `BLUETOOTH` + `BLUETOOTH_ADMIN` + location permissions (needed for device discovery)
- Android 12+: `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN`

`android:usesCleartextTraffic="true"` is set to allow HTTP dashboard URLs. The app also prompts the user to be excluded from battery optimization on first launch (Android 6+).
