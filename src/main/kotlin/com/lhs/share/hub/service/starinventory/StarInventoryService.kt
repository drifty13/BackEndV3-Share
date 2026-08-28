package com.lhs.share.hub.service.starinventory

import com.lhs.share.hub.controller.starinventory.request.StarInventoryCurrentRequest
import com.lhs.share.hub.controller.starinventory.request.StarInventoryEntryRequest
import com.lhs.share.hub.controller.starinventory.response.StarInventoryCurrentResponse
import com.lhs.share.hub.repository.StarInventoryCurrentRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.StarInventoryCurrent
import com.lhs.share.hub.repository.entity.StarInventoryEntry
import com.lhs.share.hub.service.inventory.InventoryApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class StarInventoryService(
    private val accountRepository: SubAccountRepository,
    private val currentRepository: StarInventoryCurrentRepository,
) {
    fun put(userId: String, accountId: String, request: StarInventoryCurrentRequest): StarInventoryCurrentResponse {
        requireAccount(userId, accountId)
        validate(request.entries)
        val stored = currentRepository.save(
            StarInventoryCurrent(
                id = "$userId:$accountId",
                userId = userId,
                accountId = accountId,
                effectiveAt = request.effectiveAt,
                entries = request.entries.map(::toEntity),
                updatedAt = Instant.now(),
            ),
        )
        return StarInventoryCurrentResponse.of(stored)
    }

    fun current(userId: String, accountId: String): StarInventoryCurrentResponse {
        requireAccount(userId, accountId)
        return currentRepository.findByUserIdAndAccountId(userId, accountId)
            ?.let(StarInventoryCurrentResponse::of)
            ?: StarInventoryCurrentResponse.empty(accountId)
    }

    private fun validate(entries: List<StarInventoryEntryRequest>) {
        val instanceIds = mutableSetOf<String>()
        entries.forEach { entry ->
            if (entry.instanceId.isBlank()) throw schemaError("entries[].instance_id is required")
            if (!instanceIds.add(entry.instanceId)) throw schemaError("entries[].instance_id must be unique")
            if (entry.kind !in KINDS) throw schemaError("entries[].kind must be main or support")
            if (entry.name.isBlank()) throw schemaError("entries[].name is required")
            if (entry.quality !in QUALITIES) throw schemaError("entries[].quality is invalid")
            if (entry.level !in 1..60) throw schemaError("entries[].level must be in 1..60")
        }
    }

    private fun requireAccount(userId: String, accountId: String) {
        if (accountRepository.findByUserIdAndAccountId(userId, accountId) == null) {
            throw InventoryApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")
        }
    }

    private fun toEntity(entry: StarInventoryEntryRequest): StarInventoryEntry = StarInventoryEntry(
        instanceId = entry.instanceId,
        kind = entry.kind,
        name = entry.name,
        quality = entry.quality,
        level = entry.level,
    )

    private fun schemaError(message: String) = InventoryApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "schema_validation_failed",
        message,
    )

    companion object {
        private val KINDS = setOf("main", "support")
        private val QUALITIES = setOf("orange", "purple", "blue", "green", "white")
    }
}
