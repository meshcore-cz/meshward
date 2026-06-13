package cz.meshcore.meshward.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.LocationPrecision

/**
 * Returns an action that turns on distance estimates at a chosen [LocationPrecision]: enables it if
 * the needed permission is already held, otherwise requests it (FINE asks for precise + coarse;
 * COARSE asks for coarse only). If precise was requested but the user only granted approximate, we
 * fall back to COARSE. If the OS won't show the dialog anymore (permanently denied), it routes to
 * the app's system settings so the toggle can't silently "do nothing".
 */
@Composable
fun rememberLocationEnabler(vm: ChatViewModel): (LocationPrecision) -> Unit {
    val context = LocalContext.current
    val activity = context.findActivity()
    var pending by remember { mutableStateOf(LocationPrecision.COARSE) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = fine || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        when {
            pending == LocationPrecision.FINE && fine -> vm.enableLocation(LocationPrecision.FINE)
            coarse -> vm.enableLocation(LocationPrecision.COARSE) // only approximate granted
            // Dialog was suppressed (permanently denied) → guide to settings. After a plain denial the
            // system still offers a rationale, so we leave it off and let them retry.
            activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) -> context.openAppDetailsSettings()
            else -> Unit
        }
    }
    return { precision ->
        pending = precision
        if (vm.hasLocationPermission(precision)) {
            vm.enableLocation(precision)
        } else {
            launcher.launch(
                if (precision == LocationPrecision.FINE)
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                else
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }
}

/** Opens this app's system "App info" screen, where the location permission can be toggled. */
fun Context.openAppDetailsSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Unwraps a Compose [Context] (often a ContextWrapper) to its hosting [Activity], or null. */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
