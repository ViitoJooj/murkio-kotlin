package com.murkio.identity.android

import com.murkio.identity.android.internal.Spec
import com.murkio.identity.android.internal.list
import com.murkio.identity.android.internal.obj
import com.murkio.identity.android.internal.tableFor

/** One problem reported by the API. */
public class MurkioErrorDetail(public val code: String, public val message: String, public val path: String?)

/**
 * Every failure the SDK reports. [code] is the stable API code (`rsb-00`,
 * `bss-01`...), `network` when the request never got an answer and `unknown`
 * for anything without a code. Show [localized] to users and use [code] in
 * your own logic: the API message is English only.
 */
public class MurkioException(
    public val code: String,
    public val status: Int,
    public val details: List<MurkioErrorDetail> = emptyList(),
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: details.firstOrNull()?.message ?: code, cause) {

    /** The message to show the user, in [locale]. */
    public fun localized(locale: String = "en"): String = translateError(code, locale)

    internal companion object {
        /** Reads the error of an API answer. */
        fun fromResponse(status: Int, body: Any?): MurkioException {
            val details = (body.obj()?.get("details").list() ?: emptyList()).mapNotNull { item ->
                val detail = item.obj() ?: return@mapNotNull null
                val code = detail["code"] as? String ?: return@mapNotNull null
                MurkioErrorDetail(code, detail["message"] as? String ?: "", detail["path"] as? String)
            }
            return MurkioException(details.firstOrNull()?.code ?: "unknown", status, details)
        }

        /** Any error as a [MurkioException] (`unknown` when it is something else). */
        fun from(error: Throwable): MurkioException = error as? MurkioException ?: MurkioException("unknown", 0, message = error.message ?: error.toString(), cause = error)
    }
}

/** Translates an error code (or `oauth.<error>` from a social sign-in return) for the user. */
public fun translateError(code: String, locale: String = "en"): String {
    val table = tableFor(Spec.errors, locale)
    return table[code] ?: table["unknown"] ?: Spec.errors.getValue("en").getValue("unknown")
}
