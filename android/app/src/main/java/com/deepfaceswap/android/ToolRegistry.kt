package com.deepfaceswap.android

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The tool registry.
 *
 * Every tool in the app is described by data, not by code. That is the whole
 * point: adding a tool should mean writing an entry, not writing a screen,
 * an activity and a handoff.
 *
 * Two files are merged, in this order:
 *
 *  1. `assets/tools.json` - the catalogue that ships with the build.
 *  2. `filesDir/tools-extra.json` - a file the user can drop in later.
 *
 * The second one is the interesting half. It means a new tool can be added to an
 * installed app without rebuilding it - write the JSON, push it to the device,
 * and the tool appears on the next launch. A tool whose model is not installed
 * shows as "Needs a model" rather than failing when tapped, so a half-finished
 * entry is never worse than no entry.
 */
object ToolRegistry {

    data class Category(val id: String, val name: String, val icon: String)

    data class Param(
        val key: String,
        val type: String,
        val min: Int,
        val max: Int,
        val default: Int,
    )

    data class Tool(
        val id: String,
        val name: String,
        val category: String,
        val icon: String,
        val purpose: String,
        val runner: String,
        val models: List<String>,
        val ready: Boolean,
        val screen: String?,
        val params: List<Param>,
    )

    data class ModelDef(
        val id: String,
        val name: String,
        val role: String,
        val sizeMb: Int,
        val licence: String,
        val params: String,
        val url: String,
        val file: String,
        val runtime: String,
        val measured: String,
        val unavailable: Boolean,
        val gated: Boolean,
        val note: String,
    ) {
        /** A model with no download URL cannot be fetched, however good it is. */
        val downloadable: Boolean get() = url.isNotBlank() && !unavailable
    }

    data class Registry(
        val categories: List<Category>,
        val tools: List<Tool>,
        val models: Map<String, ModelDef>,
        val source: String,
    ) {
        fun byCategory(): List<Pair<Category, List<Tool>>> =
            categories.map { c -> c to tools.filter { it.category == c.id } }
                .filter { it.second.isNotEmpty() }

        fun tool(id: String): Tool? = tools.firstOrNull { it.id == id }

        /** Tools that are wired but have no model on the device yet. */
        fun needingModels(installed: Set<String>): List<Tool> =
            tools.filter { t -> t.models.isNotEmpty() && t.models.none { it in installed } }
    }

    @Volatile
    private var cached: Registry? = null

    fun get(context: Context): Registry =
        cached ?: synchronized(this) {
            cached ?: load(context).also { cached = it }
        }

    /** Forget the cache, so a newly pushed tools-extra.json takes effect. */
    fun invalidate() {
        cached = null
    }

    private fun load(context: Context): Registry {
        val categories = LinkedHashMap<String, Category>()
        val tools = LinkedHashMap<String, Tool>()
        val models = LinkedHashMap<String, ModelDef>()
        val sources = mutableListOf<String>()

        val bundled = runCatching {
            context.assets.open("tools.json").bufferedReader().use { it.readText() }
        }.getOrNull()

        if (bundled != null) {
            merge(bundled, categories, tools, models)
            sources.add("bundled")
        }

        // The user's own file. A tool added here wins over a bundled tool with
        // the same id, so a shipped tool can be corrected without a rebuild.
        val extra = File(context.filesDir, "tools-extra.json")
        if (extra.isFile) {
            val text = runCatching { extra.readText() }.getOrNull()
            if (!text.isNullOrBlank()) {
                val ok = runCatching { merge(text, categories, tools, models) }.isSuccess
                if (ok) sources.add("user file")
            }
        }

        return Registry(
            categories = categories.values.toList(),
            tools = tools.values.toList(),
            models = models,
            source = sources.joinToString(" + ").ifEmpty { "none" },
        )
    }

    private fun merge(
        json: String,
        categories: MutableMap<String, Category>,
        tools: MutableMap<String, Tool>,
        models: MutableMap<String, ModelDef>,
    ) {
        val root = JSONObject(json)

        root.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                categories[id] = Category(id, o.getString("name"), o.optString("icon", "cube"))
            }
        }

        root.optJSONArray("tools")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val params = mutableListOf<Param>()
                o.optJSONArray("params")?.let { p ->
                    for (j in 0 until p.length()) {
                        val q = p.getJSONObject(j)
                        params.add(
                            Param(
                                key = q.getString("key"),
                                type = q.optString("type", "slider"),
                                min = q.optInt("min", 0),
                                max = q.optInt("max", 100),
                                default = q.optInt("default", 50),
                            ),
                        )
                    }
                }
                val modelIds = mutableListOf<String>()
                o.optJSONArray("models")?.let { m ->
                    for (j in 0 until m.length()) modelIds.add(m.getString(j))
                }
                tools[id] = Tool(
                    id = id,
                    name = o.getString("name"),
                    category = o.optString("category", "enhance"),
                    icon = o.optString("icon", "cube"),
                    purpose = o.optString("purpose", ""),
                    runner = o.optString("runner", "arithmetic"),
                    models = modelIds,
                    ready = o.optBoolean("ready", false),
                    screen = o.optString("screen").takeIf { it.isNotBlank() },
                    params = params,
                )
            }
        }

        root.optJSONObject("models")?.let { obj ->
            val keys = obj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val o = obj.getJSONObject(id)
                models[id] = ModelDef(
                    id = id,
                    name = o.optString("name", id),
                    role = o.optString("role", ""),
                    sizeMb = o.optInt("size_mb", 0),
                    licence = o.optString("licence", ""),
                    params = o.optString("params", ""),
                    url = o.optString("url", ""),
                    file = o.optString("file", "$id.onnx"),
                    runtime = o.optString("runtime", "onnx"),
                    measured = o.optString("measured", ""),
                    unavailable = o.optBoolean("unavailable", false),
                    gated = o.optBoolean("gated", false),
                    note = o.optString("note", ""),
                )
            }
        }
    }

    /**
     * Whether a tool can run right now.
     *
     * A tool is ready when it needs no model, or when one of the models it names
     * is installed. That is what lets the catalogue list tools honestly instead
     * of showing a grid where a third of the taps do nothing.
     */
    fun status(registry: Registry, tool: Tool, installed: Set<String>): Status = when {
        // Arithmetic tools need nothing at all.
        tool.models.isEmpty() -> Status.READY
        // One installed model is enough - the app picks the best of what it has.
        tool.models.any { it in installed } -> Status.READY
        // Every model it names is undownloadable, so the tool cannot work yet.
        tool.models.all { id -> registry.models[id]?.downloadable == false } -> Status.NOT_BUILT
        else -> Status.NEEDS_MODEL
    }

    enum class Status { READY, NEEDS_MODEL, NOT_BUILT }

    fun label(status: Status): String = when (status) {
        Status.READY -> "Ready"
        Status.NEEDS_MODEL -> "Needs a model"
        Status.NOT_BUILT -> "Not built yet"
    }
}
