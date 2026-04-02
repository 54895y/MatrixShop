@file:JvmName("MetaKt")

package taboolib.platform.util

import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.metadata.Metadatable
import taboolib.platform.BukkitPlugin

fun setMeta(target: Metadatable, key: String, value: Any?) {
    target.setMetadata(key, FixedMetadataValue(BukkitPlugin.getInstance(), value))
}

fun removeMeta(target: Metadatable, key: String) {
    target.removeMetadata(key, BukkitPlugin.getInstance())
}
