package ru.tubek.app.youtube

import android.content.Context
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import ru.tubek.app.data.CountryDetector
import ru.tubek.app.network.OkHttpClients
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP-клиент для NewPipe Extractor (без YouTube Data API).
 */
class OkHttpDownloader private constructor(
    private val clientRef: AtomicReference<okhttp3.OkHttpClient>
) : Downloader() {

    override fun execute(request: Request): Response {
        val httpRequest = okhttp3.Request.Builder()
            .url(request.url())
            .headers(toHeaders(request))
            .method(
                request.httpMethod(),
                request.dataToSend()?.toRequestBody(null)
            )
            .build()

        clientRef.get().newCall(httpRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val latestUrl = response.request.url.toString()

            if (response.code == 429) {
                throw ReCaptchaException("Слишком много запросов (429)", latestUrl)
            }

            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                body,
                latestUrl
            )
        }
    }

    private fun toHeaders(request: Request): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.add(name, value) }
        }
        if (builder.get("User-Agent") == null) {
            builder.add("User-Agent", USER_AGENT)
        }
        // Всегда свой Accept-Language — иначе YouTube/прокси могут подставить чужой язык.
        builder.set("Accept-Language", YoutubeService.acceptLanguageHeader())
        return builder.build()
    }

    fun rebuild(context: Context) {
        clientRef.set(OkHttpClients.rebuildMetadata(context.applicationContext))
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        @Volatile
        private var instance: OkHttpDownloader? = null

        fun getInstance(context: Context): OkHttpDownloader {
            return instance ?: synchronized(this) {
                instance ?: OkHttpDownloader(
                    AtomicReference(OkHttpClients.metadata(context.applicationContext))
                ).also { instance = it }
            }
        }
    }
}

object YoutubeService {
    @Volatile
    private var initialized = false

    @Volatile
    private var preferredLanguageCode: String =
        Locale.getDefault().language.takeIf { it.length >= 2 }?.lowercase(Locale.US) ?: "en"

    @Volatile
    private var preferredCountryCode: String = "US"

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            preferredCountryCode = CountryDetector.detect(context)
            preferredLanguageCode = Locale.getDefault().language
                .takeIf { it.length >= 2 }
                ?.lowercase(Locale.US)
                ?: "en"
            val localization = localizationFor(preferredLanguageCode, preferredCountryCode)
            val country = ContentCountry(preferredCountryCode)
            NewPipe.init(
                OkHttpDownloader.getInstance(context.applicationContext),
                localization,
                country
            )
            applyPreferences(preferredLanguageCode, preferredCountryCode)
            initialized = true
        }
    }

    /**
     * Язык метаданных/Accept-Language — язык пользователя;
     * страна — отдельно для трендов/гео.
     *
     * Важно: Localization всегда с кодом страны. Language-only ("ru") даёт пустой
     * country в Android/iOS-клиентах NewPipe → YouTube отвечает invalid_argument.
     */
    fun applyPreferences(languageCode: String, countryCode: String) {
        preferredLanguageCode = normalizeLanguage(languageCode)
        preferredCountryCode = countryCode.trim().uppercase(Locale.US).ifBlank {
            defaultCountryFor(preferredLanguageCode)
        }
        val localization = localizationFor(preferredLanguageCode, preferredCountryCode)
        val country = ContentCountry(preferredCountryCode)
        NewPipe.setupLocalization(localization, country)
        NewPipe.setPreferredLocalization(localization)
        NewPipe.setPreferredContentCountry(country)
    }

    fun applyContentCountry(countryCode: String) {
        applyPreferences(preferredLanguageCode, countryCode)
    }

    fun applyLanguage(languageCode: String) {
        applyPreferences(languageCode, preferredCountryCode)
    }

    fun currentLocalization(): Localization =
        localizationFor(preferredLanguageCode, preferredCountryCode)

    fun currentContentCountry(): ContentCountry = ContentCountry(preferredCountryCode)

    fun preferredLanguage(): String = preferredLanguageCode

    fun rebuildProxyClients(context: Context) {
        OkHttpDownloader.getInstance(context).rebuild(context)
        OkHttpClients.rebuildDownload()
        ru.tubek.app.network.CoilLoader.rebuild(context.applicationContext)
    }

    fun localizationFor(languageCode: String, countryCode: String = preferredCountryCode): Localization {
        val lang = normalizeLanguage(languageCode).substringBefore('-')
        val country = countryCode.trim().uppercase(Locale.US).ifBlank { defaultCountryFor(lang) }
        return Localization(lang, country)
    }

    fun acceptLanguageHeader(): String {
        val lang = preferredLanguageCode.substringBefore('-')
        val country = preferredCountryCode.ifBlank { defaultCountryFor(lang) }
        return buildString {
            append(lang).append('-').append(country).append(',')
            append(lang).append(";q=0.9")
            if (!lang.equals("en", ignoreCase = true)) {
                append(",en-US;q=0.8,en;q=0.7")
            }
        }
    }

    private fun normalizeLanguage(code: String): String {
        val raw = code.trim().replace('_', '-').lowercase(Locale.US)
        if (raw.isBlank() || raw == "auto") {
            return Locale.getDefault().language.takeIf { it.length >= 2 }?.lowercase(Locale.US) ?: "en"
        }
        return raw
    }

    private fun defaultCountryFor(language: String): String = when (language.lowercase(Locale.US)) {
        "ru" -> "RU"
        "uk" -> "UA"
        "be" -> "BY"
        "kk" -> "KZ"
        "de" -> "DE"
        "fr" -> "FR"
        "es" -> "ES"
        "tr" -> "TR"
        "pl" -> "PL"
        "it" -> "IT"
        "pt" -> "BR"
        "ar" -> "SA"
        "zh" -> "CN"
        "ja" -> "JP"
        "ko" -> "KR"
        else -> "US"
    }
}
