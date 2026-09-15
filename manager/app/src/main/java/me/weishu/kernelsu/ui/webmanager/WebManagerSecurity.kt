package me.weishu.kernelsu.ui.webmanager

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object WebManagerSecurity {
    const val TOKEN_COOKIE_NAME = "apkesu_web_token"

    private const val MAX_MODULE_ID_LENGTH = 128
    private val moduleIdPattern = Regex("[A-Za-z0-9._-]{1,$MAX_MODULE_ID_LENGTH}")

    fun isValidModuleId(value: String): Boolean = moduleIdPattern.matches(value)

    /** Decode one URL path segment and reject separators or traversal payloads. */
    fun decodeModuleId(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()?.takeIf(::isValidModuleId)

    /**
     * Mutating requests must originate from the loopback page served by this
     * process. A missing Origin is kept valid for native clients that do not
     * send browser headers; the literal browser value "null" is not valid.
     */
    fun isAllowedOrigin(origin: String?, port: Int): Boolean {
        val value = origin?.trim() ?: return true
        if (value.isEmpty() || value.equals("null", ignoreCase = true)) return false
        return value == "http://127.0.0.1:$port" ||
            value == "http://localhost:$port"
    }

    fun bearerToken(authorization: String?): String? {
        val value = authorization?.trim() ?: return null
        val prefix = "Bearer "
        return value.takeIf { it.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true) }
            ?.substring(prefix.length)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    fun constantTimeEquals(expected: String, actual: String?): Boolean {
        if (actual == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun cookieToken(cookieHeader: String?): String? {
        return cookieHeader
            ?.split(';')
            ?.asSequence()
            ?.map { it.trim() }
            ?.mapNotNull { item ->
                val separator = item.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = item.substring(0, separator).trim()
                if (name != TOKEN_COOKIE_NAME) return@mapNotNull null
                item.substring(separator + 1).trim().takeIf(String::isNotEmpty)
            }
            ?.firstOrNull()
    }
}
