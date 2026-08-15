package ru.tubek.app.data

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

object CountryDetector {
    fun detect(context: Context): String {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val network = telephony?.networkCountryIso?.trim().orEmpty()
        val sim = telephony?.simCountryIso?.trim().orEmpty()
        val locale = Locale.getDefault().country.trim()
        val code = when {
            network.length == 2 -> network
            sim.length == 2 -> sim
            locale.length == 2 -> locale
            else -> "US"
        }
        return code.uppercase(Locale.US)
    }
}
