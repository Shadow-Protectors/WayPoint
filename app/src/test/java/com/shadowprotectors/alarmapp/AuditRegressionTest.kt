package com.shadowprotectors.alarmapp

import android.content.Context
import android.media.AudioManager
import android.view.View
import android.widget.TextView
import com.shadowprotectors.alarmapp.alert.AudioAlarmHelper
import com.shadowprotectors.alarmapp.alert.AlertLevel
import com.shadowprotectors.alarmapp.service.ServiceEventBus
import com.shadowprotectors.alarmapp.service.TrackingState
import com.shadowprotectors.alarmapp.util.LocationLinkParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuditRegressionTest {
    @org.junit.After fun closeDatabase() {
        val field = com.shadowprotectors.alarmapp.data.AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        (field.get(null) as? com.shadowprotectors.alarmapp.data.AppDatabase)?.close()
        field.set(null, null)
        com.shadowprotectors.alarmapp.service.ServiceEventBus.resetState()
    }

    @Test fun usageAndRenamePreserveEachOthersChanges() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            com.shadowprotectors.alarmapp.data.AppDatabase::class.java).build()
        try {
            val dao = db.savedLocationDao()
            val id = dao.insert(com.shadowprotectors.alarmapp.data.SavedLocation(customName = "Home", latitude = 9.92, longitude = 78.11))
            dao.recordUsage(id, 100L)
            dao.rename(id, "Office")
            dao.recordUsage(id, 200L)
            val saved = dao.getById(id)!!
            assertEquals("Office", saved.customName)
            assertEquals(2, saved.useCount)
            assertEquals(200L, saved.lastUsedAt)
        } finally { db.close() }
    }

    @Test fun legacyDatabaseMigrationPreservesSavedDestination() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "audit_legacy.db"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL("CREATE TABLE destinations (name TEXT, latitude REAL, longitude REAL, created_at INTEGER)")
            legacy.execSQL("INSERT INTO destinations VALUES ('Home', 9.92, 78.11, 123)")
            legacy.version = 1
        }
        val db = androidx.room.Room.databaseBuilder(context,
            com.shadowprotectors.alarmapp.data.AppDatabase::class.java, name)
            .addMigrations(com.shadowprotectors.alarmapp.data.AppDatabase.MIGRATION_1_2).build()
        try {
            val saved = db.savedLocationDao().getAllLocations().single()
            assertEquals("Home", saved.customName)
            assertEquals(9.92, saved.latitude, 0.0)
            assertEquals(123L, saved.createdAt)
        } finally { db.close() }
    }

    @Test fun serviceRejectsInvalidDestinationAndStopsOrphanCommands() {
        val controller = Robolectric.buildService(com.shadowprotectors.alarmapp.service.LocationTrackingService::class.java).create()
        val service = controller.get()
        val invalid = android.content.Intent().setAction(com.shadowprotectors.alarmapp.service.LocationTrackingService.ACTION_START)
            .putExtra(com.shadowprotectors.alarmapp.service.LocationTrackingService.EXTRA_DEST_LAT, Double.NaN)
        service.onStartCommand(invalid, 0, 1)
        assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf)
        assertFalse(ServiceEventBus.trackingState.value.isTracking)
        controller.destroy()
    }

    @Test fun importedCoordinatesSupportIntegersAndGeoQueryTargets() = runBlocking {
        assertEquals(0.0, LocationLinkParser.parse("0,0")!!.latitude, 0.0)
        val geo = LocationLinkParser.parse("geo:0,0?q=9.92%2C78.11(Home)")!!
        assertEquals(9.92, geo.latitude, 0.0)
        assertEquals(78.11, geo.longitude, 0.0)
        assertNull(LocationLinkParser.parse("geo:91,180"))
        assertNull(LocationLinkParser.parse("https://maps.google.com/?q=9.92,181"))
        assertNull(LocationLinkParser.parse("1234.123,78.11"))
    }

    @Test fun importsPreferDestinationOverViewportOrRouteOrigin() = runBlocking {
        val point = LocationLinkParser.parse("https://www.google.com/maps/place/Home/@10.5,79.5,12z/data=!3d9.92!4d78.11")!!
        assertEquals(9.92, point.latitude, 0.0)
        val route = LocationLinkParser.parse("https://www.google.com/maps/dir/?origin=12,80&destination=9.92%2C78.11")!!
        assertEquals(78.11, route.longitude, 0.0)
        assertNull(LocationLinkParser.parse("https://maps.google.com/@10,79,12z?destination=NamedPlace"))
    }

    @Test fun expansionRejectsUntrustedHostsAndDowngrades() {
        for (url in listOf("https://evil.test/maps.app.goo.gl", "https://maps.app.goo.gl.evil.test/x",
            "http://maps.app.goo.gl/x", "https://127.0.0.1/x", "https://google.com@evil.test/x",
            "https://www.google.com:8443/x")) assertFalse(url, LocationLinkParser.isAllowedExpansionUrl(url))
        assertTrue(LocationLinkParser.isAllowedExpansionUrl("https://maps.app.goo.gl/example"))
        assertTrue(LocationLinkParser.isAllowedExpansionUrl("https://www.google.com/maps?q=9,78"))
    }

    @Test fun stoppingTripClearsAlarmBadgeAndEta() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val render = MainActivity::class.java.getDeclaredMethod("updateUiFromTrackingState", TrackingState::class.java).apply { isAccessible = true }
        val active = TrackingState(isTracking = true, destinationName = "Home", distanceKm = 0.4,
            etaMinutes = 1, alertLevel = AlertLevel.LEVEL_4_FULL_ALARM)
        ServiceEventBus.updateState(active)
        render.invoke(activity, active)
        assertFalse(activity.findViewById<TextView>(R.id.tvStatus).text.contains("Reached"))
        ServiceEventBus.resetState()
        render.invoke(activity, TrackingState())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.tvAlertLevelBadge).visibility)
        assertEquals("--:--", activity.findViewById<TextView>(R.id.tvEta).text.toString())
        controller.pause().stop().destroy()
    }

    @Test fun alarmRestoresVolumeAndLeavesOtherStreamsUnchanged() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        val music = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        AudioAlarmHelper.startFullAlarm(context)
        assertEquals(music, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
        AudioAlarmHelper.stopFullAlarm(context)
        assertEquals(2, manager.getStreamVolume(AudioManager.STREAM_ALARM))
    }
}
