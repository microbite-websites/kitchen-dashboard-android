package com.microbite.kitchendashboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Settings");
        }

        getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.settings_container, new SettingsFragment())
            .commit();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        private ActivityResultLauncher<String> btPermissionLauncher;
        private ActivityResultLauncher<Intent> enableBtLauncher;
        private ActivityResultLauncher<ScanOptions> qrScanLauncher;
        private ActivityResultLauncher<String> qrImagePickLauncher;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            btPermissionLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        populatePrinterList();
                        if (granted) {
                            Toast.makeText(requireContext(),
                                    R.string.settings_printer_select, Toast.LENGTH_SHORT).show();
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                            // Permanently denied ("Don't ask again") → only Settings can fix it
                            showAppSettingsDialog();
                        }
                    });

            enableBtLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> populatePrinterList());

            qrScanLauncher = registerForActivityResult(
                    new ScanContract(),
                    result -> {
                        if (result.getContents() != null) {
                            applyScannedUrl(result.getContents().trim());
                        }
                    });

            // Pick a photo/screenshot of the QR and decode it. Works on every
            // Android version (pure-Java decode, no camera Activity), so it's
            // the fallback for devices where the live scanner can't run.
            qrImagePickLauncher = registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) {
                            decodeQrFromImage(uri);
                        }
                    });

            Preference scanPref = findPreference("scan_qr");
            if (scanPref != null) {
                scanPref.setOnPreferenceClickListener(p -> {
                    launchQrScanner();
                    return true;
                });
            }

            populatePrinterList();
        }

        /** Opens the bundled ZXing scanner. It requests the CAMERA permission itself. */
        private void launchQrScanner() {
            // The bundled scanner (zxing-android-embedded 4.3) needs Android 7+
            // (API 24). On older devices launching it crashes the app, so fall
            // back to manual entry there. Same if the device has no camera.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                    || !requireContext().getPackageManager()
                            .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                showManualUrlFallback();
                return;
            }
            try {
                ScanOptions options = new ScanOptions();
                options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
                options.setPrompt(getString(R.string.scan_qr_prompt));
                options.setBeepEnabled(true);
                options.setOrientationLocked(true); // steadier on older hardware
                qrScanLauncher.launch(options);
            } catch (Exception e) {
                // Any failure to even start the scanner → manual entry instead.
                showManualUrlFallback();
            }
        }

        /**
         * When the live camera scanner can't run, offer two paths: read the QR
         * from a saved photo/screenshot (decoded in-app, works everywhere), or
         * type the URL in by hand.
         */
        private void showManualUrlFallback() {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.scan_qr_unavailable_title)
                    .setMessage(R.string.scan_qr_unavailable_msg)
                    .setPositiveButton(R.string.scan_qr_photo_option, (d, w) -> {
                        try {
                            qrImagePickLauncher.launch("image/*");
                        } catch (Exception e) {
                            Toast.makeText(requireContext(),
                                    R.string.scan_qr_photo_failed, Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNeutralButton(R.string.scan_qr_enter_manually, (d, w) -> {
                        Preference urlPref = findPreference("dashboard_url");
                        if (urlPref != null) {
                            onDisplayPreferenceDialog(urlPref); // opens the URL editor
                        }
                    })
                    .setNegativeButton(R.string.printer_action_dismiss, null)
                    .show();
        }

        /**
         * Decode a QR code from a picked image using zxing's pure-Java reader
         * (bundled in the APK, so this works on every Android version with no
         * camera Activity). The image is downsampled to ~1200px on load so a
         * multi-megapixel photo can't exhaust memory on a low-RAM device, and
         * we catch Throwable so an out-of-memory can never hard-crash the app.
         */
        private void decodeQrFromImage(Uri uri) {
            try {
                Bitmap bmp = loadDownsampledBitmap(uri, 1200);
                if (bmp == null) {
                    Toast.makeText(requireContext(),
                            R.string.scan_qr_photo_failed, Toast.LENGTH_LONG).show();
                    return;
                }

                int w = bmp.getWidth();
                int h = bmp.getHeight();
                int[] pixels = new int[w * h];
                bmp.getPixels(pixels, 0, w, 0, 0, w, h);
                bmp.recycle();

                BinaryBitmap binary = new BinaryBitmap(
                        new HybridBinarizer(new RGBLuminanceSource(w, h, pixels)));
                Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
                hints.put(DecodeHintType.POSSIBLE_FORMATS,
                        Collections.singletonList(BarcodeFormat.QR_CODE));
                hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

                Result result = new MultiFormatReader().decode(binary, hints);
                showScannedUrlOptions(result.getText().trim());
            } catch (Throwable t) {
                // No QR found, image unreadable, or out of memory — never crash.
                Toast.makeText(requireContext(),
                        R.string.scan_qr_photo_failed, Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Load a picked image scaled down so its longest edge is no larger than
         * {@code maxEdge}, using BitmapFactory's inSampleSize (powers of two).
         * Reading the bounds first means the full-size bitmap is never created.
         */
        private Bitmap loadDownsampledBitmap(Uri uri, int maxEdge) throws Exception {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }

            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / sample > maxEdge) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        }

        /**
         * Show the decoded URL with the choice to fill it in directly or copy it
         * to the clipboard for manual pasting (the reliable escape hatch on
         * locked-down devices).
         */
        private void showScannedUrlOptions(String scanned) {
            if (!scanned.startsWith("http://") && !scanned.startsWith("https://")) {
                Toast.makeText(requireContext(), R.string.scan_qr_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.scan_qr_found_title)
                    .setMessage(scanned)
                    .setPositiveButton(R.string.scan_qr_use_it, (d, w) -> applyScannedUrl(scanned))
                    .setNeutralButton(R.string.scan_qr_copy, (d, w) -> {
                        ClipboardManager cm = (ClipboardManager)
                                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("Dashboard URL", scanned));
                            Toast.makeText(requireContext(),
                                    R.string.scan_qr_copied, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(R.string.printer_action_dismiss, null)
                    .show();
        }

        /** Validates the scanned text is a dashboard URL and stores it. */
        private void applyScannedUrl(String scanned) {
            if (!scanned.startsWith("http://") && !scanned.startsWith("https://")) {
                Toast.makeText(requireContext(), R.string.scan_qr_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            EditTextPreference urlPref = findPreference("dashboard_url");
            if (urlPref != null) {
                urlPref.setText(scanned);   // persists to SharedPreferences + updates the dialog
            }
            Toast.makeText(requireContext(), R.string.scan_qr_saved, Toast.LENGTH_SHORT).show();
        }

        /**
         * Intercept the printer dialog: a ListPreference shows its picker from
         * onClick(), so this is the only reliable place to stop an empty "None"
         * list from opening. If the printer can't be chosen yet, explain why
         * (and offer a fix) instead; otherwise let the normal picker show.
         */
        @Override
        public void onDisplayPreferenceDialog(Preference preference) {
            if ("printer_name".equals(preference.getKey()) && interceptPrinterDialog()) {
                return;
            }
            super.onDisplayPreferenceDialog(preference);
        }

        // ─── Tap handler: explain / fix, or let the list open ──────────────────

        /** @return true if we handled it (showed a reason/fix), false to open the picker. */
        @SuppressLint("MissingPermission")
        private boolean interceptPrinterDialog() {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

            if (adapter == null) {
                showInfoDialog(R.string.printer_unsupported_msg);
                return true; // consume — nothing to choose
            }

            if (!hasBluetoothConnectPermission()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
                }
                return true;
            }

            if (!adapter.isEnabled()) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.printer_dialog_title)
                        .setMessage(R.string.printer_bt_off_msg)
                        .setPositiveButton(R.string.printer_action_turn_on,
                                (d, w) -> enableBtLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)))
                        .setNegativeButton(R.string.printer_action_dismiss, null)
                        .show();
                return true;
            }

            if (countPairedPrinters(adapter) == 0) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.printer_dialog_title)
                        .setMessage(R.string.printer_not_paired_msg)
                        .setPositiveButton(R.string.printer_action_bt_settings, (d, w) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                            } catch (Exception e) {
                                startActivity(new Intent(Settings.ACTION_SETTINGS));
                            }
                        })
                        .setNegativeButton(R.string.printer_action_dismiss, null)
                        .show();
                return true;
            }

            return false; // ready — let the normal printer picker open
        }

        private void showInfoDialog(int messageRes) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.printer_dialog_title)
                    .setMessage(messageRes)
                    .setPositiveButton(R.string.printer_action_dismiss, null)
                    .show();
        }

        private void showAppSettingsDialog() {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.printer_dialog_title)
                    .setMessage(R.string.printer_permission_denied_msg)
                    .setPositiveButton(R.string.printer_action_app_settings, (d, w) ->
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + requireContext().getPackageName()))))
                    .setNegativeButton(R.string.printer_action_dismiss, null)
                    .show();
        }

        // ─── Helpers ───────────────────────────────────────────────────────────

        private boolean hasBluetoothConnectPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        @SuppressLint("MissingPermission")
        private int countPairedPrinters(BluetoothAdapter adapter) {
            try {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                int n = 0;
                for (BluetoothDevice d : bonded) {
                    if (d.getName() != null) n++;
                }
                return n;
            } catch (SecurityException e) {
                return 0;
            }
        }

        /** Keeps the summary line in sync so the reason is visible without tapping. */
        @SuppressLint("MissingPermission")
        private void populatePrinterList() {
            ListPreference printerPref = findPreference("printer_name");
            if (printerPref == null) return;

            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                printerPref.setSummary(R.string.printer_unsupported_msg);
                return;
            }
            if (!hasBluetoothConnectPermission()) {
                printerPref.setSummary(R.string.settings_printer_need_permission);
                return;
            }
            if (!adapter.isEnabled()) {
                printerPref.setSummary(R.string.settings_printer_bt_off);
                return;
            }

            try {
                Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
                List<String> names = new ArrayList<>();
                names.add("None");
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getName() != null) {
                        names.add(device.getName());
                    }
                }

                String[] entries = names.toArray(new String[0]);
                printerPref.setEntries(entries);
                printerPref.setEntryValues(entries);

                if (names.size() <= 1) {
                    printerPref.setSummary(R.string.settings_printer_none_paired);
                } else {
                    printerPref.setSummary(R.string.settings_printer_select);
                }
            } catch (SecurityException e) {
                printerPref.setSummary(R.string.settings_printer_need_permission);
            }
        }
    }
}
