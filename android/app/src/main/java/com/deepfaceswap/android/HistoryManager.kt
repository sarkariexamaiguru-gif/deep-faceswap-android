package com.deepfaceswap.android

import android.content.Context
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Local history of completed results.
 *
 * Everything lives in app-private storage: the record list in `filesDir` and the
 * result files in the app's own external folder. Nothing is uploaded, and
 * deleting a record removes the app's copy only — a gallery export the user made
 * separately is never touched.
 */
class HistoryManager(private val context: Context) {

    enum class Kind { IMAGE, GIF, VIDEO }

    data class Item(
        val id: String,
        val fileName: String,
        val path: String,
        val kind: Kind,
        val createdAt: Long,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val modelLabel: String,
        val sourcePath: String?,
        var favorite: Boolean,
    ) {
        val file: File get() = File(path)
        val exists: Boolean get() = file.isFile && file.length() > 0L

        /** Short type badge shown on the thumbnail. */
        val typeBadge: String
            get() = when (kind) {
                Kind.VIDEO -> "VIDEO"
                Kind.GIF -> "GIF"
                else -> fileName.substringAfterLast('.', "IMG").uppercase(Locale.US)
            }

        /** `00:24` for timed media, blank otherwise. */
        val durationLabel: String
            get() = if (durationMs <= 0L) "" else formatDuration(durationMs)
    }

    data class Group(val label: String, val items: MutableList<Item>)

    private val store = File(context.filesDir, "history.json")
    private val items = mutableListOf<Item>()

    init {
        load()
    }

    // ------------------------------------------------------------- storage --

    private fun load() {
        items.clear()
        if (!store.isFile) return
        try {
            val array = JSONArray(store.readText())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                items.add(
                    Item(
                        id = o.optString("id"),
                        fileName = o.optString("fileName"),
                        path = o.optString("path"),
                        kind = runCatching { Kind.valueOf(o.optString("kind")) }
                            .getOrDefault(Kind.IMAGE),
                        createdAt = o.optLong("createdAt"),
                        sizeBytes = o.optLong("sizeBytes"),
                        width = o.optInt("width"),
                        height = o.optInt("height"),
                        durationMs = o.optLong("durationMs"),
                        modelLabel = o.optString("modelLabel"),
                        sourcePath = o.optString("sourcePath").takeIf { it.isNotEmpty() },
                        favorite = o.optBoolean("favorite"),
                    ),
                )
            }
        } catch (_: Exception) {
            // A corrupt store should never block the app; start clean.
            items.clear()
        }
    }

    private fun persist() {
        try {
            val array = JSONArray()
            for (item in items) {
                array.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("fileName", item.fileName)
                        put("path", item.path)
                        put("kind", item.kind.name)
                        put("createdAt", item.createdAt)
                        put("sizeBytes", item.sizeBytes)
                        put("width", item.width)
                        put("height", item.height)
                        put("durationMs", item.durationMs)
                        put("modelLabel", item.modelLabel)
                        put("sourcePath", item.sourcePath ?: "")
                        put("favorite", item.favorite)
                    },
                )
            }
            store.writeText(array.toString())
        } catch (_: Exception) {
            // Best effort; losing the index is recoverable, crashing is not.
        }
    }

    // ------------------------------------------------------------ mutation --

    fun all(): List<Item> = items.toList()

    fun add(item: Item) {
        items.add(0, item)
        persist()
    }

    fun toggleFavorite(id: String) {
        items.firstOrNull { it.id == id }?.let {
            it.favorite = !it.favorite
            persist()
        }
    }

    /** Delete records and the app's own copies of their files. */
    fun delete(ids: Set<String>): Int {
        val doomed = items.filter { it.id in ids }
        for (item in doomed) {
            runCatching { if (item.file.isFile) item.file.delete() }
        }
        items.removeAll { it.id in ids }
        persist()
        return doomed.size
    }

    /** Drop records whose file has disappeared, without touching anything else. */
    fun pruneMissing(): Int {
        val before = items.size
        items.removeAll { !it.exists }
        persist()
        return before - items.size
    }

    fun totalBytes(): Long = items.sumOf { if (it.exists) it.file.length() else 0L }

    // ------------------------------------------------------------- queries --

    /** Newest first, grouped into Today / Yesterday / a date label. */
    fun grouped(visible: List<Item>): List<Group> {
        val groups = LinkedHashMap<String, MutableList<Item>>()
        for (item in visible) {
            groups.getOrPut(dayLabel(item.createdAt)) { mutableListOf() }.add(item)
        }
        return groups.map { Group(it.key, it.value) }
    }

    fun filter(kind: String, favoritesOnly: Boolean, query: String, sort: String): List<Item> {
        var list = items.toList()

        when (kind) {
            "Images" -> list = list.filter { it.kind == Kind.IMAGE }
            "Videos" -> list = list.filter { it.kind == Kind.VIDEO }
            "GIFs" -> list = list.filter { it.kind == Kind.GIF }
            "Favorites" -> list = list.filter { it.favorite }
        }
        if (favoritesOnly) list = list.filter { it.favorite }

        val q = query.trim().lowercase(Locale.US)
        if (q.isNotEmpty()) {
            list = list.filter {
                it.fileName.lowercase(Locale.US).contains(q) ||
                    it.kind.name.lowercase(Locale.US).contains(q) ||
                    dayLabel(it.createdAt).lowercase(Locale.US).contains(q) ||
                    fullDate(it.createdAt).lowercase(Locale.US).contains(q)
            }
        }

        return when (sort) {
            "Oldest first" -> list.sortedBy { it.createdAt }
            "Name A–Z" -> list.sortedBy { it.fileName.lowercase(Locale.US) }
            "Largest first" -> list.sortedByDescending { if (it.exists) it.file.length() else 0L }
            else -> list.sortedByDescending { it.createdAt }
        }
    }

    // -------------------------------------------------------------- helpers --

    /** `5 files · 2 videos · 3 images` — counts always add up. */
    fun countSummary(group: Group): String {
        val videos = group.items.count { it.kind == Kind.VIDEO }
        val gifs = group.items.count { it.kind == Kind.GIF }
        val images = group.items.count { it.kind == Kind.IMAGE }
        val parts = mutableListOf("${group.items.size} files")
        if (videos > 0) parts.add("$videos video" + if (videos == 1) "" else "s")
        if (gifs > 0) parts.add("$gifs GIF" + if (gifs == 1) "" else "s")
        if (images > 0) parts.add("$images image" + if (images == 1) "" else "s")
        return parts.joinToString(" \u00B7 ")
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    /** Pull a poster frame out of a video for its thumbnail. */
    fun posterFor(item: Item): android.graphics.Bitmap? {
        if (item.kind != Kind.VIDEO) return null
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(item.path)
                retriever.getFrameAtTime(0L)
            } finally {
                retriever.release()
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private fun dayLabel(millis: Long): String {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = millis }
            val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
            val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
            return when {
                sameYear && dayDiff == 0 -> "Today"
                sameYear && dayDiff == 1 -> "Yesterday"
                else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))
            }
        }

        private fun fullDate(millis: Long): String =
            SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(millis))

        fun formatDuration(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}
