package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document("star_completion")
@CompoundIndex(name = "idx_star_completion_operation_unique", def = "{'userId': 1, 'accountId': 1, 'operationId': 1}", unique = true)
data class StarCompletion(
    @Id val id: String,
    val userId: String,
    val accountId: String,
    val game: String,
    val operationId: String,
    /** Canonical request JSON, including expected versions and one-time node corrections. */
    val requestIdentity: String,
    val changes: List<StarCompletionChange>,
    val experienceConsumed: StarStateExperience,
    val bottlesConsumed: Map<String, Long>,
    /** Existing numeric-only receipts remain readable; new receipts also preserve unknown balances as null. */
    val bottleBalances: Map<String, Long?>,
    val inventoryRevision: Long,
    /** Immutable authoritative result at commit time; later state must be read from completion context. */
    val state: StarStateCurrent,
    val createdAt: Instant,
)

data class StarCompletionChange(val instanceId: String, val from: Int, val to: Int)
