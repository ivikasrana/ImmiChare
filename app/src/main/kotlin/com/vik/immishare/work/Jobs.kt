package com.vik.immishare.work

import android.content.Context
import com.vik.immishare.data.ShareLink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Item(
    val ref: String,
    val name: String?,
    val size: Long?,
    val video: Boolean,
    val createdAt: String?,
    val mime: String?,
)

class Job(
    val id: String,
    val upload: Boolean,
    val link: ShareLink,
    val token: String,
    val title: String,
    val items: List<Item>,
) {
    fun file(c: Context) = File(dir(c), "$id.json")

    fun save(c: Context): File {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("ref", it.ref)
                    .put("name", it.name ?: JSONObject.NULL)
                    .put("size", it.size ?: JSONObject.NULL)
                    .put("video", it.video)
                    .put("createdAt", it.createdAt ?: JSONObject.NULL)
                    .put("mime", it.mime ?: JSONObject.NULL),
            )
        }
        val o = JSONObject()
            .put("id", id)
            .put("upload", upload)
            .put("base", link.base)
            .put("key", link.key)
            .put("token", token)
            .put("title", title)
            .put("items", arr)
        return file(c).apply { writeText(o.toString()) }
    }

    fun withItems(items: List<Item>) = Job(id, upload, link, token, title, items)

    companion object {
        fun dir(c: Context) = File(c.filesDir, "jobs").apply { mkdirs() }

        fun create(upload: Boolean, link: ShareLink, token: String, title: String, items: List<Item>) =
            Job(UUID.randomUUID().toString(), upload, link, token, title, items)

        fun load(file: File): Job? = runCatching {
            val o = JSONObject(file.readText())
            val arr = o.getJSONArray("items")
            fun JSONObject.s(n: String) = if (isNull(n)) null else optString(n)
            Job(
                id = o.getString("id"),
                upload = o.getBoolean("upload"),
                link = ShareLink(o.getString("base"), o.getString("key")),
                token = o.optString("token"),
                title = o.optString("title"),
                items = (0 until arr.length()).map { i ->
                    val it = arr.getJSONObject(i)
                    Item(it.getString("ref"), it.s("name"), if (it.isNull("size")) null else it.optLong("size"), it.optBoolean("video"), it.s("createdAt"), it.s("mime"))
                },
            )
        }.getOrNull()
    }
}
