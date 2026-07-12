package protect.yourself.commons.utils

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.OutputStream

/**
 * SafUtils — shared Storage Access Framework (SAF) helpers.
 *
 * Extracted from BackupManager.writeJsonToUri (commit a1ec981) and reused
 * by CrashLogViewModel.exportToUri to fix the same null-return retry gap
 * that affected both call sites.
 *
 * # Why this exists
 *
 * Some SAF providers (Google Drive, Dropbox, OneDrive, certain OEM file
 * managers) return **null** from `ContentResolver.openOutputStream(uri, "wt")`
 * instead of throwing an `IOException` when they don't support the `"wt"`
 * (truncate) mode. The original code only retried on `IOException`, so the
 * retry was never attempted and the export silently failed with "Could not
 * open output stream".
 *
 * This helper retries across both modes (`"wt"` then `"w"`) and handles
 * both failure modes (null return + exception) in a single loop, then
 * verifies the persisted file size matches the expected byte count so
 * truncated writes (disk full, provider quota, network drop) are detected.
 */
object SafUtils {

    /**
     * Write a UTF-8 JSON string to the user-picked SAF URI.
     *
     * Tries `"wt"` (truncate) mode first, falls back to `"w"` mode. Handles
     * both null return and IOException/SecurityException across both modes.
     *
     * @param resolver the ContentResolver from a Context
     * @param uri the SAF URI returned by ActivityResultContracts.CreateDocument
     * @param json the JSON string to write
     * @return the number of bytes written (== json.toByteArray(UTF_8).size on success)
     * @throws IOException if all write attempts fail or the verification fails
     */
    fun writeJsonToUri(resolver: ContentResolver, uri: Uri, json: String): Int {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val modes = listOf("wt", "w")
        var lastError: IOException? = null

        for (mode in modes) {
            var stream: OutputStream? = null
            try {
                stream = resolver.openOutputStream(uri, mode)
                if (stream == null) {
                    // Provider returned null — try next mode
                    lastError = IOException("Provider returned null stream for mode '$mode'")
                    continue
                }
                stream.use { os ->
                    os.write(bytes)
                    os.flush()
                }
                // Success — verify the persisted file size matches before returning
                verifyWrittenSize(resolver, uri, bytes.size)
                return bytes.size
            } catch (t: IOException) {
                lastError = t
                // try next mode
            } catch (t: SecurityException) {
                // Some providers throw SecurityException if the URI was revoked
                lastError = IOException("Permission denied for mode '$mode': ${t.message}", t)
                // try next mode
            }
        }
        throw IOException(
            "Could not write file: ${lastError?.message ?: "all write modes failed"}",
            lastError
        )
    }

    /**
     * Verify the persisted file size matches the expected byte count.
     *
     * Catches truncated writes (disk full, provider quota, network drop on
     * cloud providers) that would otherwise report success but leave a
     * corrupt file on disk.
     *
     * On API 26+ we prefer [DocumentsContract.getDocumentMetadata] which
     * returns the canonical size without reading the file. On older APIs
     * (or providers that don't support metadata), we open the input stream
     * and count bytes.
     */
    private fun verifyWrittenSize(resolver: ContentResolver, uri: Uri, expectedSize: Int) {
        // Try DocumentsContract.getDocumentMetadata first (API 26+, cheap).
        val actualSize: Int = try {
            val meta = DocumentsContract.getDocumentMetadata(resolver, uri)
            meta?.getLong(DocumentsContract.Document.COLUMN_SIZE)?.toInt() ?: -1
        } catch (t: Throwable) {
            -1  // fall through to byte-counting
        }
        if (actualSize >= 0) {
            if (actualSize != expectedSize) {
                throw IOException(
                    "File size mismatch: expected $expectedSize bytes, got $actualSize bytes. " +
                        "The file may be corrupt or the storage is full."
                )
            }
            return
        }
        // Fall back: open input stream and count bytes
        var counted = 0
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                counted += read
            }
        } ?: throw IOException("Could not re-open URI to verify file size")
        if (counted != expectedSize) {
            throw IOException(
                "File size mismatch: expected $expectedSize bytes, got $counted bytes. " +
                    "The file may be corrupt or the storage is full."
            )
        }
    }
}
