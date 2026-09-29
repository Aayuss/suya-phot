package com.suyaphot.app.database

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.media.ThumbnailGenerator
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EncryptedPreviewTest {
    @Test fun previewIsBoundToItemAndNeverDecodesFullSourceDimensions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("preview-source", ".jpg", context.cacheDir)
        val encrypted = File.createTempFile("preview-target", ".spr", context.cacheDir)
        encrypted.delete()
        val key = ByteArray(32) { 4 }
        try {
            val bitmap = Bitmap.createBitmap(3500, 1800, Bitmap.Config.RGB_565)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
            val generator = ThumbnailGenerator(context)
            assertTrue(generator.generateAndEncryptImagePreview(Uri.fromFile(source), "media-a", key, encrypted))
            assertNull(generator.decryptImagePreview(encrypted, key, "media-b"))
            val decoded = generator.decryptImagePreview(encrypted, key, "media-a")!!
            assertTrue(maxOf(decoded.width, decoded.height) <= ThumbnailGenerator.TARGET_PREVIEW_SIZE)
            assertTrue(decoded.width > 0 && decoded.height > 0)
            decoded.recycle()
        } finally {
            key.fill(0)
            source.delete()
            encrypted.delete()
        }
    }
}
