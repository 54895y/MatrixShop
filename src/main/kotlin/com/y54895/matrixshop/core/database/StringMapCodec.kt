package com.y54895.matrixshop.core.database

import java.util.Base64

object StringMapCodec {

    fun encode(values: Map<String, String>): String {
        if (values.isEmpty()) {
            return ""
        }
        return values.entries.joinToString(";") { entry ->
            "${encodePart(entry.key)}:${encodePart(entry.value)}"
        }
    }

    fun decode(raw: String?): MutableMap<String, String> {
        if (raw.isNullOrBlank()) {
            return linkedMapOf()
        }
        val result = linkedMapOf<String, String>()
        raw.split(';')
            .filter { it.isNotBlank() }
            .forEach { token ->
                val pair = splitToken(token) ?: return@forEach
                val key = decodePart(pair.first)
                val value = decodePart(pair.second)
                result[key] = value
            }
        return result
    }

    private fun encodePart(value: String): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    private fun decodePart(value: String): String {
        return decodeSafePart(value) ?: decodeLegacyPart(value) ?: value
    }

    private fun splitToken(token: String): Pair<String, String>? {
        val separator = token.indexOf(':')
        if (separator > 0 && separator < token.length - 1) {
            return token.substring(0, separator) to token.substring(separator + 1)
        }
        token.forEachIndexed { index, char ->
            if (char != '=') {
                return@forEachIndexed
            }
            val key = token.substring(0, index)
            val value = token.substring(index + 1)
            if (decodeLegacyPart(key) != null && decodeLegacyPart(value) != null) {
                return key to value
            }
        }
        return null
    }

    private fun decodeSafePart(value: String): String? {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return runCatching {
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun decodeLegacyPart(value: String): String? {
        return runCatching {
            val bytes = Base64.getDecoder().decode(value)
            val canonical = Base64.getEncoder().encodeToString(bytes)
            if (canonical == value) {
                String(bytes, Charsets.UTF_8)
            } else {
                null
            }
        }.getOrNull()
    }
}
