package com.y54895.matrixshop.core.database

import java.util.Base64

object StringMapCodec {

    fun encode(values: Map<String, String>): String {
        if (values.isEmpty()) {
            return ""
        }
        return values.entries.joinToString(";") { entry ->
            "${encodePart(entry.key)}=${encodePart(entry.value)}"
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
                val index = token.indexOf('=')
                if (index <= 0) {
                    return@forEach
                }
                val key = decodePart(token.substring(0, index))
                val value = decodePart(token.substring(index + 1))
                result[key] = value
            }
        return result
    }

    private fun encodePart(value: String): String {
        return Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    private fun decodePart(value: String): String {
        return runCatching {
            String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        }.getOrDefault(value)
    }
}
