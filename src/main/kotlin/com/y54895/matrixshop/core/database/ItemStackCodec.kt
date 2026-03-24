package com.y54895.matrixshop.core.database

import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

object ItemStackCodec {

    fun encode(item: ItemStack?): String {
        if (item == null) {
            return ""
        }
        val output = ByteArrayOutputStream()
        BukkitObjectOutputStream(output).use { stream ->
            stream.writeObject(item)
        }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    fun decode(value: String?): ItemStack? {
        if (value.isNullOrBlank()) {
            return null
        }
        return runCatching {
            val bytes = Base64.getDecoder().decode(value)
            BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { stream ->
                stream.readObject() as? ItemStack
            }
        }.getOrNull()
    }
}
