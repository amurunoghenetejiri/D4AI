package com.d4tech.d4ai.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Locale

class LocationHelper(private val context: Context) {

    fun whereAmI(): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Location permission is needed. Enable it for D4AI in App settings."
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
            return "Location is off. I opened location settings — turn it on, then ask again."
        }
        return try {
            @Suppress("DEPRECATION")
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc == null) {
                "I don't have a recent location yet. Move outdoors or open Maps once, then ask again."
            } else {
                val place = reverse(loc.latitude, loc.longitude)
                "You're near $place. Coordinates ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}."
            }
        } catch (e: SecurityException) {
            "Location permission denied."
        } catch (e: Exception) {
            "Couldn't get location: ${e.message}"
        }
    }

    fun navigateTo(destination: String): String {
        return try {
            val uri = Uri.parse("google.navigation:q=" + Uri.encode(destination))
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Navigating to $destination."
        } catch (_: Exception) {
            val web = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination))
            context.startActivity(Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opening maps to $destination."
        }
    }

    private fun reverse(lat: Double, lon: Double): String {
        return try {
            if (!Geocoder.isPresent()) return "$lat, $lon"
            @Suppress("DEPRECATION")
            val list = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
            val a = list?.firstOrNull() ?: return "$lat, $lon"
            listOfNotNull(a.subLocality, a.locality, a.adminArea, a.countryName)
                .distinct().joinToString(", ").ifBlank { "$lat, $lon" }
        } catch (_: Exception) {
            "$lat, $lon"
        }
    }
}
