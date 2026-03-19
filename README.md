# Kitchen Dashboard Android App

A WebView wrapper for the Microbite Kitchen Dashboard with Bluetooth ESC/POS printer support.
Works with any cheap Bluetooth thermal printer (BT-583, GOOJPRT, etc).

## Features
- Full-screen WebView showing your kitchen dashboard
- Bluetooth ESC/POS printing (58mm or 80mm)
- Auto-reconnect on print failure
- Settings for dashboard URL, printer selection, paper width
- Works on Android 5.0+ (API 21+)

## Setup — First Time

### Step 1 — Get the gradle wrapper jar (one time only)
The gradle-wrapper.jar is required but can't be included in git.
Run this command after cloning:

```bash
# On Mac/Linux:
curl -L "https://services.gradle.org/distributions/gradle-8.4-bin.zip" | \
  python3 -c "
import sys, zipfile, io
z = zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read()))
for f in z.namelist():
    if f.endswith('gradle-wrapper.jar'):
        with open('gradle/wrapper/gradle-wrapper.jar','wb') as out:
            out.write(z.read(f))
        print('Done')
        break
"
```

Or simpler — just open the project in Android Studio once and it will download automatically.

### Step 2 — Push to GitHub
```bash
git init
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/YOUR_USERNAME/kitchen-dashboard-android.git
git push -u origin main
```

### Step 3 — Get the APK from GitHub Actions
1. Go to your GitHub repo
2. Click **Actions** tab
3. Click the latest **Build APK** workflow run
4. Scroll down to **Artifacts**
5. Download **KitchenDashboard-debug**
6. Unzip and install the APK on your Android device

> Enable "Install from unknown sources" on your Android device:
> Settings → Security → Install unknown apps → Allow for your browser/file manager

### Step 4 — Configure the app
1. Open the app
2. It will prompt you to open Settings
3. Enter your dashboard URL (from WooCommerce → Kitchen Dashboard settings)
4. Pair your Bluetooth printer in Android Settings → Bluetooth first
5. Come back to the app → Settings → select your printer
6. Select paper width (58mm or 80mm)
7. Tap the menu (⋮) → Connect Printer

## How Printing Works

The app injects `window.KDPrint` into the WebView. The kitchen dashboard plugin
detects this and uses it for printing instead of the iMin WebSocket.

The plugin sends ESC/POS commands as a hex string:
```javascript
window.KDPrint.print("1B40 1B61 01 ..."); // hex bytes
window.KDPrint.isConnected();              // returns true/false
```

## Updating the App
Just push changes to GitHub — Actions will build a new APK automatically.

## Troubleshooting
- **Printer not found**: Pair the printer in Android Bluetooth settings first, then restart the app
- **Print fails**: Use menu → Connect Printer to reconnect
- **Dashboard won't load**: Check the URL in settings includes the full kd_token parameter
