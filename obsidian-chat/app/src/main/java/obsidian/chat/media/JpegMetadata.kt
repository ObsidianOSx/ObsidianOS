package obsidian.chat.media

/**
 * Finds the JPEG segments that carry metadata about a photo: where it was taken, on what, when, and
 * any captions. Re-encoding a photo is meant to drop all of it, and [privacySegments] is the check
 * that it really did before the photo leaves the phone.
 */
object JpegMetadata {
    private const val MARKER = 0xFF
    private const val START_OF_IMAGE = 0xD8
    private const val START_OF_SCAN = 0xDA
    private const val END_OF_IMAGE = 0xD9
    private const val APP1 = 0xE1 // Exif (GPS, camera, timestamps) and XMP
    private const val APP13 = 0xED // IPTC/Photoshop
    private const val COMMENT = 0xFE

    /** Names of any metadata segments present; empty means the photo carries none. */
    fun privacySegments(jpeg: ByteArray): List<String> {
        val found = mutableListOf<String>()
        if (jpeg.size < 4 || jpeg.byteAt(0) != MARKER || jpeg.byteAt(1) != START_OF_IMAGE) return listOf("not a JPEG")
        var i = 2
        while (i + 3 < jpeg.size) {
            if (jpeg.byteAt(i) != MARKER) break // entropy-coded image data begins
            when (val marker = jpeg.byteAt(i + 1)) {
                START_OF_SCAN, END_OF_IMAGE -> return found // metadata only appears before the pixels
                in 0xD0..0xD7, START_OF_IMAGE, 0x01 -> i += 2 // markers without a payload
                else -> {
                    when (marker) {
                        APP1 -> found += if (jpeg.textAt(i + 4, 4) == "Exif") "Exif" else "XMP"
                        APP13 -> found += "IPTC"
                        COMMENT -> found += "comment"
                    }
                    i += 2 + ((jpeg.byteAt(i + 2) shl 8) or jpeg.byteAt(i + 3))
                }
            }
        }
        return found
    }

    private fun ByteArray.byteAt(index: Int) = this[index].toInt() and 0xFF

    private fun ByteArray.textAt(index: Int, length: Int): String? =
        if (index + length > size) null else String(this, index, length, Charsets.US_ASCII)
}
