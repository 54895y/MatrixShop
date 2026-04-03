package com.y54895.matrixshop.core.warehouse

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.warning
import java.util.UUID

/**
 * External warehouse plugin SPI.
 *
 * MatrixShop keeps only the interface here. The actual warehouse storage and UI
 * are expected to be implemented by a separate plugin that registers itself
 * through Bukkit's ServicesManager.
 */
interface CommerceWarehouseProvider {

    val providerId: String

    fun store(request: CommerceWarehouseStoreRequest): Boolean

    fun openWarehouse(viewer: Player, ownerId: UUID = viewer.uniqueId): Boolean

    fun pendingCount(ownerId: UUID): Int
}

data class CommerceWarehouseStoreRequest(
    val ownerId: UUID,
    val ownerName: String,
    val sourceModule: String,
    val sourceId: String,
    val reason: String,
    val item: ItemStack,
    val money: Double = 0.0,
    val currencyKey: String = "vault",
    val metadata: Map<String, String> = emptyMap()
)

object CommerceWarehouseBridge {

    private var provider: CommerceWarehouseProvider? = null
    private val missingRequirements = linkedSetOf<String>()
    private var warnedStoreMissing = false
    private var warnedOpenMissing = false

    fun reload() {
        provider = Bukkit.getServicesManager()
            .getRegistration(CommerceWarehouseProvider::class.java)
            ?.provider
        missingRequirements.clear()
        warnedStoreMissing = false
        warnedOpenMissing = false
        if (provider == null) {
            warning("No external warehouse provider is registered. Warehouse handoff interfaces remain unavailable.")
        }
    }

    fun isAvailable(): Boolean {
        return provider != null
    }

    fun providerName(): String {
        return provider?.providerId ?: "none"
    }

    /**
     * Defensive guard for features that depend on warehouse retention.
     *
     * Call this during module reload or config validation when a feature enables
     * "retain to warehouse" behavior. This records the missing requirement and
     * emits a clear backend warning exactly once per feature/reason pair.
     */
    fun requireAvailable(feature: String, enabled: Boolean, reason: String = "retain-to-warehouse"): Boolean {
        if (!enabled) {
            return true
        }
        if (provider != null) {
            return true
        }
        val key = "$feature:$reason"
        if (missingRequirements.add(key)) {
            warning(
                "MatrixShop feature '$feature' has '$reason' enabled, but no external warehouse provider is registered. " +
                    "Disable warehouse retention or install a compatible warehouse plugin."
            )
        }
        return false
    }

    fun requirementSummary(): String {
        if (provider != null) {
            return "ready"
        }
        if (missingRequirements.isEmpty()) {
            return "provider-missing"
        }
        return "required-by=" + missingRequirements.joinToString(",")
    }

    fun store(request: CommerceWarehouseStoreRequest): Boolean {
        val target = provider
        if (target == null) {
            if (!warnedStoreMissing) {
                warning(
                    "MatrixShop attempted to store a warehouse entry for source '${request.sourceModule}' " +
                        "but no external warehouse provider is registered."
                )
                warnedStoreMissing = true
            }
            return false
        }
        return target.store(request)
    }

    fun openWarehouse(viewer: Player, ownerId: UUID = viewer.uniqueId): Boolean {
        val target = provider
        if (target == null) {
            if (!warnedOpenMissing) {
                warning("MatrixShop attempted to open a warehouse UI, but no external warehouse provider is registered.")
                warnedOpenMissing = true
            }
            return false
        }
        return target.openWarehouse(viewer, ownerId)
    }

    fun pendingCount(ownerId: UUID): Int {
        return provider?.pendingCount(ownerId) ?: 0
    }
}
