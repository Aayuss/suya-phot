package com.suyaphot.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on a disposable, seeded, unlocked physical device for meaningful frame data. */
@RunWith(AndroidJUnit4::class)
class GalleryMacrobenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    private val target = "com.suyaphot.app"

    @Test fun coldStartup() = rule.measureRepeated(
        packageName = target,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD
    ) { startActivityAndWait() }

    @Test fun photosGridScroll() = rule.measureRepeated(
        packageName = target,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM
    ) {
        startActivityAndWait()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assumeTrue("Seed and unlock a disposable vault before measuring", device.wait(Until.hasObject(By.res("photos_grid")), 10_000))
        val grid = device.findObject(By.res("photos_grid"))
        grid.setGestureMargin(device.displayWidth / 5)
        repeat(5) { grid.fling(Direction.DOWN) }
    }

    @Test fun viewerSwipe() = rule.measureRepeated(
        packageName = target,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM
    ) {
        startActivityAndWait()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assumeTrue("Seed two media items and unlock first", device.wait(Until.hasObject(By.desc("Media item")), 10_000))
        device.findObject(By.desc("Media item")).click()
        device.wait(Until.hasObject(By.desc("Back")), 10_000)
        device.swipe(device.displayWidth * 4 / 5, device.displayHeight / 2,
            device.displayWidth / 5, device.displayHeight / 2, 30)
    }
}
