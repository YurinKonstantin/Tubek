package ru.tubek.app.data

data class CountryOption(
    val code: String,
    val nameRu: String
)

object Countries {
    const val AUTO = "AUTO"

    val manual: List<CountryOption> = listOf(
        CountryOption("RU", "Россия"),
        CountryOption("US", "США"),
        CountryOption("GB", "Великобритания"),
        CountryOption("DE", "Германия"),
        CountryOption("FR", "Франция"),
        CountryOption("TR", "Турция"),
        CountryOption("KZ", "Казахстан"),
        CountryOption("UA", "Украина"),
        CountryOption("BY", "Беларусь"),
        CountryOption("PL", "Польша"),
        CountryOption("IT", "Италия"),
        CountryOption("ES", "Испания"),
        CountryOption("BR", "Бразилия"),
        CountryOption("IN", "Индия"),
        CountryOption("JP", "Япония"),
        CountryOption("KR", "Южная Корея"),
        CountryOption("NL", "Нидерланды"),
        CountryOption("SE", "Швеция"),
        CountryOption("FI", "Финляндия"),
        CountryOption("AE", "ОАЭ")
    )

    fun nameFor(code: String): String {
        if (code.equals(AUTO, ignoreCase = true)) return "Авто"
        return manual.firstOrNull { it.code.equals(code, ignoreCase = true) }?.nameRu
            ?: code.uppercase()
    }
}
