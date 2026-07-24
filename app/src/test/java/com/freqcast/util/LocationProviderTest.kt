package com.freqcast.util

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager

/**
 * [ShadowLocationManager] backs a real [LocationManager] under Robolectric, so these tests drive
 * the actual [LocationProvider] logic (permission gate, cached-fix preference, timeout) rather
 * than a mock — same "inject a real, test-substitutable collaborator" pattern as
 * [com.freqcast.data.RadioBrowserApi]'s MockWebServer-backed tests. Permission grant/deny
 * goes through `Shadows.shadowOf(application).grantPermissions()/denyPermissions()`
 * ([org.robolectric.shadows.ShadowContextWrapper], inherited by `ShadowApplication`) — the
 * `PackageManager`-level `grantRuntimePermission` shadow method looked like the obvious API but
 * doesn't actually affect `ContextCompat.checkSelfPermission()`'s result under this Robolectric
 * version; the `ContextWrapper`-level grant/deny does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LocationProviderTest {
    private lateinit var locationManager: LocationManager
    private lateinit var shadowLocationManager: ShadowLocationManager
    private lateinit var provider: LocationProvider

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        locationManager = context.getSystemService(LocationManager::class.java)
        shadowLocationManager = shadowOf(locationManager)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        provider = LocationProvider(context, locationManager)
    }

    private fun fixLocation(
        provider: String = LocationManager.NETWORK_PROVIDER,
        lat: Double = 52.5,
        long: Double = 13.4,
    ): Location =
        Location(provider).apply {
            latitude = lat
            longitude = long
        }

    @Test
    fun `hasPermission is true once ACCESS_COARSE_LOCATION is granted`() {
        assertTrue(provider.hasPermission())
    }

    @Test
    fun `hasPermission is false when the permission is denied`() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertFalse(provider.hasPermission())
    }

    @Test
    fun `getCurrentLocation returns null immediately when permission is missing`() =
        runTest {
            shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

            val result = provider.getCurrentLocation(timeoutMs = 50)

            assertNull(result)
        }

    @Test
    fun `getCurrentLocation prefers a cached last-known fix over waiting for a new one`() =
        runTest {
            shadowLocationManager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            val cached = fixLocation()
            shadowLocationManager.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, cached)

            val result = provider.getCurrentLocation(timeoutMs = 50)

            assertEquals(cached.latitude, result?.latitude)
            assertEquals(cached.longitude, result?.longitude)
        }

    @Test
    fun `getCurrentLocation returns null when no provider is enabled and no fix arrives`() =
        runTest {
            val result = provider.getCurrentLocation(timeoutMs = 200)

            assertNull(result)
        }

    @Test
    fun `getCurrentLocation resolves a fresh fix delivered via requestSingleUpdate`() =
        runTest {
            shadowLocationManager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            val fresh = fixLocation()

            // getCurrentLocation() hops onto a real dispatcher for this path (its own
            // requestSingleUpdate registration + the shadow's callback delivery are real,
            // Looper-based work, not virtual-scheduler-driven) — same "real dispatcher + real-time
            // poll" shape AGENTS.md documents for RadioBrowserApi/StreamValidator's tests.
            val deferred = async(Dispatchers.Default) { provider.getCurrentLocation(timeoutMs = 5000) }
            val deadline = System.currentTimeMillis() + 2000
            while (shadowLocationManager.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).isEmpty() &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(10)
            }
            // requestSingleUpdate was registered against Looper.getMainLooper(), and the shadow
            // delivers simulateLocation()'s callback by posting to that looper — which Robolectric
            // leaves paused by default, so the post needs an explicit idle() to actually run.
            shadowLocationManager.simulateLocation(fresh)
            shadowOf(Looper.getMainLooper()).idle()

            val result = deferred.await()
            assertEquals(fresh.latitude, result?.latitude)
            assertEquals(fresh.longitude, result?.longitude)
        }
}
