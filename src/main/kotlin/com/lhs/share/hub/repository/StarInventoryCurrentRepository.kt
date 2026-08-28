package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.StarInventoryCurrent
import org.springframework.data.mongodb.repository.MongoRepository

/**
 * HubBackend repository for one current star-inventory snapshot per account.
 */
interface StarInventoryCurrentRepository : MongoRepository<StarInventoryCurrent, String> {
    fun findByUserIdAndAccountId(userId: String, accountId: String): StarInventoryCurrent?

    fun deleteAllByUserIdAndAccountId(userId: String, accountId: String)
}
