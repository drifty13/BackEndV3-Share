package com.lhs.share.hub.service.account

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
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class SubAccountServiceTest {
    private val accountRepository = mockk<SubAccountRepository>()
    private val inventoryCurrentRepository = mockk<InventoryCurrentRepository>()
    private val inventoryRecordRepository = mockk<InventoryRecordRepository>()
    private val favoriteRepository = mockk<InventoryAgentFavoriteRepository>()
    private val operatorCurrentRepository = mockk<OperatorCurrentRepository>()
    private val operatorRecordRepository = mockk<OperatorRecordRepository>()
    private val operatorCorrectionRecordRepository = mockk<OperatorCorrectionRecordRepository>()
    private val operatorV3ImportRecordRepository = mockk<OperatorV3ImportRecordRepository>()
    private val tokenService = mockk<OpenApiTokenService>()
    private val annotationRepository = mockk<OperatorAnnotationRepository>()
    private val targetRepository = mockk<OperatorGrowthTargetRepository>()
    private val upgradeRepository = mockk<OperatorUpgradeTransactionRepository>()
    private val revisionRepository = mockk<InventoryRevisionRepository>()
    private val starInventoryCurrentRepository = mockk<StarInventoryCurrentRepository>()
    private val transactionTemplate = TransactionTemplate(
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
            override fun commit(status: TransactionStatus) = Unit
            override fun rollback(status: TransactionStatus) = Unit
        },
    )
    private val service = SubAccountService(
        accountRepository,
        inventoryCurrentRepository,
        inventoryRecordRepository,
        favoriteRepository,
        operatorCurrentRepository,
        operatorRecordRepository,
        operatorCorrectionRecordRepository,
        operatorV3ImportRecordRepository,
        tokenService,
        transactionTemplate,
        annotationRepository,
        targetRepository,
        upgradeRepository,
        revisionRepository,
        starInventoryCurrentRepository,
    )

    @Test
    fun `create list and partial updates preserve account identity and fields`() {
        every { accountRepository.countByUserId("u1") } returns 0
        every { accountRepository.findAllByUserIdOrderByCreatedAtAsc("u1") } answers {
            listOf(SubAccount(id = "mongo-id", userId = "u1", accountId = "main", name = "大号", game = "如鸢"))
        }
        val stored = mutableMapOf<String, SubAccount>()
        every { accountRepository.findByUserIdAndAccountId("u1", any()) } answers {
            stored[secondArg()] ?: SubAccount(id = "mongo-id", userId = "u1", accountId = secondArg(), name = "大号", game = "如鸢")
        }
        every { accountRepository.save(any()) } answers {
            firstArg<SubAccount>().let { account ->
                val saved = if (account.id == null) account.copy(id = "mongo-id") else account
                stored[saved.accountId] = saved
                saved
            }
        }

        val created = service.create("u1", "新账号", "如鸢")
        val listed = service.list("u1")
        val gameOnly = service.update("u1", created.id, null, "代号鸢")
        val nameOnly = service.update("u1", created.id, "改名", null)

        assertTrue(created.id.matches(Regex("^acc_[0-9a-f]{32}$")))
        assertEquals("如鸢", created.game)
        assertEquals("main", listed.single().id)
        assertEquals("如鸢", listed.single().game)
        assertEquals(created.id, gameOnly.id)
        assertEquals("新账号", gameOnly.name)
        assertEquals("代号鸢", gameOnly.game)
        assertEquals(created.id, nameOnly.id)
        assertEquals("改名", nameOnly.name)
        assertEquals("代号鸢", nameOnly.game)
        assertEquals(created.createdAt, gameOnly.createdAt)
        assertNotEquals(created.updatedAt, nameOnly.updatedAt)
        verify(exactly = 0) { inventoryCurrentRepository.deleteAllByUserIdAndAccountId(any(), any()) }
        verify(exactly = 0) { inventoryRecordRepository.deleteAllByUserIdAndAccountId(any(), any()) }
        verify(exactly = 0) { favoriteRepository.deleteAllByUserIdAndAccountId(any(), any()) }
        verify(exactly = 0) { operatorCurrentRepository.deleteAllByUserIdAndAccountId(any(), any()) }
        verify(exactly = 0) { operatorRecordRepository.deleteAllByUserIdAndAccountId(any(), any()) }
        verify(exactly = 0) { operatorCorrectionRecordRepository.deleteAllByUserIdAndAccountId(any(), any()) }
        verify(exactly = 0) { operatorV3ImportRecordRepository.deleteAllByUserIdAndAccountId(any(), any()) }
        verify(exactly = 0) { annotationRepository.deleteAllByUserIdAndAccountId(any(), any()) }
        verify(exactly = 0) { targetRepository.deleteAllByUserIdAndAccountId(any(), any()) }
        verify(exactly = 0) { upgradeRepository.deleteAllByUserIdAndAccountId(any(), any()) }
        verify(exactly = 0) { starInventoryCurrentRepository.deleteAllByUserIdAndAccountId(any(), any()) }
    }

    @Test
    fun `create defaults game to code yuan`() {
        every { accountRepository.countByUserId("u1") } returns 0
        every { accountRepository.save(any()) } answers { firstArg<SubAccount>().copy(id = "mongo-id") }

        assertEquals("代号鸢", service.create("u1", "默认账号").game)
    }

    @Test
    fun `invalid game and empty patch return stable 422 errors`() {
        every { accountRepository.findByUserIdAndAccountId("u1", "main") } returns
            SubAccount(id = "mongo-id", userId = "u1", accountId = "main", name = "大号")

        val invalidCreate = assertThrows(InventoryApiException::class.java) { service.create("u1", "大号", "universal") }
        val invalid = assertThrows(InventoryApiException::class.java) { service.update("u1", "main", null, "all") }
        val empty = assertThrows(InventoryApiException::class.java) { service.update("u1", "main", null, null) }

        assertEquals("invalid_game", invalidCreate.code)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalid.status)
        assertEquals("invalid_game", invalid.code)
        assertEquals("game 只允许 代号鸢 或 如鸢", invalid.message)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, empty.status)
        assertEquals("schema_validation_failed", empty.code)
        verify(exactly = 0) { accountRepository.save(any()) }
    }

    @Test
    fun `foreign account is hidden and duplicate name returns conflict`() {
        every { accountRepository.findByUserIdAndAccountId("u1", "foreign") } returns null
        every { accountRepository.countByUserId("u1") } returns 0
        every { accountRepository.save(any()) } throws DuplicateKeyException("duplicate")

        val missing = assertThrows(InventoryApiException::class.java) { service.update("u1", "foreign", "名称", null) }
        val conflict = assertThrows(InventoryApiException::class.java) { service.create("u1", "重复") }

        assertEquals(404, missing.status.value())
        assertEquals("account_not_found", missing.code)
        assertEquals(409, conflict.status.value())
        assertEquals("account_name_conflict", conflict.code)
    }

    @Test
    fun `eleventh account is rejected`() {
        every { accountRepository.countByUserId("u1") } returns 10

        val error = assertThrows(InventoryApiException::class.java) { service.create("u1", "超额账号") }

        assertEquals(409, error.status.value())
        assertEquals("account_limit_reached", error.code)
        verify(exactly = 0) { accountRepository.save(any()) }
    }

    @Test
    fun `delete cascades all domains data favorites and tokens`() {
        every { accountRepository.findByUserIdAndAccountId("u1", "main") } returns
            SubAccount(id = "mongo-id", userId = "u1", accountId = "main", name = "大号")
        every { inventoryCurrentRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { inventoryRecordRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { favoriteRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { operatorCurrentRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { operatorRecordRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { operatorCorrectionRecordRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { operatorV3ImportRecordRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { annotationRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { targetRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { upgradeRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { revisionRepository.deleteByUserIdAndAccountId("u1", "main") } returns 1
        every { starInventoryCurrentRepository.deleteAllByUserIdAndAccountId("u1", "main") } just runs
        every { tokenService.revokeByAccount("u1", "main") } just runs
        every { accountRepository.deleteById("mongo-id") } just runs

        service.delete("u1", "main")

        verify(exactly = 1) { inventoryCurrentRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { inventoryRecordRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { favoriteRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { operatorCurrentRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { operatorRecordRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { operatorCorrectionRecordRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { operatorV3ImportRecordRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { annotationRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { targetRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { upgradeRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { revisionRepository.deleteByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { starInventoryCurrentRepository.deleteAllByUserIdAndAccountId("u1", "main") }
        verify(exactly = 1) { tokenService.revokeByAccount("u1", "main") }
        verify(exactly = 1) { accountRepository.deleteById("mongo-id") }
    }
}
