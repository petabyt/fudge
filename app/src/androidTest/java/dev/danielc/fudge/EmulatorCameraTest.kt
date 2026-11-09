package dev.danielc.fudge

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EmulatorCameraTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun connectsToFujiCamera() {
        val log = java.lang.Runtime.getRuntime().exec(arrayOf("logcat", "-T", "1", "-s", "pak_debug_log:D", "*:S"))
        val lines = LinkedBlockingQueue<String>()
        val reader = Thread {
            try {
                log.inputStream.bufferedReader().useLines { sequence ->
                    sequence.forEach { lines.put(it) }
                }
            } catch (_: Exception) {
                // Closing the descriptor ends the reader when the test finishes.
            }
        }
        reader.isDaemon = true
        reader.start()
        try {
            activityRule.scenario.onActivity { startModule("libfuji", "wifi") }
            // TODO: Wait on Runtime.moduleInstances.size != 0
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) fail("Fuji camera session not established within 120 seconds")
                val line = lines.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
                if (line.contains("Failed to initialize connection") || line.contains("Failed to open session")) {
                    fail("Fuji camera initialization failed: $line")
                }
                if (line.contains("Configuring version properties")) return
            }
        } finally {
            log.destroy()
        }
    }
}
