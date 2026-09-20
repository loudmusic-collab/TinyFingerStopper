package com.loudmusic.tinyfingerstopper

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restart must always cancel the lock.
 *
 * That is the one requirement where getting it wrong hands someone a phone they
 * cannot use, so it is enforced here rather than left to be remembered. These
 * tests read the source, on purpose: they are tripwires on the three things that
 * would quietly make the lock survive a reboot.
 */
class LockSafetyTest {

    private val moduleDir: File = generateSequence(File(System.getProperty("user.dir")!!)) {
        it.parentFile
    }.first { File(it, "src/main/AndroidManifest.xml").exists() }

    private val manifest: String
        get() = File(moduleDir, "src/main/AndroidManifest.xml").readText()

    private fun kotlinSources(): List<File> =
        File(moduleDir, "src/main").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `nothing in the app runs at boot`() {
        // RECEIVE_BOOT_COMPLETED or a BOOT_COMPLETED receiver would give the app a
        // way to come back after a restart, which is exactly what must not happen.
        assertFalse(
            "AndroidManifest.xml mentions BOOT_COMPLETED. The lock must not survive " +
                "a reboot, so the app must never be started by one. See BootSafety.kt.",
            manifest.contains("BOOT_COMPLETED"),
        )
    }

    @Test
    fun `no service asks the system to restart it`() {
        // START_STICKY is the trap: the system would recreate the service after a
        // low-memory kill or a crash and, without care, re-arm a lock nobody asked for.
        val offenders = kotlinSources().filter {
            val text = it.readText()
            text.contains("START_STICKY") || text.contains("START_REDELIVER_INTENT")
        }
        assertTrue(
            "These files ask the system to restart a service: " +
                "${offenders.map { it.name }}. Services here must return " +
                "START_NOT_STICKY so an armed lock is never resurrected. See BootSafety.kt.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the lock service refuses to re-arm on a system restart`() {
        val source = kotlinSources().single { it.name == "OverlayLockService.kt" }.readText()
        assertTrue(
            "OverlayLockService must return START_NOT_STICKY.",
            source.contains("START_NOT_STICKY"),
        )
        assertTrue(
            "OverlayLockService must stop itself when onStartCommand is handed a null " +
                "intent, which is how the system signals that it restarted the service " +
                "on its own. Without that check, a kill could silently re-arm the lock.",
            source.contains("if (intent == null)"),
        )
    }

    @Test
    fun `preferences hold settings only, never lock state`() {
        // Prefs is the only thing here allowed to touch disk. Pinning the exact set
        // of stored keys means adding one is a deliberate act with this test in view,
        // rather than something that quietly gives the lock a way to outlive a reboot.
        val prefs = kotlinSources().single { it.name == "Prefs.kt" }.readText()
        val stored = Regex("private const val KEY_[A-Z_]+ = \"([a-z_]+)\"")
            .findAll(prefs)
            .map { it.groupValues[1] }
            .toSortedSet()
        assertEquals(
            "The set of preferences written to disk changed. Whether the lock is " +
                "armed must stay in memory only, so that a restart clears it. If the " +
                "new key really is just a setting, add it here.",
            sortedSetOf(
                "auto_unlock_minutes",
                "hold_millis",
                "keep_screen_on",
                "last_video_id",
                "pin_hash",
                "snap_back",
            ),
            stored,
        )
    }
}
