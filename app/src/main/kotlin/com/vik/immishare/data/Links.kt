package com.vik.immishare.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class SavedLink(
    val link: ShareLink,
    val nickname: String,
    val token: String = "",
    val allowDownload: Boolean? = null,
    val allowUpload: Boolean? = null,
    val expiresAt: String? = null,
    val lastUsed: Long = System.currentTimeMillis(),
) {
    val title: String get() = nickname.ifBlank { link.host }
}

object Links {
    private const val MAX = 50
    private val state = MutableStateFlow<List<SavedLink>>(emptyList())
    private var loaded = false

    private fun sp(c: Context) = c.getSharedPreferences("immishare", Context.MODE_PRIVATE)

    fun all(c: Context): StateFlow<List<SavedLink>> {
        synchronized(this) {
            if (!loaded) {
                state.value = read(c)
                loaded = true
            }
        }
        return state
    }

    fun find(c: Context, link: ShareLink): SavedLink? = all(c).value.firstOrNull { it.link == link }

    @Synchronized
    fun upsert(c: Context, item: SavedLink) {
        val list = all(c).value.filterNot { it.link == item.link }
        write(c, (listOf(item) + list).take(MAX))
    }

    @Synchronized
    fun update(c: Context, link: ShareLink, toFront: Boolean = true, change: (SavedLink) -> SavedLink) {
        val list = all(c).value
        val i = list.indexOfFirst { it.link == link }
        if (i < 0 || toFront) return upsert(c, change(list.getOrNull(i) ?: SavedLink(link, "")).copy(link = link))
        write(c, list.toMutableList().apply { set(i, change(get(i)).copy(link = link)) })
    }

    @Synchronized
    fun rename(c: Context, old: ShareLink, nickname: String, base: String) {
        val list = all(c).value.toMutableList()
        val i = list.indexOfFirst { it.link == old }
        if (i < 0) return
        list[i] = list[i].copy(link = ShareLink(base.trimEnd('/'), old.key), nickname = nickname.trim())
        write(c, list.distinctBy { it.link })
    }

    @Synchronized
    fun delete(c: Context, link: ShareLink) = write(c, all(c).value.filterNot { it.link == link })

    private fun write(c: Context, list: List<SavedLink>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("base", it.link.base)
                    .put("key", it.link.key)
                    .put("nickname", it.nickname)
                    .put("token", it.token)
                    .put("allowDownload", it.allowDownload ?: JSONObject.NULL)
                    .put("allowUpload", it.allowUpload ?: JSONObject.NULL)
                    .put("expiresAt", it.expiresAt ?: JSONObject.NULL)
                    .put("lastUsed", it.lastUsed),
            )
        }
        sp(c).edit().putString("links", arr.toString()).apply()
        state.value = list
    }

    private fun read(c: Context): List<SavedLink> = runCatching {
        val arr = JSONArray(sp(c).getString("links", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedLink(
                link = ShareLink(o.getString("base"), o.getString("key")),
                nickname = o.optString("nickname"),
                token = o.optString("token"),
                allowDownload = if (o.isNull("allowDownload")) null else o.optBoolean("allowDownload"),
                allowUpload = if (o.isNull("allowUpload")) null else o.optBoolean("allowUpload"),
                expiresAt = if (o.isNull("expiresAt")) null else o.optString("expiresAt"),
                lastUsed = o.optLong("lastUsed"),
            )
        }
    }.getOrDefault(emptyList())
}
