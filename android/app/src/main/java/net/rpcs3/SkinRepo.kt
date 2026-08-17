package net.rpcs3

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Browse + download community controller skins from ARM64PS3's skin repo.
 * Same shape as GpuDriverHelper's driver browser: fetch an index, hand a
 * downloaded archive to the existing installer (ControllerSkinStore).
 *
 * INDEX: `manifest.json` at the repo root is preferred -- it carries the
 * author's display name, a preview image and the button count. The
 * git-trees fallback exists so the browser still works if the manifest is
 * missing or malformed; it recovers filenames and previews where a
 * Previews/ image pairs with a skin by name, but no author or button count.
 *
 * The manifest is published by someone else, so both paths have to survive
 * it being wrong -- see [dropTrailingCommas]. A broken index should cost
 * detail, never the whole browser.
 */
object SkinRepo {
    private const val TAG = "SkinRepo"
    private const val REPO = "bagasromadon/ARMSX2-CustomControllerSkins"
    private const val RAW_BASE = "https://raw.githubusercontent.com/$REPO/main"
    private const val MANIFEST_URL = "$RAW_BASE/manifest.json"
    private const val TREE_URL = "https://api.github.com/repos/$REPO/git/trees/main?recursive=1"

    /** Skin zips run ~150 KB-3.5 MB. 32 MB is far above any real pack and
     *  still bounds a hostile or corrupted response. */
    private const val MAX_ZIP_BYTES = 32L * 1024 * 1024
    private const val MAX_PREVIEW_BYTES = 4L * 1024 * 1024

    data class RemoteSkin(
        val name: String,
        val filePath: String,
        val previewPath: String?,
        val author: String,
        val buttons: Int,
        val sizeBytes: Long,
    ) {
        val downloadUrl: String get() = "$RAW_BASE/${encodePath(filePath)}"
        val previewUrl: String? get() = previewPath?.let { "$RAW_BASE/${encodePath(it)}" }
    }

