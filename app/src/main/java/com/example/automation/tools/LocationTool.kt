package com.example.automation.tools

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

class LocationTool : Tool {
    private val TAG = "LocationTool"
    override val name: String = "location"
    override val supportedActions: Set<String> = setOf("GET_LOCATION", "SEND_LOCATION")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = true,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    private fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        Log.d(TAG, "Checking location permissions and fetching coordinates...")
        
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Location permission not granted.")
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "PERMISSION_DENIED",
                message = "Location permission is not granted. Please open the MAX app settings/permissions and grant location access."
            )
        }

        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            var location: Location? = null
            
            // 1. Try to fetch a fresh active GPS location with 8s timeout
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = withTimeoutOrNull(8000) {
                    fetchFreshLocation(context, locationManager, LocationManager.GPS_PROVIDER)
                }
            }
            
            // 2. Fallback to fetch a fresh active Network location with 5s timeout
            if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = withTimeoutOrNull(5000) {
                    fetchFreshLocation(context, locationManager, LocationManager.NETWORK_PROVIDER)
                }
            }
            
            // 3. Fallback to cached last known GPS location
            if (location == null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
            
            // 4. Fallback to cached last known Network location
            if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }

            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                Log.d(TAG, "Successfully retrieved location: Lat=$lat, Lon=$lon")
                
                ToolResult(
                    success = true,
                    toolName = name,
                    verificationRequired = false,
                    metadata = JSONObject().apply {
                        put("latitude", lat)
                        put("longitude", lon)
                        put("maps_link", "https://maps.google.com/?q=$lat,$lon")
                    }
                )
            } else {
                Log.w(TAG, "Unable to fetch location coordinates. GPS/Network providers might have no fix.")
                ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "LOCATION_UNAVAILABLE",
                    message = "Could not get current location coordinates. Please verify your device's location/GPS switch is turned ON."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to retrieve location coordinates", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "LOCATION_ERROR",
                message = e.message ?: "Unknown location services error"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchFreshLocation(context: Context, locationManager: LocationManager, provider: String): Location? = suspendCancellableCoroutine { continuation ->
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancellationSignal = android.os.CancellationSignal()
                continuation.invokeOnCancellation {
                    cancellationSignal.cancel()
                }
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    context.mainExecutor
                ) { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locationManager.removeUpdates(this)
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                
                continuation.invokeOnCancellation {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            locationManager.removeUpdates(listener)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error removing updates on cancellation", e)
                        }
                    }
                }

                @Suppress("DEPRECATION")
                locationManager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching fresh location for provider $provider", e)
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
    }
}
