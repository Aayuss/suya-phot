package com.suyaphot.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline profile generator covering startup, unlock, gallery scrolling, tab navigation, and viewer.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generateBaselineProfile() = rule.collect(packageName = "com.suyaphot.app") {
        startActivityAndWait()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Unlock if PIN pad is visible
        if (device.hasObject(By.desc("Digit 1"))) {
            listOf("1", "2", "3", "4", "5", "6").forEach { digit ->
                device.findObject(By.desc("Digit $digit"))?.click()
                Thread.sleep(100)
            }
        }

        if (device.wait(Until.hasObject(By.res("photos_grid")), 5_000)) {
            val grid = device.findObject(By.res("photos_grid"))
            grid?.setGestureMargin(device.displayWidth / 5)
            grid?.fling(Direction.DOWN)
            grid?.fling(Direction.UP)

            // Navigate through tabs
            device.findObject(By.desc("Folders"))?.click()
            Thread.sleep(500)
            device.findObject(By.desc("Security"))?.click()
            Thread.sleep(500)
            device.findObject(By.desc("Settings"))?.click()
            Thread.sleep(500)
            device.findObject(By.desc("Photos"))?.click()
            Thread.sleep(500)

            // Open viewer if media item exists
            if (device.hasObject(By.desc("Media item"))) {
                device.findObject(By.desc("Media item"))?.click()
                device.wait(Until.hasObject(By.desc("Back")), 3_000)
                device.findObject(By.desc("Back"))?.click()
            }
        }
    }
}
