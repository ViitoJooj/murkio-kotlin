package com.murkio.identity.android.internal

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * fnv1a32 is the short, non-cryptographic hash that names the storage records
 * of a publishable key. Every Murkio SDK computes the same one (FNV-1a, 32
 * bits, lowercase hex), so the name stays the same across them.
 */
internal fun fnv1a32(value: String): String {
    var hash = 0x811c9dc5.toInt()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (byte.toInt() and 0xff)
        hash *= 0x01000193
    }
    return (hash.toLong() and 0xffffffffL).toString(16).padStart(8, '0')
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

/** A random string of unreserved URL characters (PKCE verifier and OAuth state). */
internal fun randomString(length: Int, random: SecureRandom = SecureRandom()): String {
    val out = StringBuilder(length)
    repeat(length) { out.append(UNRESERVED[random.nextInt(UNRESERVED.length)]) }
    return out.toString()
}

/** S256 code challenge of a PKCE verifier (RFC 7636). */
internal fun codeChallenge(verifier: String): String = base64UrlEncode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))

private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

// android.util.Base64 is a stub in unit tests and java.util.Base64 needs API
// 26, so the few places that need base64 use these.
internal fun base64UrlEncode(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size * 4 + 2) / 3)
    var index = 0
    while (index < bytes.size) {
        val b0 = bytes[index].toInt() and 0xff
        val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xff else -1
        val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xff else -1
        out.append(URL_ALPHABET[b0 shr 2])
        out.append(URL_ALPHABET[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
        if (b1 >= 0) out.append(URL_ALPHABET[((b1 and 0x0f) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
        if (b2 >= 0) out.append(URL_ALPHABET[b2 and 0x3f])
        index += 3
    }
    return out.toString()
}

/** Decodes base64 in either alphabet, with or without padding. Null when it is not base64. */
internal fun base64Decode(text: String): ByteArray? {
    val clean = text.trimEnd('=')
    if (clean.length % 4 == 1) return null
    val out = ByteArray(clean.length * 3 / 4)
    var buffer = 0
    var bits = 0
    var written = 0
    for (char in clean) {
        val value = when (char) {
            in 'A'..'Z' -> char - 'A'
            in 'a'..'z' -> char - 'a' + 26
            in '0'..'9' -> char - '0' + 52
            '+', '-' -> 62
            '/', '_' -> 63
            else -> return null
        }
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out[written++] = ((buffer shr bits) and 0xff).toByte()
        }
    }
    return if (written == out.size) out else out.copyOf(written)
}
