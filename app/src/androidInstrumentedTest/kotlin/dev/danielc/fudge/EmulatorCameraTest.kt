package dev.danielc.fudge

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.danielc.fudge.screens.UiEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
class EmulatorCameraTest {
    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private fun assertGalleryThumbnail(mod: ModuleInstance) {
        val galleryAvailable = runBlocking {
            withTimeoutOrNull(15000.milliseconds) {
                mod.homeModelView.homeState.first { Screen.FILE_GALLERY in it.supportedNavBarScreenList }
            } != null
        }
        if (!galleryAvailable) fail("Camera does not expose a gallery: ${mod.homeModelView.homeState.value}; ${mod.getVerboseLog()}")
        runBlocking {
            val rc = mod.switchScreen(Screen.FILE_GALLERY, true, {})
            if (rc.failed) fail("Failed to open gallery: $rc")
        }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val filesystem = mod.galleryViewModel.currentFilesystemStateFlow.value
            if (filesystem?.objects?.any { it.thumbnail != null } == true) {
                println("Gallery thumbnail decoded successfully")
                return
            }
            Thread.sleep(100)
        }
        val filesystem = mod.galleryViewModel.currentFilesystemStateFlow.value
        fail("No decoded gallery thumbnail: files=${filesystem?.nFiles}, " +
            "objects=${filesystem?.objects?.size}, invalid=${filesystem?.objects?.count { it.invalidThumbnail }}")
    }

    @Test
    fun connectsToFujiCamera() {
        var mod: ModuleInstance? = null
        Runtime.moduleInstanceCallback = { mod = it }
        activityRule.scenario.onActivity {
            startModule("libfuji", "wifi")
        }

        fun waitForCondition(ms: Int, block: () -> Boolean): Boolean {
            val n = ms / 10
            for (i in 0..n) {
                if (block()) break
                Thread.sleep(10)
                if (i >= n - 1) return true
            }
            return false
        }

        if (waitForCondition(1000) { mod != null }) fail("Module instance creation timeout")
        if (mod == null) { fail("") } else {
            if (waitForCondition(15000) { mod.isConnected }) fail("Connecting timeout")
            if (waitForCondition(1000) { mod.currentScreen == Screen.DASHBOARD }) fail("Dashboard screen timeout")
            println("debug log: ${mod.getVerboseLog()}")
            val screens = mod.homeModelView.homeState.value.supportedNavBarScreenList
            for (s in (screens + screens)) { // switch through all screens twice
                runBlocking {
                    mod.switchScreen(s, true, {})
                    if (s == Screen.FILE_GALLERY) {
                        // Pull in files for a little bit
                        Thread.sleep(1000)
                    }
                }
            }
            assertGalleryThumbnail(mod)
        }
    }

    @Test
    fun connectFujiBluetooth() {
        var mod: ModuleInstance? = null
        Runtime.moduleInstanceCallback = { mod = it }
        activityRule.scenario.onActivity {
            startModule("libfuji", "bluetooth")
        }

        fun waitForCondition(ms: Int, block: () -> Boolean): Boolean {
            val n = ms / 10
            for (i in 0..n) {
                if (block()) break
                Thread.sleep(10)
                if (i >= n - 1) return true
            }
            return false
        }

        if (waitForCondition(1000) { mod != null }) fail("Module instance creation timeout")
        if (mod == null) { fail("") } else {
            if (waitForCondition(30000) { mod.isConnected }) fail("Connecting timeout")
            if (waitForCondition(1000) { mod.currentScreen == Screen.DASHBOARD }) fail("Dashboard screen timeout")
            println(mod.getVerboseLog())
            runBlocking {
                val events = Channel<UiEvent>(Channel.UNLIMITED)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    mod.homeModelView.uiEvents.collect { events.send(it) }
                }
                suspend fun waitForEvent(timeout: Long, matches: (UiEvent) -> Boolean): Boolean =
                    withTimeoutOrNull(timeout.milliseconds) {
                        while (true) {
                            if (matches(events.receive())) return@withTimeoutOrNull true
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    } ?: false
                try {
                    val rc = mod.propChanged(Widget.Button(Widget.Properties("switch-wifi", "")))
                    if (rc != PakReturnCode.NONE) fail("propChanged nonzero $rc")
                    // TODO: Expose 'connect' events and possibly other events so it can be consumed by tests
                    if (!waitForEvent(15000) { it.type == UiEvent.UiEventType.SWITCH_SCREEN && it.screen == Screen.CONNECT_SECONDARY })
                        fail("Failed to enter secondary connect screen")
                    if (!waitForEvent(30000) { it.type == UiEvent.UiEventType.GO_BACK_SCREEN })
                        fail("Failed to complete secondary camera connection")
                } finally {
                    collector.cancel()
                    events.close()
                    println(mod.getVerboseLog())
                }
            }
            assertGalleryThumbnail(mod)
        }
    }
}
