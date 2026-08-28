package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * The current finalized YuanStar inventory for one unified account.
 */
@Document("star_inventory_current")
@CompoundIndex(
    name = "idx_star_inventory_user_account",
    def = "{'userId': 1, 'accountId': 1}",
    unique = true,
)
data class StarInventoryCurrent(
    @Id
    val id: String? = null,
    val userId: String,
    val accountId: String,
    val entries: List<StarInventoryEntry> = emptyList(),
    val effectiveAt: Instant,
    val updatedAt: Instant = Instant.now(),
)

data class StarInventoryEntry(
    val instanceId: String,
    val kind: String,
    val name: String,
    val quality: String,
    val level: Int,
)
