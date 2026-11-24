package dev.aalhdydy550.tunnel.feature.profiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.aalhdydy550.tunnel.core.data.profile.QrCodeDecoder

/**
 * Thin Android adapter between a [Bitmap]/gallery [Uri] and the pure
 * JVM [QrCodeDecoder] — this file only moves pixels around, all
 * decoding logic (and its tests) lives in `:core:data`.
 *
 * Memory bound on purpose: a gallery photo can be 12 MP, and the
 * target phone has 2 GB RAM. Images are downsampled so the decoded
 * side never exceeds [MAX_DECODE_DIMENSION] — QR decoding stays
 * reliable well below full camera resolution, and the pixel array is
 * at most ~4 MB. This adapter never holds a full-size bitmap.
 */
object QrCodeBitmapDecoder {

    /** Longest decoded side, in pixels. */
    const val MAX_DECODE_DIMENSION = 1024

    /**
     * Decodes the QR code in [bitmap], or null when there is none.
     * Never recycles the caller's bitmap; an intermediate downscaled
     * copy (when one is made) is recycled here.
     */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val scaled = downscale(bitmap)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val result = QrCodeDecoder.decode(pixels, scaled.width, scaled.height)
        if (scaled !== bitmap) scaled.recycle()
        return result
    }

    /**
     * Decodes the QR code in the image at [uri] (gallery pick), or
     * null when the image cannot be read or holds no QR code. The
     * image is sampled down before decoding (see [MAX_DECODE_DIMENSION]).
     */
    fun decodeUri(context: Context, uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        try {
            return decodeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Returns [bitmap] itself when small enough, else a scaled copy. */
    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DECODE_DIMENSION) return bitmap
        val scale = MAX_DECODE_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** Power-of-two sample size so the decoded side fits the cap. */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > MAX_DECODE_DIMENSION) sample *= 2
        return sample
    }
}
