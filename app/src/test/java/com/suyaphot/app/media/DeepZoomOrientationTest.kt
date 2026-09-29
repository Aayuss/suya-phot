package com.suyaphot.app.media

import androidx.exifinterface.media.ExifInterface
import com.suyaphot.app.core.media.DeepZoomOrientationMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepZoomOrientationTest {

    private val rawWidth = 4000
    private val rawHeight = 3000

    @Test
    fun testOrientation1Normal() {
        val (w, h) = DeepZoomOrientationMapper.getDimensions(rawWidth, rawHeight, ExifInterface.ORIENTATION_NORMAL)
        assertEquals(4000, w)
        assertEquals(3000, h)

        val (rx, ry) = DeepZoomOrientationMapper.toRaw(100f, 200f, rawWidth.toFloat(), rawHeight.toFloat(), ExifInterface.ORIENTATION_NORMAL)
        assertEquals(100f, rx, 0.001f)
        assertEquals(200f, ry, 0.001f)
    }

    @Test
    fun testOrientation2FlipHorizontal() {
        val (w, h) = DeepZoomOrientationMapper.getDimensions(rawWidth, rawHeight, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertEquals(4000, w)
        assertEquals(3000, h)

        val (rx, ry) = DeepZoomOrientationMapper.toRaw(100f, 200f, rawWidth.toFloat(), rawHeight.toFloat(), ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertEquals(3900f, rx, 0.001f)
        assertEquals(200f, ry, 0.001f)
    }

    @Test
    fun testOrientation3Rotate180() {
        val (w, h) = DeepZoomOrientationMapper.getDimensions(rawWidth, rawHeight, ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(4000, w)
        assertEquals(3000, h)

        val (rx, ry) = DeepZoomOrientationMapper.toRaw(100f, 200f, rawWidth.toFloat(), rawHeight.toFloat(), ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(3900f, rx, 0.001f)
        assertEquals(2800f, ry, 0.001f)
    }

    @Test
    fun testOrientation4FlipVertical() {
        val (w, h) = DeepZoomOrientationMapper.getDimensions(rawWidth, rawHeight, ExifInterface.ORIENTATION_FLIP_VERTICAL)
        assertEquals(4000, w)
        assertEquals(3000, h)

        val (rx, ry) = DeepZoomOrientationMapper.toRaw(100f, 200f, rawWidth.toFloat(), rawHeight.toFloat(), ExifInterface.ORIENTATION_FLIP_VERTICAL)
        assertEquals(100f, rx, 0.001f)
        assertEquals(2800f, ry, 0.001f)
    }

    @Test
    fun testOrientation5Transpose() {
        val (w, h) = DeepZoomOrientationMapper.getDimensions(rawWidth, rawHeight, ExifInterface.ORIENTATION_TRANSPOSE)
        assertEquals(3000, w)
        assertEquals(4000, h)

        val (rx, ry) = DeepZoomOrientationMapper.toRaw(100f, 200f, rawWidth.toFloat(), rawHeight.toFloat(), ExifInterface.ORIENTATION_TRANSPOSE)
        assertEquals(200f, rx, 0.001f)
        assertEquals(100f, ry, 0.001f)
    }

    @Test
    fun testOrientation6Rotate90() {
        val (w, h) = DeepZoomOrientationMapper.getDimensions(rawWidth, rawHeight, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(3000, w)
        assertEquals(4000, h)

        val (rx, ry) = DeepZoomOrientationMapper.toRaw(100f, 200f, rawWidth.toFloat(), rawHeight.toFloat(), ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(200f, rx, 0.001f)
        assertEquals(2900f, ry, 0.001f)
    }

    @Test
    fun testOrientation7Transverse() {
        val (w, h) = DeepZoomOrientationMapper.getDimensions(rawWidth, rawHeight, ExifInterface.ORIENTATION_TRANSVERSE)
        assertEquals(3000, w)
        assertEquals(4000, h)

        val (rx, ry) = DeepZoomOrientationMapper.toRaw(100f, 200f, rawWidth.toFloat(), rawHeight.toFloat(), ExifInterface.ORIENTATION_TRANSVERSE)
        assertEquals(3800f, rx, 0.001f)
        assertEquals(2900f, ry, 0.001f)
    }

    @Test
    fun testOrientation8Rotate270() {
        val (w, h) = DeepZoomOrientationMapper.getDimensions(rawWidth, rawHeight, ExifInterface.ORIENTATION_ROTATE_270)
        assertEquals(3000, w)
        assertEquals(4000, h)

        val (rx, ry) = DeepZoomOrientationMapper.toRaw(100f, 200f, rawWidth.toFloat(), rawHeight.toFloat(), ExifInterface.ORIENTATION_ROTATE_270)
        assertEquals(3800f, rx, 0.001f)
        assertEquals(100f, ry, 0.001f)
    }

    @Test
    fun testMapRectToRawCoversAllCorners() {
        for (orient in 1..8) {
            val (w, h) = DeepZoomOrientationMapper.getDimensions(rawWidth, rawHeight, orient)
            val rect = DeepZoomOrientationMapper.mapRectToRaw(
                0f, 0f, w.toFloat(), h.toFloat(),
                rawWidth.toFloat(), rawHeight.toFloat(),
                orient
            )
            assertEquals("Orientation $orient minX", 0f, rect[0], 0.001f)
            assertEquals("Orientation $orient minY", 0f, rect[1], 0.001f)
            assertEquals("Orientation $orient maxX", rawWidth.toFloat(), rect[2], 0.001f)
            assertEquals("Orientation $orient maxY", rawHeight.toFloat(), rect[3], 0.001f)
        }
    }
}
