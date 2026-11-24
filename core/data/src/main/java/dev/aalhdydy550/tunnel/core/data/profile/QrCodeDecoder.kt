package dev.aalhdydy550.tunnel.core.data.profile

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * QR decoding with ZXing core (Apache-2.0, pure Java).
 *
 * Batch 1, item #1 (§4-#1 in the hamo-like-apps report): QR import
 * for proxy configs. ZXing was chosen over ML Kit deliberately —
 * ML Kit needs a model download and extra memory, both rejected on
 * the target phone (Infinix Smart 5, Android 10 Go, 2 GB RAM).
 *
 * Pure Kotlin/JVM: no Android dependency, so this is unit-tested on
 * the JVM (see `QrCodeDecoderTest`). The Android side (Bitmap → pixel
 * array) is a thin adapter in `:feature:profiles`
 * (`QrCodeBitmapDecoder`); the camera/gallery UI is `QrImportSection`.
 *
 * A decoded QR never enters the app on its own: the caller must route
 * the text through [isImportableQrText] and then the existing import
 * path — a proxy link via `ProfileLinkParser` (same as a manual
 * paste) or a subscription URL via `SubscriptionFetcher`. Anything
 * else is rejected, so a QR pointing at a random website or plain
 * text can never create a profile.
 */
object QrCodeDecoder {

    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    /**
     * Decodes the QR code in an ARGB pixel array, or null when there
     * is no readable QR code in the image (no code in frame, damaged
     * code, or undecodable pixels). Never throws for bad image data.
     */
    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        return decodeSource(RGBLuminanceSource(width, height, pixels))
    }

    /** Decodes from any ZXing luminance source (tests, custom inputs). */
    fun decode(source: LuminanceSource): String? = decodeSource(source)

    private fun decodeSource(source: LuminanceSource): String? {
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            // NotFoundException (no QR in frame), ChecksumException
            // and FormatException (damaged code) all mean the same
            // thing to the caller: "no readable QR here".
            null
        }
    }

    /**
     * Whether scanned [text] can go through the existing import path:
     * one or more proxy links ([ProfileLinkParser] — the same parser
     * a manual paste uses) or a fetchable subscription URL
     * ([SubscriptionFetcher]). Anything else — a website, plain text,
     * a Wi-Fi QR — is rejected instead of being stored.
     */
    fun isImportableQrText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (SubscriptionFetcher.isAllowedSubscriptionUrl(trimmed)) return true
        return ProfileLinkParser.parseText(trimmed).profiles.isNotEmpty()
    }
}