    /** Percent-encode each path segment -- most skin filenames have spaces
     *  ("God Of War 2.zip"), and raw.githubusercontent 400s on a raw space. */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }

    private fun userAgent(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "dev"
        return "PS3Native/$version"
    }

    private fun get(
        context: Context,
        url: String,
        timeoutMs: Int = 20_000,
        maxBytes: Long = MAX_ZIP_BYTES
    ): ByteArray? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", userAgent(context))
            }
            try {
                if (conn.responseCode != 200) {
                    Log.w(TAG, "GET $url -> status=${conn.responseCode}")
                    return null
                }
                val declaredLength = conn.contentLength
                if (declaredLength > maxBytes) {
                    Log.w(TAG, "GET $url -> declared $declaredLength bytes exceeds cap $maxBytes")
                    return null
                }
                conn.inputStream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) {
                            Log.w(TAG, "GET $url -> exceeded cap $maxBytes while streaming")
                            return null
                        }
                        buffer.write(chunk, 0, n)
                    }
                    buffer.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            Log.w(TAG, "GET $url failed", it)
            null
        }
    }

    /** Available skins, newest index first. Blocking -- call on Dispatchers.IO. */
    fun fetch(context: Context): List<RemoteSkin> {
        parseManifest(get(context, MANIFEST_URL, maxBytes = 1L * 1024 * 1024)?.toString(Charsets.UTF_8))
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        Log.w(TAG, "manifest unusable, falling back to git tree")
        return fetchFromTree(context)
    }

    /**
     * Drop a `,` separated only by whitespace from the `}`/`]` that closes
     * its container. A trailing comma is invalid JSON and org.json rejects
     * it -- one stray comma in the published manifest would otherwise take
     * down the whole skin index. String-aware rather than a regex, so a
     * name that genuinely contains ", ]" survives.
     */
    private fun dropTrailingCommas(body: String): String {
        val out = StringBuilder(body.length)
        var inString = false
        var escaped = false
        for (c in body) {
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            if (c == '"') inString = true
            else if (c == '}' || c == ']') {
                var i = out.length
                while (i > 0 && out[i - 1].isWhitespace()) i--
                if (i > 0 && out[i - 1] == ',') out.deleteCharAt(i - 1)
            }
            out.append(c)
        }
        return out.toString()
    }

    private fun parseManifest(body: String?): List<RemoteSkin>? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { JSONObject(dropTrailingCommas(body)) }.getOrNull()
                ?.also { Log.w(TAG, "manifest has trailing commas, parsed after repair") }
            ?: return null
        return runCatching {
            val arr = root.getJSONArray("skins")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RemoteSkin(
                    name = o.optString("name").takeIf { it.isNotBlank() }
                        ?: file.substringAfterLast('/').removeSuffix(".zip"),
                    filePath = file,
                    previewPath = o.optString("preview").takeIf { it.isNotBlank() },
                    author = o.optString("author").takeIf { it.isNotBlank() } ?: "community",
                    buttons = o.optInt("buttons", 0),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                )
            }
        }.getOrNull()
    }

    /** Comparison key for pairing a skin with its preview across the naming
     *  schemes the repo actually uses. Letters and digits only, trailing
     *  "preview" dropped. Near misses just go without a thumbnail. */
    private fun matchKey(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }.removeSuffix("preview")

    /** Filenames plus whatever previews pair by name -- used when the
     *  manifest can't be read. */
    private fun fetchFromTree(context: Context): List<RemoteSkin> {
        val body = get(context, TREE_URL, maxBytes = 4L * 1024 * 1024)?.toString(Charsets.UTF_8)
            ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).getJSONArray("tree")
            val paths = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

            val previews = paths.mapNotNull { o ->
                val p = o.optString("path")
                if (!p.startsWith("Previews/") || !p.endsWith(".png")) null
                else matchKey(p.substringAfterLast('/').removeSuffix(".png")) to p
            }.toMap()

            paths.mapNotNull { o ->
                val path = o.optString("path")
                if (!path.startsWith("Skins/") || !path.endsWith(".zip")) return@mapNotNull null
                // Anything under 1 KB can't hold a real button PNG pack --
                // drop it rather than surface a skin that installs nothing.
                val size = o.optLong("size", 0L)
                if (size in 1 until 1024) return@mapNotNull null
                val name = path.substringAfterLast('/').removeSuffix(".zip")
                RemoteSkin(
                    name = name,
                    filePath = path,
                    previewPath = previews[matchKey(name)],
                    author = "community",
                    buttons = 0,
                    sizeBytes = size,
                )
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    // ---- preview images ----

    private fun previewDir(context: Context): File =
        File(context.cacheDir, "skinpreviews").apply { mkdirs() }

    /** Cached preview file for [skin], downloading on first use. Null when
     *  the skin has no preview or the fetch failed -- the UI just shows no
     *  thumbnail. Blocking -- call on Dispatchers.IO. */
    fun preview(context: Context, skin: RemoteSkin): File? {
        val url = skin.previewUrl ?: return null
        val cached = File(previewDir(context), skin.filePath.hashCode().toString(16) + ".png")
        if (cached.isFile && cached.length() > 0) return cached
        val bytes = get(context, url, timeoutMs = 15_000, maxBytes = MAX_PREVIEW_BYTES) ?: return null
        return runCatching {
            cached.outputStream().use { it.write(bytes) }
            cached
        }.getOrElse { cached.delete(); null }
    }

    // ---- download + install ----

    /** Download [skin] and install it via ControllerSkinStore. Returns the
     *  new local skin id, or null if the download failed or the archive
     *  held no button images. Blocking -- call on Dispatchers.IO. */
    fun install(context: Context, skin: RemoteSkin): String? {
        val bytes = get(context, skin.downloadUrl, timeoutMs = 60_000) ?: return null
        val tmp = File(context.cacheDir, "skin-dl-${System.nanoTime()}.zip")
        return try {
            tmp.outputStream().use { it.write(bytes) }
            ControllerSkinStore.importFromZipFile(context, tmp, skin.name)
        } catch (t: Throwable) {
            Log.w(TAG, "install ${skin.name} failed", t)
            null
        } finally {
            tmp.delete()
        }
    }
}
