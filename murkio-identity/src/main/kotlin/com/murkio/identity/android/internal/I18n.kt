package com.murkio.identity.android.internal

/** `pt_BR` and `pt-BR` are the same locale; the tables use the hyphen. */
internal fun normalizeLocale(locale: String): String = locale.replace('_', '-')

/** The language of a locale, without the region (`pt-BR` is `pt`). */
internal fun languageOf(locale: String): String = normalizeLocale(locale).substringBefore('-')

/** The table of a locale: the exact one, the Brazilian one for any Portuguese, English for everything else. */
internal fun tableFor(tables: Map<String, Map<String, String>>, locale: String): Map<String, String> {
    val normalized = normalizeLocale(locale)
    return tables[normalized]
        ?: if (languageOf(normalized) == "pt") tables.getValue("pt-BR") else tables.getValue("en")
}

private val PLACEHOLDER = Regex("\\{(\\w+)\\}")

/** A text of the built-in screens (email code, two-step verification, password reset). */
internal fun murkioString(locale: String, key: String, params: Map<String, String> = emptyMap()): String {
    val template = tableFor(Spec.strings, locale)[key] ?: Spec.strings.getValue("en")[key] ?: key
    return PLACEHOLDER.replace(template) { match -> params[match.groupValues[1]] ?: "" }
}

private val PROVIDER_NAMES = mapOf(
    "google" to "Google",
    "github" to "GitHub",
    "apple" to "Apple",
    "facebook" to "Facebook",
    "x" to "X",
)

/** The display name of a social sign-in provider. */
internal fun providerName(provider: String): String = PROVIDER_NAMES[provider] ?: provider
