package obsidian.chat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Turns a chosen photo into JPEG bytes that are nothing but pixels. Decoding and re-encoding drops
 * EXIF (where it was taken, on what, and when), XMP and IPTC, while keeping the rotation the EXIF
 * asked for. Large photos are scaled down so they travel over Tor in reasonable time.
 */
object PhotoScrubber {
    private const val MAX_EDGE = 2048
    private const val QUALITY = 85

    /** [removed] names what the original carried and this copy no longer does. */
    class Photo(val jpeg: ByteArray, val bitmap: Bitmap, val removed: List<String>)

    fun scrub(context: Context, uri: Uri): Photo {
        val original = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not read that photo")
        val removed = JpegMetadata.privacySegments(original).filterNot { it == "not a JPEG" }
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software bitmaps can be re-encoded; hardware ones cannot
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longestEdge = maxOf(info.size.width, info.size.height)
            if (longestEdge > MAX_EDGE) {
                val scale = MAX_EDGE.toDouble() / longestEdge
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        val jpeg = out.toByteArray()

        // Fail closed: never send a photo that still carries anything about where it came from
        val leftovers = JpegMetadata.privacySegments(jpeg)
        check(leftovers.isEmpty()) { "This photo still carries ${leftovers.joinToString()}, so it was not sent" }
        return Photo(jpeg, bitmap, removed)
    }
}
