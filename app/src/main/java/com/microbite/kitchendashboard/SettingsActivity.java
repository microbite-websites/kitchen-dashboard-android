package com.microbite.kitchendashboard;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

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

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
            populatePrinterList();
        }

        @SuppressLint("MissingPermission")
        private void populatePrinterList() {
            ListPreference printerPref = findPreference("printer_name");
            if (printerPref == null) return;

            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                printerPref.setSummary("Bluetooth not available on this device");
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

                if (pairedDevices.isEmpty()) {
                    printerPref.setSummary("No paired Bluetooth devices found. Pair your printer in Android Settings first.");
                }
            } catch (SecurityException e) {
                printerPref.setSummary("Bluetooth permission required");
            }
        }
    }
}
