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
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "KitchenDashboard";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int PERMISSION_REQUEST_CODE = 100;

    private static final int RECONNECT_DELAY_MS  = 5000;
    private static final int RECONNECT_MAX_TRIES = 10;

    private WebView webView;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isConnecting     = false;
    private boolean autoReconnecting = false;
    private int     reconnectAttempt = 0;

    private PowerManager.WakeLock wakeLock;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyStatusBarSetting();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Only apply top padding when transparent status bar is ON
        // When opaque, the system stacks everything correctly with zero padding
        ViewCompat.setOnApplyWindowInsetsListener(webView, (v, insets) -> {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean transparent = prefs.getBoolean("transparent_status_bar", false);

            if (transparent) {
                int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                int extraPx = dpToPx(getTopMarginOffsetDp());
                int totalPadding = statusBarHeight + extraPx;
                v.setPadding(0, totalPadding, 0, 0);
                Log.d(TAG, "Transparent ON — top padding: " + totalPadding + "px (statusBar=" + statusBarHeight + " extra=" + extraPx + ")");
            } else {
                v.setPadding(0, 0, 0, 0);
                Log.d(TAG, "Transparent OFF — top padding: 0");
            }
            return WindowInsetsCompat.CONSUMED;
        });

        applyScreenOnSetting();
        setupWebView();
        requestPermissions();
        promptBatteryOptimisation();
        loadDashboard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyScreenOnSetting();
        acquireWakeLock();
        applyTextZoom();
        ViewCompat.requestApplyInsets(webView);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String url     = prefs.getString("dashboard_url", "");
        String current = webView.getUrl();
        if (!url.isEmpty() && (current == null || !current.equals(url))) {
            webView.loadUrl(url);
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
    }

    // ─── Status Bar ───────────────────────────────────────────────────────────

    private void applyStatusBarSetting() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean transparent = prefs.getBoolean("transparent_status_bar", false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            if (transparent) {
                window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                window.setStatusBarColor(Color.TRANSPARENT);
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(Color.parseColor("#1a1a2e"));
            }
        }
    }

    // ─── Top Margin ───────────────────────────────────────────────────────────

    private int getTopMarginOffsetDp() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        try {
            return Integer.parseInt(prefs.getString("top_margin_offset", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int dpToPx(int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()
        ));
    }

    // ─── Wake Lock ────────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KitchenDashboard::PrinterWakeLock");
        wakeLock.acquire(60 * 60 * 1000L);
        Log.d(TAG, "Wake lock acquired");
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "Wake lock released");
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
                    Intent intent = new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
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

        applyTextZoom();

        webView.addJavascriptInterface(new PrintBridge(), "KDPrint");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webView.evaluateJavascript("window.kdAndroidBridge = true;", null);
            }
        });
    }

    private void applyTextZoom() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int zoom = Integer.parseInt(prefs.getString("text_zoom", "100"));
        webView.getSettings().setTextZoom(zoom);
        Log.d(TAG, "Text zoom set to " + zoom + "%");
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

    // ─── Permissions ──────────────────────────────────────────────────────────

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN},
                        PERMISSION_REQUEST_CODE);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        PERMISSION_REQUEST_CODE);
            }
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
            reconnectAttempt = 0;
            connectPrinter();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ─── Bluetooth Connection ─────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private void connectPrinter() {
        if (isConnecting) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String printerName = prefs.getString("printer_name", "");
        if (printerName.isEmpty()) {
            Toast.makeText(this, "No printer selected in settings", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        isConnecting = true;
        if (!autoReconnecting) {
            Toast.makeText(this, "Connecting to " + printerName + "…", Toast.LENGTH_SHORT).show();
        }
        executor.execute(() -> {
            try {
                Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
                BluetoothDevice targetDevice = null;
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getName() != null && device.getName().equals(printerName)) {
                        targetDevice = device;
                        break;
                    }
                }
                if (targetDevice == null) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Printer not found. Pair it in Bluetooth settings first.", Toast.LENGTH_LONG).show();
                        isConnecting = false;
                    });
                    return;
                }
                closeBluetoothSocket();
                bluetoothSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                mainHandler.post(() -> {
                    isConnecting     = false;
                    autoReconnecting = false;
                    reconnectAttempt = 0;
                    Toast.makeText(this, "✅ Printer connected!", Toast.LENGTH_SHORT).show();
                    webView.evaluateJavascript("window.kdPrinterConnected = true;", null);
                    Log.d(TAG, "Printer connected successfully");
                });
            } catch (IOException e) {
                Log.e(TAG, "Bluetooth connection failed (attempt " + reconnectAttempt + ")", e);
                mainHandler.post(() -> {
                    isConnecting = false;
                    scheduleAutoReconnect();
                });
            }
        });
    }

    private void scheduleAutoReconnect() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean("auto_reconnect", true);
        if (!enabled) {
            Toast.makeText(this, "❌ Printer disconnected", Toast.LENGTH_SHORT).show();
            return;
        }
        reconnectAttempt++;
        if (reconnectAttempt > RECONNECT_MAX_TRIES) {
            autoReconnecting = false;
            reconnectAttempt = 0;
            Toast.makeText(this,
                    "❌ Could not reconnect after " + RECONNECT_MAX_TRIES + " attempts. Use menu → Connect Printer to try again.",
                    Toast.LENGTH_LONG).show();
            webView.evaluateJavascript("window.kdPrinterConnected = false;", null);
            return;
        }
        autoReconnecting = true;
        Log.d(TAG, "Auto-reconnect scheduled in " + RECONNECT_DELAY_MS + "ms (attempt " + reconnectAttempt + ")");
        mainHandler.postDelayed(this::connectPrinter, RECONNECT_DELAY_MS);
    }

    private void cancelAutoReconnect() {
        autoReconnecting = false;
        reconnectAttempt = 0;
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void closeBluetoothSocket() {
        if (bluetoothSocket != null) {
            try { bluetoothSocket.close(); } catch (IOException e) { /* ignore */ }
            bluetoothSocket = null;
        }
        outputStream = null;
    }

    // ─── JavaScript Bridge ────────────────────────────────────────────────────

    public class PrintBridge {

        @JavascriptInterface
        public void print(String hexData) {
            executor.execute(() -> {
                try {
                    if (outputStream == null) {
                        mainHandler.post(() ->
                                Toast.makeText(MainActivity.this,
                                        "Printer not connected. Use menu → Connect Printer",
                                        Toast.LENGTH_LONG).show());
                        return;
                    }
                    byte[] bytes = hexToBytes(hexData.trim());
                    outputStream.write(bytes);
                    outputStream.flush();
                    Log.d(TAG, "Printed " + bytes.length + " bytes");
                } catch (IOException e) {
                    Log.e(TAG, "Print failed — triggering auto-reconnect", e);
                    outputStream = null;
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "Print failed — reconnecting…", Toast.LENGTH_SHORT).show();
                        scheduleAutoReconnect();
                    });
                }
            });
        }

        @JavascriptInterface
        public boolean isConnected() {
            return outputStream != null && bluetoothSocket != null && bluetoothSocket.isConnected();
        }

        @JavascriptInterface
        public String getPairedPrinters() {
            if (bluetoothAdapter == null) return "";
            try {
                @SuppressLint("MissingPermission")
                Set<BluetoothDevice> devices = bluetoothAdapter.getBondedDevices();
                StringBuilder sb = new StringBuilder();
                for (BluetoothDevice d : devices) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(d.getName());
                }
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private static byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s+", "");
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
