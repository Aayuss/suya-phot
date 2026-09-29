package com.suyaphot.benchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

object BenchmarkSeeder {
    fun ensureVaultReady(device: UiDevice) {
        if (device.hasObject(By.res("photos_grid"))) return

        // 1. If fresh install at setup screen
        if (device.hasObject(By.text("Get Started"))) {
            device.findObject(By.text("Get Started"))?.click()
            device.wait(Until.hasObject(By.text("PIN")), 5_000)
            device.findObject(By.text("PIN"))?.click()
            Thread.sleep(500)
        }

        // 2. PIN setup or unlock (default deterministic PIN: 123456)
        if (device.hasObject(By.desc("Digit 1"))) {
            listOf("1", "2", "3", "4", "5", "6").forEach { digit ->
                device.findObject(By.desc("Digit $digit"))?.click()
                Thread.sleep(100)
            }
        }

        // 3. Confirm PIN if on SetupScreen
        if (device.wait(Until.hasObject(By.text("Confirm your PIN")), 2_000)) {
            listOf("1", "2", "3", "4", "5", "6").forEach { digit ->
                device.findObject(By.desc("Digit $digit"))?.click()
                Thread.sleep(100)
            }
        }

        // 4. Continue through recovery kit if present
        if (device.wait(Until.hasObject(By.text("Continue")), 2_000)) {
            device.findObject(By.text("Continue"))?.click()
        }

        check(
            device.wait(
                Until.hasObject(By.res("photos_grid")),
                10_000
            )
        ) {
            "Benchmark fixture did not reach gallery"
        }
    }
}
