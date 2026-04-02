package taboolib.platform.util

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerSessionMap<T>(
    private val onRemove: ((T) -> Unit)? = null,
    private val clearOnQuit: Boolean = false
) {

    private val map = ConcurrentHashMap<UUID, T>()

    fun getOrCreate(uniqueId: UUID, supplier: () -> T): T {
        return map.computeIfAbsent(uniqueId) { supplier() }
    }

    fun get(uniqueId: UUID): T? {
        return map[uniqueId]
    }

    fun set(uniqueId: UUID, value: T) {
        map[uniqueId] = value
    }

    fun remove(uniqueId: UUID) {
        map.remove(uniqueId)?.let { removed ->
            onRemove?.invoke(removed)
        }
    }

    fun clear() {
        if (onRemove != null) {
            map.values.forEach(onRemove)
        }
        map.clear()
    }

    fun clearOnQuit(): Boolean {
        return clearOnQuit
    }
}
