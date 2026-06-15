package com.microbite.kitchendashboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.ArrayList;
import java.util.List;
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

        // Ask for Bluetooth permission at most once per visit. Without this,
        // denying the prompt re-triggers populate → re-request → infinite loop.
        private boolean permissionRequested = false;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            btPermissionLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> populatePrinterList());

            populatePrinterList();
        }

        private boolean hasBluetoothConnectPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        @SuppressLint("MissingPermission")
        private void populatePrinterList() {
            ListPreference printerPref = findPreference("printer_name");
            if (printerPref == null) return;

            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                printerPref.setSummary(R.string.printer_unsupported_msg);
                return;
            }

            // Ask for permission inline (once) so the list can be populated
            // instead of dead-ending on a SecurityException. Re-requesting from
            // the denial callback would loop, so guard with permissionRequested.
            if (!hasBluetoothConnectPermission()) {
                printerPref.setSummary(R.string.settings_printer_need_permission);
                if (!permissionRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissionRequested = true;
                    btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
                }
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
