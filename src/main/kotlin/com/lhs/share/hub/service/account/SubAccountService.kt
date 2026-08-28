package com.lhs.share.hub.service.account

import com.lhs.share.hub.controller.account.response.SubAccountResponse
import com.lhs.share.hub.repository.InventoryAgentFavoriteRepository
import com.lhs.share.hub.repository.InventoryCurrentRepository
import com.lhs.share.hub.repository.InventoryRecordRepository
import com.lhs.share.hub.repository.InventoryRevisionRepository
import com.lhs.share.hub.repository.OperatorAnnotationRepository
import com.lhs.share.hub.repository.OperatorCorrectionRecordRepository
import com.lhs.share.hub.repository.OperatorCurrentRepository
import com.lhs.share.hub.repository.OperatorGrowthTargetRepository
import com.lhs.share.hub.repository.OperatorRecordRepository
import com.lhs.share.hub.repository.OperatorUpgradeTransactionRepository
import com.lhs.share.hub.repository.OperatorV3ImportRecordRepository
import com.lhs.share.hub.repository.StarInventoryCurrentRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.lhs.share.openapi.OpenApiTokenService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * 统一子账号服务(库存 × 密探共用)
 *
 * 一个子账号 = 用户的某个游戏登录账号。删除 = 整账号级联:库存 current/records、
 * 密探 current/records、特别关注、全部绑定的 token 一起删除。
 */
@Service
class SubAccountService(
    private val accountRepository: SubAccountRepository,
    private val inventoryCurrentRepository: InventoryCurrentRepository,
    private val inventoryRecordRepository: InventoryRecordRepository,
    private val favoriteRepository: InventoryAgentFavoriteRepository,
    private val operatorCurrentRepository: OperatorCurrentRepository,
    private val operatorRecordRepository: OperatorRecordRepository,
    private val operatorCorrectionRecordRepository: OperatorCorrectionRecordRepository,
    private val operatorV3ImportRecordRepository: OperatorV3ImportRecordRepository,
    private val tokenService: OpenApiTokenService,
    @param:Qualifier("hubTransactionTemplate") private val transactionTemplate: TransactionTemplate,
    private val operatorAnnotationRepository: OperatorAnnotationRepository? = null,
    private val operatorGrowthTargetRepository: OperatorGrowthTargetRepository? = null,
    private val operatorUpgradeTransactionRepository: OperatorUpgradeTransactionRepository? = null,
    private val inventoryRevisionRepository: InventoryRevisionRepository? = null,
    private val starInventoryCurrentRepository: StarInventoryCurrentRepository,
) {
    fun create(userId: String, name: String, game: String? = null): SubAccountResponse {
        val normalizedGame = normalizeGame(game ?: DEFAULT_GAME)
        if (accountRepository.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) {
            throw InventoryApiException(
                HttpStatus.CONFLICT,
                "account_limit_reached",
                "Account limit reached (maximum $MAX_ACCOUNTS_PER_USER)",
            )
        }
        val now = Instant.now()
        return try {
            SubAccountResponse.of(
                accountRepository.save(
                    SubAccount(
                        userId = userId,
                        accountId = "acc_${UUID.randomUUID().toString().replace("-", "")}",
                        name = name,
                        game = normalizedGame,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
            )
        } catch (e: DuplicateKeyException) {
            throw nameConflict()
        }
    }

    fun list(userId: String): List<SubAccountResponse> =
        accountRepository.findAllByUserIdOrderByCreatedAtAsc(userId).map(SubAccountResponse::of)

    fun update(userId: String, accountId: String, name: String?, game: String?): SubAccountResponse {
        if (name == null && game == null) {
            throw InventoryApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "schema_validation_failed",
                "name 或 game 至少提供一项",
            )
        }
        val account = requireAccount(userId, accountId)
        val nextGame = game?.let(::normalizeGame) ?: account.game
        return try {
            SubAccountResponse.of(
                accountRepository.save(
                    account.copy(
                        name = name ?: account.name,
                        game = nextGame,
                        updatedAt = Instant.now(),
                    ),
                ),
            )
        } catch (e: DuplicateKeyException) {
            throw nameConflict()
        }
    }

    private fun normalizeGame(game: String): String {
        if (game !in SUPPORTED_GAMES) {
            throw InventoryApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid_game",
                "game 只允许 代号鸢 或 如鸢",
            )
        }
        return game
    }

    fun delete(userId: String, accountId: String) {
        val account = requireAccount(userId, accountId)
        transactionTemplate.executeWithoutResult {
            inventoryCurrentRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            inventoryRecordRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            favoriteRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            operatorCurrentRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            operatorRecordRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            operatorCorrectionRecordRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            operatorV3ImportRecordRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            operatorAnnotationRepository?.deleteAllByUserIdAndAccountId(userId, accountId)
            operatorGrowthTargetRepository?.deleteAllByUserIdAndAccountId(userId, accountId)
            operatorUpgradeTransactionRepository?.deleteAllByUserIdAndAccountId(userId, accountId)
            inventoryRevisionRepository?.deleteByUserIdAndAccountId(userId, accountId)
            starInventoryCurrentRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            tokenService.revokeByAccount(userId, accountId)
            accountRepository.deleteById(checkNotNull(account.id))
        }
    }

    fun requireAccount(userId: String, accountId: String): SubAccount = accountRepository.findByUserIdAndAccountId(userId, accountId)
        ?: throw InventoryApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")

    private fun nameConflict() = InventoryApiException(
        HttpStatus.CONFLICT,
        "account_name_conflict",
        "An account with this name already exists",
    )

    companion object {
        const val MAX_ACCOUNTS_PER_USER = 10
        const val DEFAULT_GAME = "代号鸢"
        val SUPPORTED_GAMES = setOf(DEFAULT_GAME, "如鸢")
    }
}
