package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode

/** Currency -> primary country (ISO-3166 alpha-2) plus country display names. */
object CurrencyCountries {
    private val MAP: Map<String, Pair<String, String>> = mapOf(
        "AED" to ("AE" to "United Arab Emirates"), "ARS" to ("AR" to "Argentina"), "AUD" to ("AU" to "Australia"),
        "BDT" to ("BD" to "Bangladesh"), "BGN" to ("BG" to "Bulgaria"), "BHD" to ("BH" to "Bahrain"),
        "BRL" to ("BR" to "Brazil"), "BTN" to ("BT" to "Bhutan"), "CAD" to ("CA" to "Canada"),
        "CHF" to ("CH" to "Switzerland"), "CLP" to ("CL" to "Chile"), "CNY" to ("CN" to "China"),
        "COP" to ("CO" to "Colombia"), "CZK" to ("CZ" to "Czechia"), "DKK" to ("DK" to "Denmark"),
        "EGP" to ("EG" to "Egypt"), "EUR" to ("EU" to "the Eurozone"), "FJD" to ("FJ" to "Fiji"),
        "GBP" to ("GB" to "the United Kingdom"), "GEL" to ("GE" to "Georgia"), "GHS" to ("GH" to "Ghana"),
        "HKD" to ("HK" to "Hong Kong"), "HUF" to ("HU" to "Hungary"), "IDR" to ("ID" to "Indonesia"),
        "ILS" to ("IL" to "Israel"), "INR" to ("IN" to "India"), "ISK" to ("IS" to "Iceland"),
        "JOD" to ("JO" to "Jordan"), "JPY" to ("JP" to "Japan"), "KES" to ("KE" to "Kenya"),
        "KHR" to ("KH" to "Cambodia"), "KRW" to ("KR" to "South Korea"), "KWD" to ("KW" to "Kuwait"),
        "KZT" to ("KZ" to "Kazakhstan"), "LAK" to ("LA" to "Laos"), "LKR" to ("LK" to "Sri Lanka"),
        "MAD" to ("MA" to "Morocco"), "MMK" to ("MM" to "Myanmar"), "MUR" to ("MU" to "Mauritius"),
        "MVR" to ("MV" to "Maldives"), "MXN" to ("MX" to "Mexico"), "MYR" to ("MY" to "Malaysia"),
        "NGN" to ("NG" to "Nigeria"), "NOK" to ("NO" to "Norway"), "NPR" to ("NP" to "Nepal"),
        "NZD" to ("NZ" to "New Zealand"), "OMR" to ("OM" to "Oman"), "PEN" to ("PE" to "Peru"),
        "PHP" to ("PH" to "the Philippines"), "PKR" to ("PK" to "Pakistan"), "PLN" to ("PL" to "Poland"),
        "QAR" to ("QA" to "Qatar"), "RON" to ("RO" to "Romania"), "RSD" to ("RS" to "Serbia"),
        "RUB" to ("RU" to "Russia"), "SAR" to ("SA" to "Saudi Arabia"), "SEK" to ("SE" to "Sweden"),
        "SGD" to ("SG" to "Singapore"), "THB" to ("TH" to "Thailand"), "TRY" to ("TR" to "Türkiye"),
        "TWD" to ("TW" to "Taiwan"), "TZS" to ("TZ" to "Tanzania"), "UAH" to ("UA" to "Ukraine"),
        "UGX" to ("UG" to "Uganda"), "USD" to ("US" to "the United States"), "UZS" to ("UZ" to "Uzbekistan"),
        "VND" to ("VN" to "Vietnam"), "XAF" to ("CM" to "Central Africa"), "XOF" to ("SN" to "West Africa"),
        "ZAR" to ("ZA" to "South Africa"), "ZMW" to ("ZM" to "Zambia"), "MNT" to ("MN" to "Mongolia"),
        "AZN" to ("AZ" to "Azerbaijan"), "AMD" to ("AM" to "Armenia"), "CRC" to ("CR" to "Costa Rica"),
        "DOP" to ("DO" to "Dominican Republic"), "ETB" to ("ET" to "Ethiopia"), "SCR" to ("SC" to "Seychelles"),
    )

    /** Extra country names for codes that are not a currency's primary country (common EUR members etc.). */
    private val EXTRA_NAMES = mapOf(
        "FR" to "France", "DE" to "Germany", "IT" to "Italy", "ES" to "Spain", "NL" to "the Netherlands",
        "PT" to "Portugal", "GR" to "Greece", "AT" to "Austria", "BE" to "Belgium", "IE" to "Ireland",
        "FI" to "Finland", "HR" to "Croatia", "LU" to "Luxembourg", "MT" to "Malta", "SK" to "Slovakia",
        "SI" to "Slovenia", "EE" to "Estonia", "LV" to "Latvia", "LT" to "Lithuania", "CY" to "Cyprus",
    )

    private val COUNTRY_TO_CURRENCY: Map<String, String> =
        MAP.entries.associate { (cur, v) -> v.first to cur } + EXTRA_NAMES.keys.associateWith { "EUR" }

    val size: Int get() = MAP.size

    fun countryFor(c: CurrencyCode): String? = MAP[c.code]?.first

    fun currencyFor(countryCode: String): CurrencyCode? = COUNTRY_TO_CURRENCY[countryCode.uppercase()]?.let { CurrencyCode(it) }

    fun countryName(countryCode: String): String =
        MAP.values.firstOrNull { it.first.equals(countryCode, true) }?.second
            ?: EXTRA_NAMES[countryCode.uppercase()] ?: countryCode.uppercase()
}
