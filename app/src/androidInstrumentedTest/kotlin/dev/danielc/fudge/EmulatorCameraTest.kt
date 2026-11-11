package dev.danielc.fudge

import android.Manifest
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorCameraTest {
//    @get:Rule(order = 0)
//    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
//        Manifest.permission.BLUETOOTH_SCAN,
//        Manifest.permission.BLUETOOTH_CONNECT,
//        Manifest.permission.NEARBY_WIFI_DEVICES,
//        Manifest.permission.ACCESS_WIFI_STATE,
//        Manifest.permission.CHANGE_WIFI_STATE,
//    )

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

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
            runBlocking {
                mod.switchScreen(Screen.FILE_GALLERY, true, {})
                mod.galleryViewModel.goToViewer(FileHandle(0))
            }
//            val screens = mod.homeModelView.homeState.value.supportedNavBarScreenList
//            for (s in screens) {
//                runBlocking {
//                    mod.switchScreen(s, true, {})
//                }
//            }
        }
    }
}
