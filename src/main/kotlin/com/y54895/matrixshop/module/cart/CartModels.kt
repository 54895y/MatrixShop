package com.y54895.matrixshop.module.cart

import org.bukkit.inventory.ItemStack
import java.util.UUID

data class CartStore(
    val ownerId: UUID,
    val entries: MutableList<CartEntry> = mutableListOf()
)

data class CartEntry(
    val id: String,
    val sourceModule: String,
    val sourceId: String,
    val name: String,
    val currency: String,
    var snapshotPrice: Double,
    var amount: Int,
    val ownerName: String = "",
    val item: ItemStack,
    val editableAmount: Boolean = true,
    val protectedOnClear: Boolean = false,
    val watchOnly: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val metadata: MutableMap<String, String> = linkedMapOf()
)

data class CartValidation(
    val valid: Boolean,
    val state: String,
    val reason: String
)
