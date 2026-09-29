package com.suyaphot.app.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.HkdfSha256
import com.suyaphot.app.core.util.SafeLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Generates and encrypts downsampled thumbnails for images and videos with zero full-file buffering.
 */
class ThumbnailGenerator(private val context: Context) {

    companion object {
        const val TARGET_THUMB_SIZE = 360
        const val JPEG_QUALITY = 80
        const val TARGET_PREVIEW_SIZE = 2560
    }

    private fun previewKey(thumbSubkey: ByteArray): ByteArray = HkdfSha256.derive(
        thumbSubkey, salt = ByteArray(0), info = "suya-phot-preview-key-v1".toByteArray(), length = 32
    )

    fun generateAndEncryptImagePreview(
        imageUri: Uri, itemId: String, thumbSubkey: ByteArray, outputPreviewFile: File,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL
    ): Boolean {
        outputPreviewFile.parentFile?.mkdirs()
        val partial = File(outputPreviewFile.parentFile, "${outputPreviewFile.name}.partial")
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = context.contentResolver.openInputStream(imageUri) ?: return false
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > TARGET_PREVIEW_SIZE) sample *= 2
            var bitmap = context.contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
            } ?: return false
            if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                bitmap = applyExifOrientation(bitmap, orientation)
            }
            val out = ByteArrayOutputStream()
            try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) }
            finally { bitmap.recycle() }
            val plaintext = out.toByteArray()
            val key = previewKey(thumbSubkey)
            val encrypted = try {
                Aead.encryptWithPrependedNonce(key, plaintext, "suya-phot:preview:v1:$itemId".toByteArray())
            } finally { plaintext.fill(0); key.fill(0) }
            try {
                FileOutputStream(partial).use { output ->
                    output.write(encrypted)
                    output.flush()
                    output.fd.sync()
                }
                commitDerivative(partial, outputPreviewFile)
            } finally { encrypted.fill(0) }
            true
        } catch (e: Exception) {
            SafeLog.w("ThumbnailGenerator", "Could not generate encrypted viewer preview")
            partial.delete()
            false
        }
    }

    fun decryptImagePreview(file: File, thumbSubkey: ByteArray, itemId: String): Bitmap? {
        if (!file.exists() || file.length() > 32L * 1024 * 1024) return null
        val key = previewKey(thumbSubkey)
        return try {
            val encrypted = file.readBytes()
            val plain = try {
                Aead.decryptWithPrependedNonce(key, encrypted, "suya-phot:preview:v1:$itemId".toByteArray())
            } finally { encrypted.fill(0) }
            try { BitmapFactory.decodeByteArray(plain, 0, plain.size) }
            finally { plain.fill(0) }
        } catch (_: Exception) { null }
        finally { key.fill(0) }
    }

    /**
     * Generates a downsampled bitmap using two-pass streaming decode (avoiding full-file heap allocations),
     * compresses to JPEG, encrypts with [thumbSubkey] bound to [itemId] AAD, and atomically writes to [outputThumbFile].
     */
    fun generateAndEncryptImageThumbnail(
        imageUri: Uri,
        itemId: String,
        thumbSubkey: ByteArray,
        outputThumbFile: File,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL
    ): Boolean {
        outputThumbFile.parentFile?.mkdirs()
        val partialThumbFile = File(outputThumbFile.parentFile, "${outputThumbFile.name}.partial")

        return try {
            // Pass 1: Decode bounds only
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return false

            val sampleSize = calculateInSampleSize(boundsOptions, TARGET_THUMB_SIZE, TARGET_THUMB_SIZE)

            // Pass 2: Decode downsampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Memory efficient
            }

            var bitmap = context.contentResolver.openInputStream(imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return false

            if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                bitmap = applyExifOrientation(bitmap, orientation)
            }

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            bitmap.recycle()

            val plaintextBytes = baos.toByteArray()
            val encryptedBytes = try {
                Aead.encryptWithPrependedNonce(
                    keyBytes = thumbSubkey,
                    plaintext = plaintextBytes,
                    aad = "suya-phot:thumbnail:v1:$itemId".toByteArray(Charsets.UTF_8)
                )
            } finally {
                plaintextBytes.fill(0)
            }
            try {
                FileOutputStream(partialThumbFile).use { fos ->
                    fos.write(encryptedBytes)
                    fos.flush()
                    runCatching { fos.fd.sync() }
                }
                commitDerivative(partialThumbFile, outputThumbFile)
            } finally {
                encryptedBytes.fill(0)
            }
            true
        } catch (e: Exception) {
            SafeLog.e("ThumbnailGenerator", "Error generating image thumbnail", e)
            if (partialThumbFile.exists()) partialThumbFile.delete()
            false
        }
    }

    /**
     * Extracts a representative video frame using MediaMetadataRetriever,
     * downsamples, encrypts with [thumbSubkey] bound to [itemId] AAD, and writes to [outputThumbFile].
     */
    fun generateAndEncryptVideoThumbnail(
        videoUri: Uri,
        itemId: String,
        thumbSubkey: ByteArray,
        outputThumbFile: File
    ): Boolean {
        val retriever = MediaMetadataRetriever()
        outputThumbFile.parentFile?.mkdirs()
        val partialThumbFile = File(outputThumbFile.parentFile, "${outputThumbFile.name}.partial")

        return try {
            retriever.setDataSource(context, videoUri)

            // Extract frame at 1 second
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime

            if (frame != null) {
                val scaled = Bitmap.createScaledBitmap(
                    frame,
                    TARGET_THUMB_SIZE,
                    (TARGET_THUMB_SIZE * frame.height) / frame.width.coerceAtLeast(1),
                    true
                )
                if (scaled != frame) frame.recycle()

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                scaled.recycle()

                val plaintext = baos.toByteArray()
                val encrypted = try {
                    Aead.encryptWithPrependedNonce(
                        keyBytes = thumbSubkey,
                        plaintext = plaintext,
                        aad = "suya-phot:thumbnail:v1:$itemId".toByteArray(Charsets.UTF_8)
                    )
                } finally {
                    plaintext.fill(0)
                }
                try {
                    FileOutputStream(partialThumbFile).use { fos ->
                        fos.write(encrypted)
                        fos.flush()
                        runCatching { fos.fd.sync() }
                    }
                    commitDerivative(partialThumbFile, outputThumbFile)
                } finally {
                    encrypted.fill(0)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            SafeLog.e("ThumbnailGenerator", "Error generating video thumbnail", e)
            if (partialThumbFile.exists()) partialThumbFile.delete()
            false
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Decrypts an encrypted thumbnail file into a Bitmap for display.
     */
    fun decryptThumbnail(
        thumbFile: File,
        thumbSubkey: ByteArray,
        itemId: String? = null
    ): Bitmap? {
        if (!thumbFile.exists()) return null
        return try {
            val encryptedBytes = thumbFile.readBytes()
            val plaintextBytes = try {
                val aad = if (itemId != null) "suya-phot:thumbnail:v1:$itemId".toByteArray(Charsets.UTF_8) else "thumbnail-v1".toByteArray(Charsets.UTF_8)
                Aead.decryptWithPrependedNonce(
                    keyBytes = thumbSubkey,
                    payload = encryptedBytes,
                    aad = aad
                )
            } catch (e: Exception) {
                // Fallback to legacy AAD for older thumbnails
                Aead.decryptWithPrependedNonce(
                    keyBytes = thumbSubkey,
                    payload = encryptedBytes,
                    aad = "thumbnail-v1".toByteArray(Charsets.UTF_8)
                )
            }
            try {
                BitmapFactory.decodeByteArray(plaintextBytes, 0, plaintextBytes.size)
            } finally {
                plaintextBytes.fill(0)
                encryptedBytes.fill(0)
            }
        } catch (e: Exception) {
            SafeLog.e("ThumbnailGenerator", "Thumbnail decryption failed")
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun commitDerivative(partial: File, final: File) {
        check(!final.exists()) { "Refusing to overwrite encrypted derivative" }
        if (partial.renameTo(final)) return
        val copying = File(final.parentFile, "${final.name}.copying")
        try {
            FileOutputStream(copying).use { output ->
                partial.inputStream().use { it.copyTo(output) }
                output.flush()
                output.fd.sync()
            }
            check(copying.length() == partial.length()) { "Derivative staging size mismatch" }
            check(copying.renameTo(final)) { "Could not commit encrypted derivative" }
            partial.delete()
        } finally {
            copying.delete()
        }
    }
}
