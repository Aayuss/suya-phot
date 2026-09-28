package com.suyaphot.app.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.util.SafeLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Generates and encrypts downsampled thumbnails for images and videos.
 */
class ThumbnailGenerator(private val context: Context) {

    companion object {
        const val TARGET_THUMB_SIZE = 360
        const val JPEG_QUALITY = 80
    }

    /**
     * Generates a downsampled bitmap from an image stream, compresses it to JPEG,
     * encrypts with [thumbSubkey] using AES-256-GCM, and writes to [outputThumbFile].
     */
    fun generateAndEncryptImageThumbnail(
        imageStream: InputStream,
        thumbSubkey: ByteArray,
        outputThumbFile: File,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL
    ): Boolean {
        return try {
            outputThumbFile.parentFile?.mkdirs()

            val bytes = imageStream.readBytes()
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

            val sampleSize = calculateInSampleSize(boundsOptions, TARGET_THUMB_SIZE, TARGET_THUMB_SIZE)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Memory efficient
            }

            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return false

            if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                bitmap = applyExifRotation(bitmap, orientation)
            }

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            bitmap.recycle()

            val plaintextBytes = baos.toByteArray()
            val encryptedBytes = Aead.encryptWithPrependedNonce(
                keyBytes = thumbSubkey,
                plaintext = plaintextBytes,
                aad = "thumbnail-v1".toByteArray(Charsets.UTF_8)
            )

            FileOutputStream(outputThumbFile).use { fos ->
                fos.write(encryptedBytes)
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            true
        } catch (e: Exception) {
            SafeLog.e("ThumbnailGenerator", "Error generating image thumbnail", e)
            false
        }
    }

    /**
     * Extracts a representative video frame using MediaMetadataRetriever,
     * downsamples, encrypts with [thumbSubkey], and writes to [outputThumbFile].
     */
    fun generateAndEncryptVideoThumbnail(
        videoUri: Uri,
        thumbSubkey: ByteArray,
        outputThumbFile: File
    ): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            outputThumbFile.parentFile?.mkdirs()
            retriever.setDataSource(context, videoUri)

            // Extract frame at 1 second
            var frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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

                val encrypted = Aead.encryptWithPrependedNonce(
                    keyBytes = thumbSubkey,
                    plaintext = baos.toByteArray(),
                    aad = "thumbnail-v1".toByteArray(Charsets.UTF_8)
                )

                FileOutputStream(outputThumbFile).use { fos ->
                    fos.write(encrypted)
                    fos.flush()
                    runCatching { fos.fd.sync() }
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            SafeLog.e("ThumbnailGenerator", "Error generating video thumbnail", e)
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
        thumbSubkey: ByteArray
    ): Bitmap? {
        if (!thumbFile.exists()) return null
        return try {
            val encryptedBytes = thumbFile.readBytes()
            val plaintextBytes = Aead.decryptWithPrependedNonce(
                keyBytes = thumbSubkey,
                payload = encryptedBytes,
                aad = "thumbnail-v1".toByteArray(Charsets.UTF_8)
            )
            BitmapFactory.decodeByteArray(plaintextBytes, 0, plaintextBytes.size)
        } catch (e: Exception) {
            SafeLog.e("ThumbnailGenerator", "Failed decrypting thumbnail: ${thumbFile.name}", e)
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

    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }
}
