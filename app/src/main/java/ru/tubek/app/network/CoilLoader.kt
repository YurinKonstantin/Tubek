package ru.tubek.app.network

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ImageLoader Coil через тот же OkHttp + прокси, что и метаданные.
 */
object CoilLoader {
    @Volatile
    private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        loader?.let { return it }
        return synchronized(this) {
            loader ?: build(context.applicationContext).also { loader = it }
        }
    }

    fun rebuild(context: Context) {
        synchronized(this) {
            loader = build(context.applicationContext)
        }
    }

    private fun build(context: Context): ImageLoader {
        val callFactory = Call.Factory { request: Request ->
            OkHttpClients.metadata(context).newCall(request)
        }
        return ImageLoader.Builder(context)
            .callFactory(callFactory)
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder(context).maxSizePercent(0.2).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_thumbs"))
                    .maxSizeBytes(40L * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
