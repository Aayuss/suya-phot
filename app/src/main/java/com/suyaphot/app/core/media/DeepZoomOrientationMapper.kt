package com.suyaphot.app.core.media

import androidx.exifinterface.media.ExifInterface

object DeepZoomOrientationMapper {
    fun getDimensions(rawW: Int, rawH: Int, orientation: Int): Pair<Int, Int> {
        val rotated = orientation in 5..8
        val width = if (rotated) rawH else rawW
        val height = if (rotated) rawW else rawH
        return width to height
    }

    fun toRaw(x: Float, y: Float, rawW: Float, rawH: Float, orientation: Int): Pair<Float, Float> =
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> rawW - x to y
            ExifInterface.ORIENTATION_ROTATE_180 -> rawW - x to rawH - y
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> x to rawH - y
            ExifInterface.ORIENTATION_TRANSPOSE -> y to x
            ExifInterface.ORIENTATION_ROTATE_90 -> y to rawH - x
            ExifInterface.ORIENTATION_TRANSVERSE -> rawW - y to rawH - x
            ExifInterface.ORIENTATION_ROTATE_270 -> rawW - y to x
            else -> x to y
        }

    fun mapRectToRaw(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rawW: Float,
        rawH: Float,
        orientation: Int
    ): List<Float> {
        val corners = listOf(
            toRaw(left, top, rawW, rawH, orientation),
            toRaw(right, top, rawW, rawH, orientation),
            toRaw(left, bottom, rawW, rawH, orientation),
            toRaw(right, bottom, rawW, rawH, orientation)
        )
        val minX = corners.minOf { it.first }.coerceIn(0f, rawW)
        val minY = corners.minOf { it.second }.coerceIn(0f, rawH)
        val maxX = corners.maxOf { it.first }.coerceIn(0f, rawW)
        val maxY = corners.maxOf { it.second }.coerceIn(0f, rawH)
        return listOf(minX, minY, maxX, maxY)
    }
}
