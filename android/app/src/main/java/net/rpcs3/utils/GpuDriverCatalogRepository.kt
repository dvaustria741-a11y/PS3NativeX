// Ported from sashkinbro/EmuCoreC (GPLv2, same license as this project).
// Points at the same public driver catalog EmuCoreC uses
// (sashkinbro/EmuCoreV-Drivers) -- these are the exact same Turnip/Mesa
// builds shown in that app's "Driver catalog" screen, not something
// PS3NativeX-specific. Worth knowing: unlike the Skins feature (which just
// downloads PNGs), this downloads and runs third-party-built native driver
// binaries. If you'd rather point this at a different or self-hosted
// catalog, CATALOG_URLS below is the only thing that needs to change --
// everything else (parsing, matching, download, install) works against
// any source that serves the same drivers.json shape.

package net.rpcs3.utils

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class RemoteGpuDriver(
    val id: String,
    val name: String,
    val variant: String,
    val gpu: String,
    val description: String,
    val recommended: Boolean,
    val downloadUrl: String,
    val sourceUrl: String,
    val credits: String,
    val sizeBytes: Long?
)

class GpuDriverCatalogRepository(private val context: Context) {

    fun loadCatalog(): List<RemoteGpuDriver> {
        var lastFailure: Throwable? = null
        for (catalogUrl in CATALOG_URLS) {
            val result = runCatching {
                val connection = openConnection(catalogUrl, "application/json,text/plain,*/*")
                try {
                    ensureSuccess(connection, "driver catalog")
                    connection.inputStream.bufferedReader().use { reader ->
                        parseCatalog(reader.readText())
                    }
                } finally {
                    connection.disconnect()
                }
            }
            result.onSuccess { return it }
            lastFailure = result.exceptionOrNull()
        }
        throw IOException("Driver catalog unavailable", lastFailure)
    }

    fun downloadDriver(driver: RemoteGpuDriver, onProgress: (Float) -> Unit): File {
        val target = File(context.cacheDir, "gpu-drivers/${driver.safeArchiveName()}")
        target.parentFile?.mkdirs()
        if (target.exists()) {
            target.delete()
        }

        val connection = openConnection(driver.downloadUrl, "application/zip,application/octet-stream,*/*").apply {
            readTimeout = 60_000
        }
        try {
            ensureSuccess(connection, "driver archive")
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: driver.sizeBytes ?: -1L
            var copied = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) {
                            onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        onProgress(1f)
        return target
    }

    private fun parseCatalog(json: String): List<RemoteGpuDriver> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val downloadUrl = item.optString("downloadUrl")
                val id = item.optString("id")
                if (id.isBlank() || downloadUrl.isBlank()) continue
                add(
                    RemoteGpuDriver(
                        id = id,
                        name = item.optString("name", id),
                        variant = item.optString("variant"),
                        gpu = item.optString("gpu", "Adreno"),
                        description = item.optString("description"),
                        recommended = item.optBoolean("recommended", false),
                        downloadUrl = downloadUrl,
                        sourceUrl = item.optString("sourceUrl"),
                        credits = item.optString("credits"),
                        sizeBytes = item.optLong("sizeBytes").takeIf { it > 0L }
                    )
                )
            }
        }
    }

    private fun RemoteGpuDriver.safeArchiveName(): String {
        val rawName = downloadUrl.substringBefore('?').substringAfterLast('/').ifBlank { "$id.zip" }
        val safeName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safeName.endsWith(".zip", ignoreCase = true)) safeName else "$safeName.zip"
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "PS3Native/${context.packageName}")
        }
    }

    private fun ensureSuccess(connection: HttpURLConnection, label: String) {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val responseMessage = connection.responseMessage.orEmpty().ifBlank { "HTTP $responseCode" }
            throw IOException("Could not load $label: $responseMessage")
        }
    }

    companion object {
        val CATALOG_URLS = listOf(
            "https://raw.githubusercontent.com/sashkinbro/EmuCoreV-Drivers/main/drivers.json",
            "https://github.com/sashkinbro/EmuCoreV-Drivers/raw/main/drivers.json",
            "https://cdn.jsdelivr.net/gh/sashkinbro/EmuCoreV-Drivers@main/drivers.json"
        )

        private const val PREFS_NAME = "GpuDriverCatalogPrefs"

        // The catalog's display name and a driver archive's own meta.json
        // name don't reliably agree (observed e.g. "Turnip v26.3.0 R1" in
        // the catalog vs "Turnip v26.3.0-R1" in the actual archive), and
        // that's third-party data neither side controls -- so "is this
        // catalog entry installed" is tracked explicitly here (catalog id
        // -> installed label) rather than fuzzy-matched by name after the
        // fact, which would drift the moment either side's formatting
        // changes.
        fun recordInstalled(context: Context, catalogId: String, installedLabel: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("id:$catalogId", installedLabel)
                .apply()
        }

        fun clearInstalled(context: Context, catalogId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove("id:$catalogId")
                .apply()
        }

        /** Installed label for [catalogId], or null if it was never
         *  installed through the catalog (or has since been removed). */
        fun installedLabelFor(context: Context, catalogId: String): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("id:$catalogId", null)
    }
}
