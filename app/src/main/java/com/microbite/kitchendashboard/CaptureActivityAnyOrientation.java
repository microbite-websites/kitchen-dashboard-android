package com.microbite.kitchendashboard;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * The bundled scanner screen is locked to landscape by default. This subclass
 * exists only so we can declare it in the manifest with a sensor-based
 * orientation, letting the scanner follow whichever way the device is held.
 * Used via ScanOptions.setCaptureActivity(...) in SettingsActivity.
 */
public class CaptureActivityAnyOrientation extends CaptureActivity {
}
