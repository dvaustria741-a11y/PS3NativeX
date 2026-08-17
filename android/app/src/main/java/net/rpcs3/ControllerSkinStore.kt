package net.rpcs3

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Custom on-screen controller button skins. A skin is a folder of
 * `ic_controller_<button>.png` images -- the same filename scheme ARM64PS3's
 * community skin repo (see SkinRepo) already publishes packs in, so a skin
 * downloaded there imports here without any translation step. Imported skins
 * live app-private under `filesDir/controllerskins/<id>/`. One is selected at
 * a time, or none = the built-in procedurally-drawn look
 * (see PadButtonArt/PadOverlay).
 *
 * v1 wiring covers the buttons PadOverlay.kt builds as individual bitmaps:
 * face buttons, shoulder buttons, L3/R3, both analog sticks, and
 * start/select/ps. The d-pad and face-button cluster are drawn through a
 * shared composite (createDpad) rather than individual bitmaps and aren't
 * skinned yet -- FILE still recognizes their keys on import so packs aren't
 * missing data if that gets wired up later, they just aren't drawn from yet.
 */
object ControllerSkinStore {

    private const val PREFS_NAME = "ControllerSkinPrefs"
    private const val KEY_ACTIVE = "skin.active"

    private val prefs: (Context) -> SharedPreferences = { ctx ->
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Currently selected skin id, or null for the built-in look. */
    @Volatile
    var activeSkinId: String? = null
        private set

    @Volatile private var loaded = false

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        activeSkinId = prefs(context).getString(KEY_ACTIVE, null)
            ?.takeIf { File(root(context), it).isDirectory }
        loaded = true
    }

    /** Call once at app start so the overlay has the right skin from its
     *  first frame instead of only after Skins is opened. */
    fun load(context: Context) = ensureLoaded(context)

    data class Skin(val id: String, val name: String, val imageCount: Int)

    /** Logical button key -> filename inside a skin folder. Matches the iOS
     *  scheme ARM64PS3's community repo publishes packs in. */
    private val FILE: Map<String, String> = buildMap {
        for (k in listOf(
            "cross", "circle", "square", "triangle",
            "l1", "l2", "l3", "r1", "r2", "r3",
            "start", "select", "ps",
            "up", "down", "left", "right",
        )) put(k, "ic_controller_${k}_button.png")
        put("analog_base_left", "ic_controller_analog_base_left.png")
        put("analog_base_right", "ic_controller_analog_base_right.png")
        put("analog_stick_left", "ic_controller_analog_stick_left.png")
        put("analog_stick_right", "ic_controller_analog_stick_right.png")
        // Generic fallback (packs that don't split L/R stick art).
        put("analog_base", "ic_controller_analog_base.png")
        put("analog_stick", "ic_controller_analog_stick.png")
    }

    private fun keyForFilename(name: String): String? {
        val n = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (n.startsWith("._") || !n.endsWith(".png")) return null
        val core = n.removeSuffix(".png").removePrefix("ic_controller_").removeSuffix("_button")
        return if (FILE.containsKey(core)) core else null
    }

    // Generous but bounded (phone storage + a hostile/corrupt zip).
    private const val MAX_IMAGES = 24
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    private fun root(context: Context): File =
        File(context.filesDir, "controllerskins").apply { mkdirs() }

