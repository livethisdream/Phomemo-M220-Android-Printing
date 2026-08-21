package net.homelab.labeler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Named label designs on disk.
 *
 * A design is the layout *and the stock it was laid out on*. Millimeter
 * coordinates are only meaningful against a label of a known size - the same
 * numbers describe a centered QR on 50x30 and one hanging off the edge of
 * 30x20 - so the size travels with the design and is restored when it loads.
 *
 * Printer settings deliberately do not travel with it. Heat, feed distance and
 * protocol are properties of the machine and the roll in it, not of the layout;
 * folding them in would let recalling a design silently undo tuning that took
 * a long time to get right.
 *
 * Storage is one JSON file per design plus a rendered thumbnail, so the library
 * screen can show what each design looks like without decoding every image it
 * contains.
 */
class DesignStore(context: Context) {

    private val root = File(context.filesDir, "designs")
    private val blobs = File(context.filesDir, "images")

    /** List entry: everything the library screen shows, without the elements. */
    data class Saved(
        val id: String,
        val name: String,
        val widthMm: Int,
        val heightMm: Int,
        val savedAt: Long
    ) {
        val size: String get() = "$widthMm × $heightMm mm"
    }

    class Loaded(
        val id: String,
        val name: String,
        val doc: LabelDesign.Doc,
        val widthMm: Int,
        val heightMm: Int
    )

    /** Newest first, because the thing you just saved is the thing you want. */
    fun list(): List<Saved> {
        val files = root.listFiles { f: File -> f.name.endsWith(SUFFIX_JSON) } ?: return emptyList()
        return files.mapNotNull { file ->
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@mapNotNull null
            Saved(
                id = file.name.removeSuffix(SUFFIX_JSON),
                name = json.optString("name").ifBlank { "Untitled" },
                widthMm = json.optInt("widthMm", 50),
                heightMm = json.optInt("heightMm", 30),
                savedAt = json.optLong("savedAt")
            )
        }.sortedByDescending { it.savedAt }
    }

