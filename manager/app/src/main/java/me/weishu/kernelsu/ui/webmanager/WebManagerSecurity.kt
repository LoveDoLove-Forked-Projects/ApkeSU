package me.weishu.kernelsu.ui.webmanager

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object WebManagerSecurity {
    private const val MAX_MODULE_ID_LENGTH = 128
    private val moduleIdPattern = Regex("[A-Za-z0-9._-]{1,$MAX_MODULE_ID_LENGTH}")
    private const val MAX_UPLOAD_NAME_LENGTH = 96
    private val uploadNamePattern = Regex("[A-Za-z0-9._-]{1,$MAX_UPLOAD_NAME_LENGTH}")

    fun isValidModuleId(value: String): Boolean =
        moduleIdPattern.matches(value) && value != "." && value != ".."

    /**
     * 导入文件的落盘名：剥掉任何目录部分，只允许 `[A-Za-z0-9._-]`，且不以点开头。
     * 不合法就返回 null，由调用方生成兜底文件名，绝不把用户输入当路径用。
     */
    fun sanitizeUploadName(raw: String?): String? {
        val value = raw?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        if (value.isEmpty() || value.startsWith('.')) return null
        val collapsed = value.replace(' ', '_')
        return collapsed.takeIf(uploadNamePattern::matches)
    }

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

    fun isAllowedHost(host: String?, port: Int): Boolean {
        val value = host?.trim() ?: return false
        return value.equals("127.0.0.1:$port", ignoreCase = true) ||
            value.equals("localhost:$port", ignoreCase = true)
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

}
