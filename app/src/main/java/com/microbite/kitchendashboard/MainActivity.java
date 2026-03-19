package com.microbite.kitchendashboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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

    private WebView webView;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isConnecting = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        setupWebView();
        requestPermissions();
        loadDashboard();
    }

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

        // Inject the JavaScript bridge
        webView.addJavascriptInterface(new PrintBridge(), "KDPrint");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Inject bridge detection for the plugin
                webView.evaluateJavascript(
                    "window.kdAndroidBridge = true;", null
                );
            }
        });
    }

    private void loadDashboard() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String url = prefs.getString("dashboard_url", "");
        if (url.isEmpty()) {
            // No URL set — open settings
            startActivity(new Intent(this, SettingsActivity.class));
            Toast.makeText(this, "Please configure your dashboard URL", Toast.LENGTH_LONG).show();
        } else {
            webView.loadUrl(url);
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    }, PERMISSION_REQUEST_CODE);
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
            connectPrinter();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

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
        Toast.makeText(this, "Connecting to " + printerName + "...", Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            try {
                // Find the paired device by name
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

                // Close existing connection
                if (bluetoothSocket != null) {
                    try { bluetoothSocket.close(); } catch (IOException e) { /* ignore */ }
                }

                bluetoothSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();

                mainHandler.post(() -> {
                    Toast.makeText(this, "✅ Printer connected!", Toast.LENGTH_SHORT).show();
                    isConnecting = false;
                    // Notify the WebView that printer is ready
                    webView.evaluateJavascript("window.kdPrinterConnected = true;", null);
                });

            } catch (IOException e) {
                Log.e(TAG, "Bluetooth connection failed", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "❌ Connection failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    isConnecting = false;
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload if URL changed in settings
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String url = prefs.getString("dashboard_url", "");
        if (!url.isEmpty() && !webView.getUrl().equals(url)) {
            webView.loadUrl(url);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (bluetoothSocket != null) {
            try { bluetoothSocket.close(); } catch (IOException e) { /* ignore */ }
        }
    }

    // ─── JavaScript Bridge ────────────────────────────────────────────────────

    public class PrintBridge {

        @JavascriptInterface
        public void print(String hexData) {
            // hexData is a hex string of ESC/POS bytes e.g. "1b40 1b61..."
            executor.execute(() -> {
                try {
                    if (outputStream == null) {
                        mainHandler.post(() ->
                            Toast.makeText(MainActivity.this,
                                "Printer not connected. Use menu → Connect Printer",
                                Toast.LENGTH_LONG).show()
                        );
                        return;
                    }
                    byte[] bytes = hexToBytes(hexData.trim());
                    outputStream.write(bytes);
                    outputStream.flush();
                    Log.d(TAG, "Printed " + bytes.length + " bytes");
                } catch (IOException e) {
                    Log.e(TAG, "Print failed", e);
                    outputStream = null; // mark as disconnected
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this,
                            "Print failed — reconnecting...", Toast.LENGTH_SHORT).show();
                        connectPrinter();
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
            // Returns comma-separated list of paired Bluetooth device names
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
        // Accepts "1B 40 1B 61" or "1B401B61" formats
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
