package obsidian.chat.update

import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Hands a downloaded update to the part of Android that installs it.
 *
 * These phones carry two copies of the operating system. The update is written into the half that
 * is not running, while the phone stays usable, and the next restart starts the new one. If that
 * copy fails to start, the phone falls back to the old one by itself, so a bad update cannot leave
 * someone holding a phone that will not turn on.
 *
 * update_engine checks the update's signature against a key built into the phone before it writes
 * anything. That check is the one that matters: it does not care where the file came from, so a
 * file that has been tampered with is refused even if everything else has been fooled.
 */
class SystemUpdater {
    sealed interface Progress {
        data class Working(val percent: Int, val phase: String) : Progress
        data object NeedsRestart : Progress
    }

    /**
     * Writes [otaZip] into the spare half of the phone. Returns when the phone is ready to restart
     * into it, or throws if update_engine refused it.
     */
    suspend fun apply(otaZip: File, onProgress: (Progress) -> Unit) {
        val payload = locatePayload(otaZip)
        val engine = UpdateEngine()
        try {
            suspendCancellableCoroutine { continuation ->
                val callback = object : UpdateEngineCallback() {
                    override fun onStatusUpdate(status: Int, percent: Float) {
                        val phase = when (status) {
                            STATUS_DOWNLOADING -> "Writing the update"
                            STATUS_VERIFYING -> "Checking what was written"
                            STATUS_FINALIZING -> "Finishing"
                            STATUS_UPDATED_NEED_REBOOT -> "Ready"
                            else -> "Preparing"
                        }
                        onProgress(Progress.Working((percent * 100).toInt().coerceIn(0, 100), phase))
                    }

                    override fun onPayloadApplicationComplete(errorCode: Int) {
                        if (continuation.isActive) {
                            if (errorCode == ERROR_SUCCESS || errorCode == ERROR_UPDATED_BUT_NOT_ACTIVE) {
                                onProgress(Progress.NeedsRestart)
                                continuation.resume(Unit)
                            } else {
                                continuation.resumeWithException(IOException(explain(errorCode)))
                            }
                        }
                    }
                }
                if (!engine.bind(callback)) {
                    continuation.resumeWithException(IOException("This phone will not let OBSIDIAN install updates"))
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    runCatching { engine.cancel() }
                    runCatching { engine.unbind() }
                }
                Log.i(TAG, "applying ${otaZip.name}, payload at ${payload.offset} size ${payload.size}")
                // update_engine reads the payload straight out of the zip, so the file is never
                // unpacked and the phone never needs room for a second copy of it.
                engine.applyPayload("file://${otaZip.absolutePath}", payload.offset, payload.size, payload.properties)
            }
        } finally {
            runCatching { engine.unbind() }
        }
    }

    private data class Payload(val offset: Long, val size: Long, val properties: Array<String>)

    /**
     * Finds the payload inside the update package. Update packages store these two entries
     * uncompressed precisely so they can be read in place.
     */
    private fun locatePayload(otaZip: File): Payload {
        ZipFile(otaZip).use { zip ->
            val properties = zip.getEntry(PROPERTIES_ENTRY)
                ?: throw IOException("That file is not an OBSIDIAN update package")
            val payload = zip.getEntry(PAYLOAD_ENTRY)
                ?: throw IOException("That file is not an OBSIDIAN update package")
            if (payload.method != java.util.zip.ZipEntry.STORED) {
                throw IOException("The update package is packed in a way this phone cannot read")
            }
            val lines = zip.getInputStream(properties).bufferedReader().readLines()
                .filter { it.isNotBlank() }
            // The offset of the entry's data: the local header, then the name and any extra field.
            val headerSize = 30L + payload.name.toByteArray().size + (payload.extra?.size ?: 0)
            return Payload(
                offset = offsetOf(zip, payload) + headerSize,
                size = payload.size,
                properties = lines.toTypedArray(),
            )
        }
    }

    /** ZipEntry knows where its header starts, but only through a field the SDK does not expose. */
    private fun offsetOf(zip: ZipFile, entry: java.util.zip.ZipEntry): Long {
        // Android's ZipEntry carries the header offset; read it rather than scanning a 2 GB file.
        val field = entry.javaClass.getDeclaredField("localHeaderRelOffset").apply { isAccessible = true }
        return field.getLong(entry)
    }

    private fun explain(errorCode: Int): String = when (errorCode) {
        ERROR_PAYLOAD_MISMATCHED_TYPE, ERROR_PAYLOAD_HASH_MISMATCH, ERROR_PAYLOAD_SIZE_MISMATCH ->
            "The update does not match what it claims to be, so nothing was installed."
        ERROR_SIGNATURE -> "The update is not signed by OBSIDIAN, so nothing was installed."
        ERROR_DOWNLOAD_TRANSFER -> "The update could not be read all the way through. Try again."
        ERROR_NO_SPACE -> "There is not enough room on the phone to install the update."
        else -> "The phone refused the update (code $errorCode). Nothing was changed."
    }

    private companion object {
        const val TAG = "ObsidianUpdate"
        const val PAYLOAD_ENTRY = "payload.bin"
        const val PROPERTIES_ENTRY = "payload_properties.txt"

        // From system/update_engine/client_library/include/update_engine/update_status.h
        const val STATUS_DOWNLOADING = 4
        const val STATUS_VERIFYING = 5
        const val STATUS_FINALIZING = 6
        const val STATUS_UPDATED_NEED_REBOOT = 7

        // From system/update_engine/common/error_code.h
        const val ERROR_SUCCESS = 0
        const val ERROR_PAYLOAD_MISMATCHED_TYPE = 10
        const val ERROR_DOWNLOAD_TRANSFER = 9
        const val ERROR_PAYLOAD_HASH_MISMATCH = 12
        const val ERROR_PAYLOAD_SIZE_MISMATCH = 11
        const val ERROR_SIGNATURE = 21
        const val ERROR_NO_SPACE = 22
        const val ERROR_UPDATED_BUT_NOT_ACTIVE = 52
    }
}