    fun thumbnail(id: String): Bitmap? {
        val file = File(root, id + SUFFIX_PNG)
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    /**
     * Writes a design. Passing an [id] overwrites that design; passing null
     * creates one, which is how "save a copy" differs from "update".
     */
    fun save(
        id: String?,
        name: String,
        doc: LabelDesign.Doc,
        widthMm: Int,
        heightMm: Int
    ): Saved {
        root.mkdirs()
        blobs.mkdirs()

        val designId = id ?: UUID.randomUUID().toString()
        val savedAt = System.currentTimeMillis()

        val elements = JSONArray()
        // Images are written before the design that references them, so a
        // collection pass can never see a blob whose only reference has not
        // been recorded yet.
        for (element in doc.elements) encode(element)?.let(elements::put)

        val json = JSONObject()
            .put("version", VERSION)
            .put("name", name)
            .put("savedAt", savedAt)
            .put("widthMm", widthMm)
            .put("heightMm", heightMm)
            .put("elements", elements)

        writeAtomically(File(root, designId + SUFFIX_JSON), json.toString().toByteArray())

        runCatching {
            val bitmap = DesignRenderer.render(doc, widthMm, heightMm)
            writeAtomically(File(root, designId + SUFFIX_PNG), png(bitmap))
        }

        collectGarbage()
        return Saved(designId, name, widthMm, heightMm, savedAt)
    }

    fun load(id: String): Loaded? {
        val file = File(root, id + SUFFIX_JSON)
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null

        val array = json.optJSONArray("elements") ?: JSONArray()
        val elements = (0 until array.length()).mapNotNull { decode(array.optJSONObject(it)) }

        return Loaded(
            id = id,
            name = json.optString("name").ifBlank { "Untitled" },
            doc = LabelDesign.Doc(elements),
            widthMm = json.optInt("widthMm", 50),
            heightMm = json.optInt("heightMm", 30)
        )
    }

    fun delete(id: String) {
        File(root, id + SUFFIX_JSON).delete()
        File(root, id + SUFFIX_PNG).delete()
        collectGarbage()
    }

    // --- Elements -------------------------------------------------------------

    /**
     * Element ids are not persisted. They come from a counter that restarts
     * with the process, so a saved id would collide with a live one the moment
     * a design is loaded next to anything else. Identity within a document is
     * all they are for, and loading rebuilds the document anyway.
     */
    private fun encode(element: LabelDesign.Element): JSONObject? {
        val json = JSONObject()
            .put("x", element.x.toDouble())
            .put("y", element.y.toDouble())
            .put("w", element.w.toDouble())
            .put("h", element.h.toDouble())

        return when (element) {
            is LabelDesign.Element.Text -> json
                .put("type", "text")
                .put("text", element.text)
                .put("sizeMm", element.sizeMm.toDouble())
                .put("bold", element.bold)
                .put("monospace", element.monospace)
                .put("align", element.align.name.lowercase())
                .put("invert", element.invert)

            is LabelDesign.Element.Qr -> json
                .put("type", "qr")
                .put("content", element.content)

            is LabelDesign.Element.Picture -> {
                val digest = storeImage(element.bitmap) ?: return null
                json.put("type", "picture")
                    .put("image", digest)
                    .put("mode", element.mode.name.lowercase())
            }
        }
    }

    /**
     * Returns null for an element that cannot be rebuilt - a picture whose blob
     * has gone missing. Dropping one element loses part of a design; refusing
     * the whole file would lose all of it, including the parts still intact.
     */
    private fun decode(json: JSONObject?): LabelDesign.Element? {
        if (json == null) return null
        val x = json.optDouble("x", 0.0).toFloat()
        val y = json.optDouble("y", 0.0).toFloat()
        val w = json.optDouble("w", 0.0).toFloat()
        val h = json.optDouble("h", 0.0).toFloat()
        if (w <= 0f || h <= 0f) return null

        return when (json.optString("type")) {
            "text" -> LabelDesign.Element.Text(
                id = LabelDesign.newId(),
                x = x, y = y, w = w, h = h,
                text = json.optString("text"),
                sizeMm = json.optDouble("sizeMm", 4.0).toFloat(),
                bold = json.optBoolean("bold"),
                monospace = json.optBoolean("monospace"),
                align = when (json.optString("align")) {
                    "center" -> LabelDesign.Align.CENTER
                    "right" -> LabelDesign.Align.RIGHT
                    else -> LabelDesign.Align.LEFT
                },
                invert = json.optBoolean("invert")
            )

            "qr" -> LabelDesign.Element.Qr(
                id = LabelDesign.newId(),
                x = x, y = y, w = w, h = h,
                content = json.optString("content")
            )

            "picture" -> {
                val bitmap = loadImage(json.optString("image")) ?: return null
                LabelDesign.Element.Picture(
                    id = LabelDesign.newId(),
                    x = x, y = y, w = w, h = h,
                    bitmap = bitmap,
                    mode = if (json.optString("mode") == "dither") {
                        ImageLabel.Mode.DITHER
                    } else {
                        ImageLabel.Mode.THRESHOLD
                    }
                )
            }

            else -> null
        }
    }

    // --- Image blobs ----------------------------------------------------------

    /**
     * Writes a picture under the digest of its own bytes and returns that
     * digest. Content addressing means the same photo saved into five designs
     * occupies one file, and that re-saving a design does not accumulate copies
     * of an image that has not changed.
     */
    private fun storeImage(bitmap: Bitmap): String? = runCatching {
        val bytes = png(bitmap)
        val digest = sha1(bytes)
        val file = File(blobs, "$digest$SUFFIX_PNG")
        if (!file.isFile) writeAtomically(file, bytes)
        digest
    }.getOrNull()

    private fun loadImage(digest: String): Bitmap? {
        if (digest.isEmpty()) return null
        val file = File(blobs, "$digest$SUFFIX_PNG")
        if (!file.isFile) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return runCatching { BitmapFactory.decodeFile(file.path, opts) }.getOrNull()
    }

    /**
     * Deletes image blobs no surviving design mentions.
     *
     * Content addressing shares blobs between designs, so deleting a design
     * cannot delete its images directly - another design may be using the same
     * one. Sweeping the whole set is cheap at this scale and is the only
     * approach that cannot leave a design pointing at a file that is gone.
     */
    private fun collectGarbage() {
        val files = blobs.listFiles() ?: return
        if (files.isEmpty()) return

        val referenced = mutableSetOf<String>()
        root.listFiles { f: File -> f.name.endsWith(SUFFIX_JSON) }?.forEach { file ->
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@forEach
            val array = json.optJSONArray("elements") ?: return@forEach
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.optString("image")?.takeIf { it.isNotEmpty() }
                    ?.let(referenced::add)
            }
        }

        for (file in files) {
            if (file.name.removeSuffix(SUFFIX_PNG) !in referenced) file.delete()
        }
    }

    // --- Files ----------------------------------------------------------------

    private fun png(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Writes through a temporary file. A design half-written when the process
     * dies would otherwise be a file that parses as nothing and takes its old
     * contents with it.
     */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
    }

    private companion object {
        const val VERSION = 1
        const val SUFFIX_JSON = ".json"
        const val SUFFIX_PNG = ".png"
    }
}
