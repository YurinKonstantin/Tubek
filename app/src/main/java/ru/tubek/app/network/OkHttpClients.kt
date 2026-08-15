package ru.tubek.app.network

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import ru.tubek.app.proxy.ProxyPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object OkHttpClients {
    private val metadataRef = AtomicReference<OkHttpClient?>(null)
    private val downloadRef = AtomicReference<OkHttpClient?>(null)

    fun metadata(context: Context): OkHttpClient {
        metadataRef.get()?.let { return it }
        return rebuildMetadata(context)
    }

    fun download(): OkHttpClient {
        downloadRef.get()?.let { return it }
        return rebuildDownload()
    }

    fun rebuildMetadata(context: Context): OkHttpClient {
        val pool = ProxyPool.get()
        val viaProxy = pool.currentJavaProxy() != null
        // При включённом прокси не ждём 30 с на «прямой»/зависший запрос —
        // иначе смена прокси почти не срабатывает.
        val timeout = if (pool.isEnabled()) pool.responseTimeoutSec() else 30L
        val client = baseBuilder(
            connectSec = timeout,
            readSec = timeout,
            writeSec = timeout,
            callSec = if (pool.isEnabled()) timeout else null
        )
            .cache(
                Cache(
                    directory = context.cacheDir.resolve("http_cache"),
                    maxSize = 20L * 1024L * 1024L
                )
            )
            .applyProxy(viaProxy)
            .build()
        metadataRef.set(client)
        return client
    }

    fun rebuildDownload(): OkHttpClient {
        val pool = ProxyPool.get()
        val viaProxy = pool.currentJavaProxy() != null
        val connect = if (pool.isEnabled()) pool.responseTimeoutSec() else 30L
        val client = baseBuilder(
            connectSec = connect,
            readSec = 120,
            writeSec = 60,
            callSec = null
        )
            .followRedirects(true)
            .followSslRedirects(true)
            .applyProxy(viaProxy)
            .build()
        downloadRef.set(client)
        return client
    }

    fun rebuildAll(context: Context) {
        rebuildMetadata(context)
        rebuildDownload()
    }

    private fun baseBuilder(
        connectSec: Long,
        readSec: Long,
        writeSec: Long,
        callSec: Long?
    ): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .writeTimeout(writeSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
        if (callSec != null) {
            builder.callTimeout(callSec, TimeUnit.SECONDS)
        }
        return builder
    }

    private fun OkHttpClient.Builder.applyProxy(viaProxy: Boolean): OkHttpClient.Builder {
        if (viaProxy) {
            ProxyPool.get().currentJavaProxy()?.let { proxy(it) }
        }
        return this
    }
}
