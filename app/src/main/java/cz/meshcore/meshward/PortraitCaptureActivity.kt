package cz.meshcore.meshward

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * QR scanner activity pinned to portrait. The library's default [CaptureActivity] follows the sensor
 * (even with `setOrientationLocked(true)`, which only locks to whatever orientation it detects at
 * launch), so we subclass it purely to attach `android:screenOrientation="portrait"` in the manifest.
 */
class PortraitCaptureActivity : CaptureActivity()
