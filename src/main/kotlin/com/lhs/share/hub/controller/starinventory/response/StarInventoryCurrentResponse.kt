package com.lhs.share.hub.controller.starinventory.response

import com.lhs.share.hub.repository.entity.StarInventoryCurrent
import com.lhs.share.hub.repository.entity.StarInventoryEntry
import java.time.Instant

data class StarInventoryCurrentResponse(
    val accountId: String,
    val effectiveAt: Instant?,
    val updatedAt: Instant?,
    val entries: List<StarInventoryEntryResponse>,
) {
    companion object {
        fun of(current: StarInventoryCurrent): StarInventoryCurrentResponse = StarInventoryCurrentResponse(
            accountId = current.accountId,
            effectiveAt = current.effectiveAt,
            updatedAt = current.updatedAt,
            entries = current.entries.map(StarInventoryEntryResponse::of),
        )

        fun empty(accountId: String): StarInventoryCurrentResponse = StarInventoryCurrentResponse(
            accountId = accountId,
            effectiveAt = null,
            updatedAt = null,
            entries = emptyList(),
        )
    }
}

data class StarInventoryEntryResponse(
    val instanceId: String,
    val kind: String,
    val name: String,
    val quality: String,
    val level: Int,
) {
    companion object {
        fun of(entry: StarInventoryEntry): StarInventoryEntryResponse = StarInventoryEntryResponse(
            instanceId = entry.instanceId,
            kind = entry.kind,
            name = entry.name,
            quality = entry.quality,
            level = entry.level,
        )
    }
}