    /** Imported skins that have at least one recognized image. */
    fun list(context: Context): List<Skin> {
        ensureLoaded(context)
        val dirs = root(context).listFiles { f -> f.isDirectory && !f.name.endsWith(".tmp") }
            ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val n = dir.listFiles { f -> f.isFile && keyForFilename(f.name) != null }?.size ?: 0
            if (n == 0) null else Skin(dir.name, prettyName(dir.name), n)
        }.sortedBy { it.name.lowercase() }
    }

    /** Select [id] (null = built-in look). */
    fun setActive(context: Context, id: String?) {
        ensureLoaded(context)
        prefs(context).edit().apply {
            if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id)
        }.apply()
        activeSkinId = id
        clearCache()
    }

    fun delete(context: Context, id: String) {
        File(root(context), id).deleteRecursively()
        if (prefs(context).getString(KEY_ACTIVE, null) == id) {
            prefs(context).edit().remove(KEY_ACTIVE).apply()
            activeSkinId = null
        }
        clearCache()
    }

    // ---- Runtime image cache (active skin) ----------------------------------
    private val cache = HashMap<String, Bitmap?>()

    private fun clearCache() = synchronized(cache) { cache.clear() }

    /** Longest edge a decoded skin image is kept at -- on-screen buttons never
     *  draw larger than a few hundred px, but community packs ship
     *  1024-2048px art, so decoding full-res wastes memory and redraw time
     *  every frame the overlay repaints. */
    private const val MAX_DECODE_PX = 512

    private fun sampleSizeFor(w: Int, h: Int): Int {
        var sample = 1
        var longest = maxOf(w, h)
        while (longest / 2 >= MAX_DECODE_PX) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    private fun decodeDownsampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return runCatching {
            file.inputStream().use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    /** Bitmap for [key] from the active skin, or null to use the built-in
     *  procedurally-drawn art (PadButtonArt). */
    fun bitmapForKey(context: Context, key: String): Bitmap? {
        ensureLoaded(context)
        val id = activeSkinId ?: return null
        val fname = FILE[key] ?: return null
        val cacheKey = "$id/$key"
        synchronized(cache) { if (cache.containsKey(cacheKey)) return cache[cacheKey] }
        val file = File(File(root(context), id), fname)
        val bmp = if (file.isFile) decodeDownsampled(file) else null
        synchronized(cache) { cache[cacheKey] = bmp }
        return bmp
    }

    // ---- Import ---------------------------------------------------------

    /** Import a skin from a picked .zip (SAF content:// URI) of
     *  `ic_controller_*.png`, flattened to the zip root. */
    fun importFromZip(context: Context, zipUri: Uri): String? {
        val name = runCatching {
            context.contentResolver.query(zipUri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()?.removeSuffix(".zip")?.removeSuffix(".ZIP") ?: "skin"
        return importZip(context, name) { context.contentResolver.openInputStream(zipUri) }
    }

    /** Import a .zip already on local disk under an explicit display name --
     *  the download path (see SkinRepo). */
    fun importFromZipFile(context: Context, zip: File, displayName: String): String? =
        importZip(context, displayName) { zip.inputStream() }

    private fun importZip(
        context: Context,
        rawName: String,
        open: () -> java.io.InputStream?
    ): String? {
        val id = newId(context, rawName)
        val tmp = File(root(context), "$id.tmp").apply { deleteRecursively(); mkdirs() }
        var count = 0
        runCatching {
            open()?.use { stream ->
                ZipInputStream(stream).use { zin ->
                    while (true) {
                        val entry = zin.nextEntry ?: break
                        val key = if (entry.isDirectory) null else keyForFilename(entry.name)
                        if (key != null && count < MAX_IMAGES) {
                            val out = File(tmp, FILE.getValue(key))
                            val ok = runCatching {
                                // Cap each entry so a hostile/corrupt archive
                                // can't decompress unbounded data to disk.
                                val limited = LimitedInputStream(zin, MAX_IMAGE_BYTES)
                                out.outputStream().use { limited.copyTo(it) }
                            }.isSuccess
                            if (ok && out.length() in 1..MAX_IMAGE_BYTES) count++ else out.delete()
                        }
                        zin.closeEntry()
                    }
                }
            }
        }
        if (count == 0) {
            tmp.deleteRecursively()
            return null
        }
        val target = File(root(context), id)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) {
            tmp.deleteRecursively()
            return null
        }
        clearCache()
        return id
    }

    private class LimitedInputStream(
        private val delegate: java.io.InputStream,
        private val max: Long
    ) : java.io.InputStream() {
        private var read = 0L
        override fun read(): Int {
            if (read >= max) return -1
            val b = delegate.read()
            if (b >= 0) read++
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= max) return -1
            val n = delegate.read(b, off, minOf(len, (max - read).toInt()))
            if (n > 0) read += n
            return n
        }
    }

    private fun newId(context: Context, name: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
            .trim('_').ifEmpty { "skin" }
        var id = base
        var i = 1
        while (File(root(context), id).exists()) id = "${base}_${i++}"
        return id
    }

    private fun prettyName(id: String) = id.replace('_', ' ').trim()
}
