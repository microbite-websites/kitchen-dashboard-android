package com.microbite.kitchendashboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "KitchenDashboard";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Auto-reconnect: wait 5s between attempts, give up after 10 tries
    private static final int RECONNECT_DELAY_MS  = 5000;
    private static final int RECONNECT_MAX_TRIES = 10;

    /**
     * Printer readiness / connection state. Shared with the dashboard through
     * {@link PrintBridge#getStatus()} so the web UI can show a specific reason
     * rather than a generic "check Bluetooth".
     */
    enum PrinterStatus {
        CONNECTED,           // socket open, ready to print
        CONNECTING,          // connection attempt in flight
        DISCONNECTED,        // ready to connect, but not connected
        NO_PRINTER_SELECTED, // user hasn't picked a printer in Settings
        NOT_PAIRED,          // selected printer isn't bonded to this device
        BT_OFF,              // Bluetooth adapter is turned off
        NO_PERMISSION,       // BLUETOOTH_CONNECT not granted (API 31+)
        BT_UNSUPPORTED       // device has no Bluetooth hardware
    }

    private WebView webView;
    private BluetoothAdapter bluetoothAdapter;

    private volatile BluetoothSocket bluetoothSocket;
    private volatile OutputStream outputStream;
    private volatile PrinterStatus status = PrinterStatus.DISCONNECTED;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable reconnectRunnable = this::connectPrinter;

    private boolean isConnecting     = false;
    private boolean autoReconnecting = false;
    private int     reconnectAttempt = 0;

    // True when the user explicitly asked to connect (menu tap) — controls
    // whether we surface a blocking restriction dialog vs. failing quietly.
    private boolean userInitiatedConnect = false;

    // Wake lock — keeps CPU alive so SSE / BT stay connected when screen dims
    private PowerManager.WakeLock wakeLock;

    // Modern permission / enable-Bluetooth result handlers (registered in onCreate)
    private ActivityResultLauncher<String> btPermissionLauncher;
    private ActivityResultLauncher<Intent> enableBtLauncher;

    // ─── Reliability features (each toggleable in Settings) ───────────────────

    // #3 — auto-reload when the network comes back, plus a once-a-day refresh
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean loadFailed = false;
    private static final long DAILY_REFRESH_MS = 24L * 60 * 60 * 1000;
    private final Runnable dailyRefreshRunnable = new Runnable() {
        @Override public void run() {
            if (webView != null) webView.reload();
            mainHandler.postDelayed(this, DAILY_REFRESH_MS);
        }
    };

    // #2 — recover from a WebView render-process crash; guard against tight loops
    private long lastRenderRecoveryMs = 0L;

    // #7 — print queue: jobs that arrive while disconnected are held briefly
    // (under the dashboard's fallback timeout) and flushed once reconnected,
    // so a docket fired mid-reconnect still reaches the Bluetooth printer.
    private static final int  PRINT_QUEUE_MAX        = 20;
    private static final long PRINT_QUEUE_TIMEOUT_MS = 5000;
    private final List<QueuedPrint> printQueue = new ArrayList<>();

    private final class QueuedPrint {
        final String hex;
        final String jobId;
        Runnable timeout;
        QueuedPrint(String hex, String jobId) { this.hex = hex; this.jobId = jobId; }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        webView = findViewById(R.id.webView);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        registerActivityLaunchers();

        applyScreenOnSetting();
        setupWebView();
        applyTextZoom();
        promptBatteryOptimisation();
        loadDashboard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyScreenOnSetting();
        acquireWakeLock();
        applyTextZoom();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String url     = prefs.getString("dashboard_url", "");
        String current = webView.getUrl();
        if (!url.isEmpty() && (current == null || !current.equals(url))) {
            webView.loadUrl(url);
        }

        // Reconcile network monitoring with the current toggle state so a change
        // made in Settings takes effect as soon as we return to the dashboard.
        if (prefs.getBoolean("auto_reload_network", true)) {
            startNetworkMonitoring();
        } else {
            stopNetworkMonitoring();
        }

        // Reconnect automatically if a printer is configured and ready but the
        // socket isn't live (e.g. it dropped while we were backgrounded).
        if (status != PrinterStatus.CONNECTED && !isConnecting
                && checkReadiness() == PrinterStatus.DISCONNECTED) {
            userInitiatedConnect = false;
            reconnectAttempt = 0;
            connectPrinter();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseWakeLock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        cancelAutoReconnect();
        closeBluetoothSocket();
        releaseWakeLock();
        stopNetworkMonitoring();
    }

    // ─── Activity result launchers ────────────────────────────────────────────

    private void registerActivityLaunchers() {
        btPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        reconnectAttempt = 0;
                        connectPrinter();
                    } else {
                        // Permanently denied (don't ask again) → guide to app settings
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                            showRestrictionDialog(PrinterStatus.NO_PERMISSION, true);
                        }
                    }
                });

        enableBtLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                        reconnectAttempt = 0;
                        connectPrinter();
                    }
                });
    }

    // ─── App Settings Logic ───────────────────────────────────────────────────

    private void applyTextZoom() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int zoom = 100;
        try {
            Object zoomObj = prefs.getAll().get("text_zoom");
            if (zoomObj instanceof String) {
                zoom = Integer.parseInt((String) zoomObj);
            } else if (zoomObj instanceof Integer) {
                zoom = (Integer) zoomObj;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading text zoom setting", e);
        }
        if (webView != null) {
            webView.getSettings().setTextZoom(zoom);
        }
    }

    private void applyScreenOnSetting() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean keepOn = prefs.getBoolean("keep_screen_on", true);
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ─── Wake Lock ────────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "KitchenDashboard::PrinterWakeLock"
        );
        wakeLock.acquire(60 * 60 * 1000L); // 1-hour safety cap, re-acquired each onResume
        Log.d(TAG, "Wake lock acquired");
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "Wake lock released");
        }
    }

    // ─── Network-recovery auto-reload + daily refresh (#3) ────────────────────

    private void startNetworkMonitoring() {
        if (connectivityManager == null) {
            connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        }
        if (connectivityManager == null) return;

        if (networkCallback == null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    // Fires on a background thread — hop to the UI thread before
                    // touching the WebView. Only reload if the page actually
                    // failed, so a normal connectivity blip costs nothing.
                    mainHandler.post(() -> {
                        if (loadFailed && webView != null) {
                            loadFailed = false;
                            webView.reload();
                        }
                    });
                }
            };
            try {
                NetworkRequest req = new NetworkRequest.Builder().build();
                connectivityManager.registerNetworkCallback(req, networkCallback);
            } catch (Exception e) {
                Log.w(TAG, "Could not register network callback", e);
                networkCallback = null;
            }
        }

        // (Re)arm the once-a-day refresh.
        mainHandler.removeCallbacks(dailyRefreshRunnable);
        mainHandler.postDelayed(dailyRefreshRunnable, DAILY_REFRESH_MS);
    }

    private void stopNetworkMonitoring() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) { /* wasn't registered */ }
        }
        networkCallback = null;
        mainHandler.removeCallbacks(dailyRefreshRunnable);
    }

    // ─── Battery Optimisation ─────────────────────────────────────────────────

    private void promptBatteryOptimisation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("battery_opt_asked", false)) return;
        prefs.edit().putBoolean("battery_opt_asked", true).apply();

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.battery_opt_title))
                .setMessage(getString(R.string.battery_opt_message))
                .setPositiveButton(getString(R.string.battery_opt_ok), (dialog, which) -> {
                    // Play-safe: open the battery-optimisation LIST (no restricted
                    // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission required).
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                })
                .setNegativeButton(getString(R.string.battery_opt_cancel), null)
                .show();
    }

    // ─── WebView Setup ────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // Let the new-order chime play without a tap first. The dashboard builds
        // a fresh AudioContext per order, which otherwise stays suspended in a
        // WebView until the user interacts — meaning a missed audible alert.
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new PrintBridge(), "KDPrint");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Only a real http(s) load counts as success; the offline
                // fallback page (loadDataWithBaseURL) must NOT clear the flag,
                // otherwise network-recovery reload wouldn't fire.
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    loadFailed = false;
                }
                webView.evaluateJavascript("window.kdAndroidBridge = true;", null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // Only replace the page when the main document itself fails to
                // load (no network, bad URL, server down) — never for a missing
                // sub-resource like a favicon or font.
                if (request.isForMainFrame()) {
                    loadFailed = true;
                    showErrorPage();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The WebView's render process died (OS reclaimed memory, or the
                // page crashed). That WebView object is now unusable; returning
                // true tells the system we've handled it so the WHOLE APP is not
                // killed. When recovery is on we rebuild the Activity from
                // scratch (fresh WebView, reloads the dashboard).
                Log.w(TAG, "WebView render process gone (crashed="
                        + (detail != null && detail.didCrash()) + ")");
                SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                boolean recover = p.getBoolean("auto_recover_crash", true);
                long now = SystemClock.elapsedRealtime();
                // 10s guard stops a crash-on-load page from looping forever.
                if (recover && now - lastRenderRecoveryMs > 10_000) {
                    lastRenderRecoveryMs = now;
                    mainHandler.post(MainActivity.this::recreate);
                }
                return true;
            }
        });
    }

    /**
     * Friendly, branded "can't reach dashboard" screen with a Try Again button,
     * shown instead of the WebView's blank default error. Tapping Try Again
     * re-navigates to the configured dashboard URL.
     */
    private void showErrorPage() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String url  = prefs.getString("dashboard_url", "");
        String safe = url.replace("\\", "\\\\").replace("'", "\\'");
        String html =
                "<!DOCTYPE html><html><head><meta charset='utf-8'>"
              + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
              + "<style>html,body{height:100%;margin:0}"
              + "body{font-family:sans-serif;background:#1f2430;color:#e8eaed;"
              + "display:flex;align-items:center;justify-content:center;text-align:center;padding:24px;box-sizing:border-box}"
              + ".card{max-width:420px}.ico{font-size:56px;margin-bottom:8px}"
              + "h1{font-size:22px;margin:0 0 12px}p{color:#aab2c0;line-height:1.55;margin:0 0 22px}"
              + "button{background:#2271b1;color:#fff;border:0;border-radius:10px;"
              + "padding:15px 30px;font-size:16px;font-weight:700}"
              + ".hint{font-size:13px;color:#7b8694;margin-top:18px}</style></head>"
              + "<body><div class='card'><div class='ico'>\uD83D\uDCE1</div>"
              + "<h1>Can\u2019t reach the dashboard</h1>"
              + "<p>Make sure this device is connected to Wi\u2011Fi or mobile data, then try again. "
              + "If it keeps failing, check the dashboard URL in Settings.</p>"
              + "<button onclick=\"location.href='" + safe + "'\">Try Again</button>"
              + "<div class='hint'>Menu \u2192 Settings to change the dashboard URL</div>"
              + "</div></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void loadDashboard() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String url = prefs.getString("dashboard_url", "");
        if (url.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
            Toast.makeText(this, "Please configure your dashboard URL", Toast.LENGTH_LONG).show();
        } else {
            webView.loadUrl(url);
        }
    }

    // ─── Options Menu ─────────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_reload) {
            webView.reload();
            return true;
        }
        if (item.getItemId() == R.id.action_connect_printer) {
            userInitiatedConnect = true;
            reconnectAttempt = 0;
            connectPrinter();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ─── Readiness check ──────────────────────────────────────────────────────

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true; // legacy BLUETOOTH is an install-time permission
    }

    private String selectedPrinterName() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        return prefs.getString("printer_name", "");
    }

    /**
     * Works out why we can (or can't) connect, without touching the socket.
     * Order matters: each check assumes the previous ones passed.
     */
    private PrinterStatus checkReadiness() {
        if (bluetoothAdapter == null)            return PrinterStatus.BT_UNSUPPORTED;
        if (!hasBluetoothConnectPermission())    return PrinterStatus.NO_PERMISSION;
        if (!bluetoothAdapter.isEnabled())       return PrinterStatus.BT_OFF;
        String name = selectedPrinterName();
        if (name.isEmpty() || "None".equals(name)) return PrinterStatus.NO_PRINTER_SELECTED;
        if (findBondedDevice(name) == null)      return PrinterStatus.NOT_PAIRED;
        return PrinterStatus.DISCONNECTED;
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice findBondedDevice(String name) {
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bonded) {
                if (name.equals(device.getName())) return device;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to read bonded devices", e);
        }
        return null;
    }

    // ─── Restriction dialog (native, actionable) ──────────────────────────────

    private void showRestrictionDialog(PrinterStatus reason, boolean permissionPermanentlyDenied) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.printer_dialog_title)
                .setNegativeButton(R.string.printer_action_dismiss, null);

        switch (reason) {
            case BT_UNSUPPORTED:
                b.setMessage(R.string.printer_unsupported_msg);
                break;

            case NO_PERMISSION:
                if (permissionPermanentlyDenied) {
                    b.setMessage(R.string.printer_permission_denied_msg)
                     .setPositiveButton(R.string.printer_action_app_settings,
                             (d, w) -> startActivity(new Intent(
                                     Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                     Uri.parse("package:" + getPackageName()))));
                } else {
                    b.setMessage(R.string.printer_permission_msg)
                     .setPositiveButton(R.string.printer_action_grant,
                             (d, w) -> requestBluetoothPermission());
                }
                break;

            case BT_OFF:
                b.setMessage(R.string.printer_bt_off_msg)
                 .setPositiveButton(R.string.printer_action_turn_on, (d, w) -> requestEnableBluetooth());
                break;

            case NO_PRINTER_SELECTED:
                b.setMessage(R.string.printer_none_selected_msg)
                 .setPositiveButton(R.string.printer_action_open_settings,
                         (d, w) -> startActivity(new Intent(this, SettingsActivity.class)));
                break;

            case NOT_PAIRED:
                b.setMessage(R.string.printer_not_paired_msg)
                 .setPositiveButton(R.string.printer_action_bt_settings,
                         (d, w) -> {
                             try {
                                 startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                             } catch (Exception e) {
                                 startActivity(new Intent(Settings.ACTION_SETTINGS));
                             }
                         });
                break;

            default:
                return; // nothing actionable for CONNECTED / CONNECTING / DISCONNECTED
        }
        b.show();
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
        }
    }

    @SuppressLint("MissingPermission")
    private void requestEnableBluetooth() {
        enableBtLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
    }

    // ─── Bluetooth Connection ─────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private void connectPrinter() {
        if (isConnecting) return;

        PrinterStatus readiness = checkReadiness();
        if (readiness != PrinterStatus.DISCONNECTED) {
            // Not ready to connect — record the reason and (only if the user
            // asked) show an actionable dialog. Auto attempts stay silent.
            setStatus(readiness);
            if (userInitiatedConnect) {
                userInitiatedConnect = false;
                showRestrictionDialog(readiness, false);
            }
            return;
        }

        final String printerName = selectedPrinterName();
        isConnecting = true;
        setStatus(PrinterStatus.CONNECTING);
        if (!autoReconnecting && userInitiatedConnect) {
            Toast.makeText(this, "Connecting to " + printerName + "…", Toast.LENGTH_SHORT).show();
        }

        executor.execute(() -> {
            try {
                BluetoothDevice targetDevice = findBondedDevice(printerName);
                if (targetDevice == null) {
                    mainHandler.post(() -> {
                        isConnecting = false;
                        setStatus(PrinterStatus.NOT_PAIRED);
                        if (userInitiatedConnect) {
                            userInitiatedConnect = false;
                            showRestrictionDialog(PrinterStatus.NOT_PAIRED, false);
                        }
                    });
                    return;
                }

                closeBluetoothSocket();

                // NOTE: we deliberately do NOT call bluetoothAdapter.cancelDiscovery()
                // here. On Android 12+ it requires BLUETOOTH_SCAN, which this app
                // doesn't hold (Play compliance — we only connect to bonded devices,
                // never scan). Calling it would throw SecurityException on this
                // background thread and crash the process.
                BluetoothSocket socket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                bluetoothSocket = socket;
                outputStream = socket.getOutputStream();

                mainHandler.post(() -> {
                    isConnecting     = false;
                    autoReconnecting = false;
                    userInitiatedConnect = false;
                    reconnectAttempt = 0;
                    setStatus(PrinterStatus.CONNECTED);
                    Toast.makeText(this, "✅ Printer connected!", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Printer connected successfully");
                    flushPrintQueue();
                });

            } catch (IOException e) {
                Log.e(TAG, "Bluetooth connection failed (attempt " + reconnectAttempt + ")", e);
                mainHandler.post(() -> {
                    isConnecting = false;
                    setStatus(PrinterStatus.DISCONNECTED);
                    scheduleAutoReconnect();
                });
            } catch (Exception e) {
                // SecurityException (a missing BT permission) or anything else
                // unexpected. This runs on a background thread, so an uncaught
                // exception here would terminate the whole app — never let that
                // happen. Re-derive the real reason and stop quietly.
                Log.e(TAG, "Bluetooth connect error — aborting without crashing", e);
                mainHandler.post(() -> {
                    isConnecting = false;
                    setStatus(checkReadiness());
                });
            }
        });
    }

    private void scheduleAutoReconnect() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean("auto_reconnect", true);
        if (!enabled) {
            setStatus(PrinterStatus.DISCONNECTED);
            Toast.makeText(this, "❌ Printer disconnected", Toast.LENGTH_SHORT).show();
            return;
        }

        reconnectAttempt++;
        if (reconnectAttempt > RECONNECT_MAX_TRIES) {
            autoReconnecting = false;
            reconnectAttempt = 0;
            setStatus(PrinterStatus.DISCONNECTED);
            Toast.makeText(this,
                    "❌ Could not reconnect after " + RECONNECT_MAX_TRIES + " attempts. " +
                    "Use menu → Connect Printer to try again.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        autoReconnecting = true;
        Log.d(TAG, "Auto-reconnect scheduled in " + RECONNECT_DELAY_MS + "ms (attempt " + reconnectAttempt + ")");
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
    }

    private void cancelAutoReconnect() {
        autoReconnecting = false;
        reconnectAttempt = 0;
        mainHandler.removeCallbacks(reconnectRunnable);
    }

    private void closeBluetoothSocket() {
        if (bluetoothSocket != null) {
            try { bluetoothSocket.close(); } catch (IOException e) { /* ignore */ }
            bluetoothSocket = null;
        }
        outputStream = null;
    }

    private void setStatus(PrinterStatus newStatus) {
        status = newStatus;
    }

    // ─── JavaScript Bridge ────────────────────────────────────────────────────

    public class PrintBridge {

        /** Legacy entry point (no job id) — kept so older dashboards still print. */
        @JavascriptInterface
        public void print(String hexData) {
            doPrint(hexData, null);
        }

        /** Preferred entry point: the dashboard passes a job id so the result
         *  can be matched back via window.kdPrintResult(jobId, ok, reason). */
        @JavascriptInterface
        public void print(String hexData, String jobId) {
            doPrint(hexData, jobId);
        }

        @JavascriptInterface
        public boolean isConnected() {
            return status == PrinterStatus.CONNECTED && outputStream != null;
        }

        /** Structured state so the dashboard can show a specific reason. */
        @JavascriptInterface
        public String getStatus() {
            PrinterStatus s = status;
            if (s != PrinterStatus.CONNECTED && s != PrinterStatus.CONNECTING) {
                // Refresh non-connected state on demand (BT may have been toggled)
                s = checkReadiness();
                setStatus(s);
            }
            String printer = jsonEscape(selectedPrinterName());
            return "{\"state\":\"" + s.name().toLowerCase() + "\",\"printer\":\"" + printer + "\"}";
        }

        @JavascriptInterface
        public String getPairedPrinters() {
            if (bluetoothAdapter == null || !hasBluetoothConnectPermission()) return "";
            try {
                @SuppressLint("MissingPermission")
                Set<BluetoothDevice> devices = bluetoothAdapter.getBondedDevices();
                StringBuilder sb = new StringBuilder();
                for (BluetoothDevice d : devices) {
                    if (d.getName() == null) continue;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(d.getName());
                }
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }
    }

    private void doPrint(String hexData, String jobId) {
        executor.execute(() -> {
            OutputStream os = outputStream;
            if (os == null) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                boolean queueEnabled = prefs.getBoolean("print_queue", true);
                // Only queue jobs that carry an id (so the result can be matched
                // and a timeout reported). Legacy no-id prints keep the old path.
                if (queueEnabled && jobId != null) {
                    mainHandler.post(() -> enqueuePrintJob(hexData, jobId));
                } else {
                    reportPrintResult(jobId, false, "not_connected");
                    // Kick off a reconnect so the next docket has a chance
                    mainHandler.post(() -> {
                        if (!isConnecting && checkReadiness() == PrinterStatus.DISCONNECTED) {
                            reconnectAttempt = 0;
                            connectPrinter();
                        }
                    });
                }
                return;
            }

            final byte[] bytes;
            try {
                bytes = hexToBytes(hexData);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Malformed hex payload — dropping print", e);
                reportPrintResult(jobId, false, "bad_data");
                return;
            }

            try {
                os.write(bytes);
                os.flush();
                Log.d(TAG, "Printed " + bytes.length + " bytes");
                reportPrintResult(jobId, true, null);
            } catch (IOException e) {
                Log.e(TAG, "Print failed — triggering auto-reconnect", e);
                outputStream = null;
                reportPrintResult(jobId, false, "io_error");
                mainHandler.post(() -> {
                    setStatus(PrinterStatus.DISCONNECTED);
                    Toast.makeText(MainActivity.this,
                            "Print failed — reconnecting…", Toast.LENGTH_SHORT).show();
                    scheduleAutoReconnect();
                });
            }
        });
    }

    /** Calls back into the dashboard so a failed docket can fall back / re-queue. */
    private void reportPrintResult(String jobId, boolean ok, String reason) {
        if (jobId == null) return;
        final String js = "if(window.kdPrintResult){window.kdPrintResult('"
                + jsonEscape(jobId) + "'," + ok + ","
                + (reason == null ? "null" : "'" + reason + "'") + ");}";
        mainHandler.post(() -> webView.evaluateJavascript(js, null));
    }

    // ─── Print queue (#7) — all access on the main thread ─────────────────────

    /**
     * Holds a docket that arrived while the printer was offline and triggers a
     * reconnect. If we connect within {@link #PRINT_QUEUE_TIMEOUT_MS} the job is
     * flushed to the printer; otherwise it's reported as failed so the dashboard
     * still falls back to browser printing (the timeout is deliberately shorter
     * than the dashboard's own fallback, so we never double-print).
     */
    private void enqueuePrintJob(String hex, String jobId) {
        // Drop the oldest if the buffer is full, failing it cleanly.
        while (printQueue.size() >= PRINT_QUEUE_MAX) {
            QueuedPrint old = printQueue.remove(0);
            mainHandler.removeCallbacks(old.timeout);
            reportPrintResult(old.jobId, false, "queue_full");
        }

        final QueuedPrint job = new QueuedPrint(hex, jobId);
        job.timeout = () -> {
            if (printQueue.remove(job)) {
                reportPrintResult(job.jobId, false, "timeout");
            }
        };
        printQueue.add(job);
        mainHandler.postDelayed(job.timeout, PRINT_QUEUE_TIMEOUT_MS);

        if (!isConnecting && checkReadiness() == PrinterStatus.DISCONNECTED) {
            reconnectAttempt = 0;
            connectPrinter();
        }
    }

    /** Re-sends every held docket now that the printer is connected. */
    private void flushPrintQueue() {
        if (printQueue.isEmpty()) return;
        List<QueuedPrint> jobs = new ArrayList<>(printQueue);
        printQueue.clear();
        for (QueuedPrint job : jobs) {
            mainHandler.removeCallbacks(job.timeout);
            doPrint(job.hex, job.jobId);
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    /** Strict hex → bytes. Throws IllegalArgumentException on bad input so a
     *  malformed payload is reported rather than silently killing the task. */
    private static byte[] hexToBytes(String hex) {
        if (hex == null) throw new IllegalArgumentException("null hex");
        hex = hex.replaceAll("\\s+", "");
        int len = hex.length();
        if (len == 0 || (len & 1) != 0) {
            throw new IllegalArgumentException("hex length not even: " + len);
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("non-hex char at " + i);
            }
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
    }
}
