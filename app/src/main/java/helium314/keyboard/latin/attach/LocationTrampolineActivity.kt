// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.attach

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import helium314.keyboard.latin.R

/**
 * The keyboard cannot show a permission dialog. This activity asks only when Location is tapped,
 * takes one fix while the screen is open, and inserts it as text. It is not exported.
 * It never requests background location and it does not keep the fix.
 */
class LocationTrampolineActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var asked = false
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (hasLocationPermission()) fetch()
        else if (Build.VERSION.SDK_INT >= 23) {
            asked = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQ)
        } else {
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ) return
        if (hasLocationPermission()) {
            fetch()
            return
        }
        val blocked = asked && grantResults.isNotEmpty() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        Toast.makeText(this, R.string.location_denied, Toast.LENGTH_SHORT).show()
        if (blocked) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        finish()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun fetch() {
        val manager = getSystemService(LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            giveUp()
            return
        }
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val provider = when {
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            else -> null
        }
        if (provider == null) {
            deliver(lastKnown(manager))
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val cancel = CancellationSignal()
            handler.postDelayed({ cancel.cancel() }, TIMEOUT_MS)
            manager.getCurrentLocation(provider, cancel, mainExecutor) { location ->
                deliver(location ?: lastKnown(manager))
            }
        } else {
            deliver(lastKnown(manager))
        }
    }

    private fun lastKnown(manager: LocationManager): Location? {
        val names = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (name in names) {
            val fix = try {
                manager.getLastKnownLocation(name)
            } catch (_: SecurityException) {
                null
            } ?: continue
            if (best == null || fix.time > best.time) best = fix
        }
        return best
    }

    private fun deliver(location: Location?) {
        if (delivered) return
        delivered = true
        if (location == null) {
            giveUp()
            return
        }
        AttachBridge.deliver(AttachResult.Text(AttachCommit.locationLink(location.latitude, location.longitude)))
        finish()
    }

    private fun giveUp() {
        Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        const val REQ = 7
        const val TIMEOUT_MS = 8_000L
    }
}
