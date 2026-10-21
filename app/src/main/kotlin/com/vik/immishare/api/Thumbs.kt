package com.vik.immishare.api

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object Thumbs {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val gate = Semaphore(6)

    fun cached(api: Immich, id: String): ImageBitmap? = cache.get(api.link.url + id)

    suspend fun load(api: Immich, id: String): ImageBitmap? {
        val key = api.link.url + id
        cache.get(key)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                cache.get(key) ?: runCatching {
                    val bytes = api.thumbnail(id)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()?.also { cache.put(key, it) }
            }
        }
    }
}
